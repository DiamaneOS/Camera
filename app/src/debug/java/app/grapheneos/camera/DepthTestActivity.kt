package app.grapheneos.camera

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import app.grapheneos.camera.capturer.PortraitSegmenter
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/** Owner-approved local experiment. No bundled depth model or production entry point. */
class DepthTestActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val label = TextView(this).apply { text = "Processing saved depth test" }
        setContentView(label)
        thread(name = "local-depth-test") {
            val root = File(filesDir, "processing-test/depth").apply { mkdirs() }
            val result = JSONObject().put("status", "running")
            val started = SystemClock.elapsedRealtime()
            File(root, "result.json").writeText(result.toString(2))
            var image: Bitmap? = null
            var resized: Bitmap? = null
            try {
                val modelFile = File(root, "model.tflite")
                require(modelFile.length() == 98_928_904L)
                val digest = MessageDigest.getInstance("SHA-256")
                modelFile.inputStream().use { input ->
                    val bytes = ByteArray(1 shl 20)
                    while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
                }
                check(digest.digest().joinToString("") { "%02x".format(it) } == "0bb8192ef8affcb956f6b1577b3ba3207238357f1fc2516211e0e2b8ff86381c")
                val source = File(root, "source.jpg")
                require(source.length() in 1..20 * 1024 * 1024)
                image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                    require(info.size.width.toLong() * info.size.height <= 16_000_000)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
                }
                val photo = image!!
                val scale = 518f / maxOf(photo.width, photo.height)
                val width = (photo.width * scale).roundToInt().coerceAtLeast(1)
                val height = (photo.height * scale).roundToInt().coerceAtLeast(1)
                val left = (518 - width) / 2; val top = (518 - height) / 2
                resized = Bitmap.createBitmap(518, 518, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(resized!!); canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(photo, null, RectF(left.toFloat(), top.toFloat(), (left + width).toFloat(), (top + height).toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                val pixels = IntArray(518 * 518); resized!!.getPixels(pixels, 0, 518, 0, 0, 518, 518)
                val input = ByteBuffer.allocateDirect(518 * 518 * 3 * 4).order(ByteOrder.nativeOrder())
                for (p in pixels) { input.putFloat(Color.red(p) / 255f); input.putFloat(Color.green(p) / 255f); input.putFloat(Color.blue(p) / 255f) }
                input.rewind()
                val output = ByteBuffer.allocateDirect(518 * 518 * 4).order(ByteOrder.nativeOrder())
                FileInputStream(modelFile).channel.use { channel ->
                    val model = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                    val loadStarted = SystemClock.elapsedRealtime()
                    Interpreter(model, Interpreter.Options().setNumThreads(2).setUseXNNPACK(true)).use { interpreter ->
                        check(interpreter.inputTensorCount == 1 && interpreter.outputTensorCount == 1)
                        check(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1, 518, 518, 3)))
                        check(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1, 518, 518, 1)))
                        check(interpreter.getInputTensor(0).dataType() == DataType.FLOAT32 && interpreter.getOutputTensor(0).dataType() == DataType.FLOAT32)
                        result.put("loadMs", SystemClock.elapsedRealtime() - loadStarted)
                        val inferenceStarted = SystemClock.elapsedRealtime(); interpreter.run(input, output)
                        result.put("inferenceMs", SystemClock.elapsedRealtime() - inferenceStarted)
                    }
                }
                output.rewind()
                val depth = ByteBuffer.allocate(width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
                var low = Float.POSITIVE_INFINITY; var high = Float.NEGATIVE_INFINITY
                for (y in 0 until 518) for (x in 0 until 518) {
                    val value = output.float; check(value.isFinite())
                    if (x in left until left + width && y in top until top + height) {
                        depth.putFloat(value); low = minOf(low, value); high = maxOf(high, value)
                    }
                }
                File(root, "depth.f32").writeBytes(depth.array())
                val subjectX = intent.getFloatExtra("subjectX", .84f); val subjectY = intent.getFloatExtra("subjectY", .20f)
                val segmentationModel = assets.open("portrait_subject_v1.tflite").use { it.readBytes() }
                val memory = ByteBuffer.allocateDirect(segmentationModel.size).order(ByteOrder.nativeOrder()).apply { put(segmentationModel); rewind() }
                val mask = PortraitSegmenter(memory).use { it.segment(photo, subjectX, subjectY) }
                val matte = ByteBuffer.allocate(mask.confidence.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                mask.confidence.forEach(matte::putFloat); File(root, "mask.f32").writeBytes(matte.array())
                result.put("width", width).put("height", height).put("maskWidth", mask.width).put("maskHeight", mask.height)
                    .put("sourceWidth", photo.width).put("sourceHeight", photo.height).put("depthMin", low).put("depthMax", high)
                    .put("subjectX", subjectX).put("subjectY", subjectY).put("segmentationMs", mask.inferenceMs).put("status", "ok")
            } catch (error: Throwable) {
                result.put("status", "error").put("error", error.javaClass.simpleName + ": " + error.message)
            } finally { resized?.recycle(); image?.recycle() }
            result.put("elapsedMs", SystemClock.elapsedRealtime() - started)
            File(root, "result.json").writeText(result.toString(2))
            runOnUiThread { label.text = result.toString(2) }
        }
    }
}
