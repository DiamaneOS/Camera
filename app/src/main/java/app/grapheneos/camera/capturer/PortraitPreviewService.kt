package app.grapheneos.camera.capturer

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.*
import android.system.OsConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Separate isolated worker: live masks cannot delay the durable photo-processing queue. */
class PortraitPreviewService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var segmenter: PortraitSegmenter? = null
    @Volatile private var closing = false
    @Volatile private var refinement: RawNativeCore.Task? = null
    private val processing = AtomicBoolean()
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        val data = message.data.apply { classLoader = SharedMemory::class.java.classLoader }
        val memory = try { androidx.core.os.BundleCompat.getParcelable(data, "pixels", SharedMemory::class.java) }
            catch (_: RuntimeException) { null }
        val reply = message.replyTo; val sequence = message.arg1
        if (closing || message.what != 1) memory?.close()
        else if (memory != null && !processing.compareAndSet(false, true)) {
            memory.close()
            try { reply?.send(Message.obtain(null, 1).apply { arg1 = sequence; this.data = Bundle().apply { putBoolean("failed", true) } }) }
            catch (_: RemoteException) { }
        } else if (memory != null) worker.execute {
            var image: Bitmap? = null
            var resultMemory: SharedMemory? = null
            val result = Bundle()
            val started = SystemClock.elapsedRealtime()
            var stage = "input"
            try {
                val w = data.getInt("width"); val h = data.getInt("height")
                require(w in 2..512 && h in 2..512 && memory.size == w * h * 4)
                val input = memory.mapReadOnly()
                val pixels = IntArray(w * h)
                try {
                    input.order(ByteOrder.nativeOrder()).asIntBuffer().get(pixels)
                } finally { SharedMemory.unmap(input) }
                image = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
                stage = "model"
                val model = segmenter ?: run {
                    val bytes = assets.open("portrait_subject_v1.tflite").use { it.readBytes() }
                    require(bytes.size == 6_227_884)
                    check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) } ==
                        "e24338a717c1b7ad8d159666677ef400babb7f33b8ad60c4d96db4ecf694cd25")
                    PortraitSegmenter(ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() })
                        .also { segmenter = it }
                }
                stage = "segment"
                val mask = model.segment(image, data.getFloat("x"), data.getFloat("y"))
                result.putLong("inferenceMs", mask.inferenceMs)
                stage = "refine"
                val refineStarted = SystemClock.elapsedRealtime()
                val refined = RawNativeCore.Task().use { task ->
                    refinement = task
                    try { check(!closing); task.refinePortraitMask(pixels, w, h, mask.confidence, mask.width, mask.height) }
                    finally { refinement = null }
                }
                result.putLong("refineMs", SystemClock.elapsedRealtime() - refineStarted)
                stage = "output"
                val output = SharedMemory.create("portrait-preview-mask", refined.size * 4)
                resultMemory = output
                val mapping = output.mapReadWrite()
                try {
                    val encoded = IntArray(refined.size) { index ->
                        -0x1000000 or ((refined[index] * 255).toInt() * 0x10101)
                    }
                    mapping.order(ByteOrder.nativeOrder()).asIntBuffer().put(encoded)
                }
                finally { SharedMemory.unmap(mapping) }
                check(output.setProtect(OsConstants.PROT_READ))
                result.putParcelable("mask", output); result.putInt("width", w); result.putInt("height", h)
                result.putString("format", "confidence_argb8888")
            } catch (error: Throwable) {
                // Out of memory included: a failed preview frame is a controlled result.
                result.putBoolean("failed", true)
                if (app.grapheneos.camera.BuildConfig.DEBUG) result.putString("failure", stage + ":" + error.javaClass.simpleName)
            }
            finally { image?.recycle(); memory.close() }
            result.putLong("workerMs", SystemClock.elapsedRealtime() - started)
            // A reply may immediately cause the client to submit its next frame.
            // The single executor still serializes work after admission is freed.
            processing.set(false)
            try { reply?.send(Message.obtain(null, 1).apply { arg1 = sequence; this.data = result }) }
            catch (_: RemoteException) { }
            finally { resultMemory?.close() }
        }
        true
    })
    override fun onBind(intent: Intent) = messenger.binder
    override fun onDestroy() {
        closing = true
        refinement?.cancel()
        try { segmenter?.cancel() } catch (_: Exception) { }
        worker.execute { segmenter?.close(); segmenter = null }; worker.shutdown()
        super.onDestroy()
    }
}
