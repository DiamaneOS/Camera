package app.grapheneos.camera

import android.app.Activity
import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.concurrent.thread

/** Shell/DUMP-only, fixed private paths, pinned model, bounded inputs. Never in release. */
class SegmentationBenchmarkActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply { text = "Testing local Portrait segmentation" })
        val x = intent.getFloatExtra("subjectX", .5f)
        val y = intent.getFloatExtra("subjectY", .5f)
        thread(name="segmentation-benchmark") {
            val directory=File(filesDir,"processing-test/segmentation").apply { mkdirs() }
            val result=JSONObject().put("status","running")
            File(directory,"result.json").writeText(result.toString())
            var image: Bitmap?=null
            try {
                require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
                val source=File(directory,"source.jpg");require(source.length() in 1..20*1024*1024)
                image=ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder,info,_ ->
                    require(info.size.width.toLong()*info.size.height<=16_000_000)
                    decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                }
                val bytes=assets.open("portrait_subject_v1.tflite").use { it.readBytes() }
                require(bytes.size==6_227_884 && MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") {"%02x".format(it.toInt() and 255)}=="e24338a717c1b7ad8d159666677ef400babb7f33b8ad60c4d96db4ecf694cd25")
                val model=ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {put(bytes);rewind()}
                val rows=JSONArray()
                for(side in listOf(512,256)) {
                    val row=JSONObject().put("inputSide",side)
                    try {
                        val load=SystemClock.elapsedRealtime()
                        Interpreter(model.duplicate(),Interpreter.Options().setNumThreads(2).setUseXNNPACK(true)).use { interpreter ->
                            if(side!=512) {interpreter.resizeInput(0,intArrayOf(1,side,side,4));interpreter.allocateTensors()}
                            require(interpreter.inputTensorCount==1 && interpreter.outputTensorCount==1)
                            require(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1,side,side,4)))
                            val shape=interpreter.getOutputTensor(0).shape()
                            require(shape.size==4 && shape[0]==1 && shape[1] in 2..512 && shape[2] in 2..512 && shape[3]==1)
                            require(interpreter.getInputTensor(0).dataType()==DataType.FLOAT32 && interpreter.getOutputTensor(0).dataType()==DataType.FLOAT32)
                            row.put("loadMs",SystemClock.elapsedRealtime()-load)
                            val started=SystemClock.elapsedRealtime();val resized=Bitmap.createScaledBitmap(image!!,side,side,true)
                            val pixels=IntArray(side*side)
                            try {resized.getPixels(pixels,0,side,0,0,side,side)} finally {if(resized!==image) resized.recycle()}
                            val input=FloatArray(side*side*4);val sx=(x*side).toInt().coerceAtMost(side-1);val sy=(y*side).toInt().coerceAtMost(side-1)
                            for(i in pixels.indices) {
                                input[i*4]=Color.red(pixels[i])/255f;input[i*4+1]=Color.green(pixels[i])/255f
                                input[i*4+2]=Color.blue(pixels[i])/255f;input[i*4+3]=if(i==sy*side+sx) 1f else 0f
                            }
                            val data=ByteBuffer.allocateDirect(input.size*4).order(ByteOrder.nativeOrder());data.asFloatBuffer().put(input)
                            val output=ByteBuffer.allocateDirect(shape[1]*shape[2]*4).order(ByteOrder.nativeOrder())
                            row.put("preprocessMs",SystemClock.elapsedRealtime()-started)
                            val inference=SystemClock.elapsedRealtime();interpreter.run(data,output);row.put("inferenceMs",SystemClock.elapsedRealtime()-inference)
                            output.rewind()
                            val confidence=FloatArray(shape[1]*shape[2]);output.asFloatBuffer().get(confidence)
                            require(confidence.all {it.isFinite() && it in 0f..1f})
                            val encoded=ByteBuffer.allocate(confidence.size*4).order(ByteOrder.LITTLE_ENDIAN);encoded.asFloatBuffer().put(confidence)
                            File(directory,"mask-$side.f32").writeBytes(encoded.array())
                            row.put("maskWidth",shape[2]).put("maskHeight",shape[1]).put("totalMs",SystemClock.elapsedRealtime()-started).put("status","ok")
                        }
                    } catch(error:Exception) {row.put("status","unsupported").put("errorType",error.javaClass.simpleName)}
                    rows.put(row)
                }
                result.put("status","ok").put("results",rows)
            } catch(error:Exception) {result.put("status","failed").put("errorType",error.javaClass.simpleName)}
            finally {image?.recycle()}
            File(directory,"result.json").writeText(result.toString(2))
        }
    }
}
