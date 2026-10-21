package app.grapheneos.camera.capturer

import android.content.*
import android.os.*
import android.system.OsConstants
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Called from a capture executor. Unbinds on timeout, cancellation, process death and success. */
internal object ProcessingClient {
    @Suppress("DEPRECATION")
    private fun closeReply(reply: Bundle) {
        reply.getParcelable<SharedMemory>("jpeg")?.close()
        reply.getParcelable<SharedMemory>("portraitData")?.close()
    }
    fun memory(name: String, buffer: ByteBuffer): SharedMemory {
        require(buffer.remaining() in 1..32 * 1024 * 1024)
        val memory = SharedMemory.create(name, buffer.remaining())
        try {
            val output = memory.mapReadWrite()
            try { output.put(buffer) } finally { SharedMemory.unmap(output) }
            check(memory.setProtect(OsConstants.PROT_READ))
            return memory
        } catch (error: Throwable) { memory.close(); throw error }
    }

    fun fileMemory(name: String, file: java.io.File, maximumBytes: Long = 32L * 1024 * 1024): SharedMemory {
        require(maximumBytes in 1..110L * 1024 * 1024 && file.length() in 1..maximumBytes)
        val memory = SharedMemory.create(name, file.length().toInt())
        try {
            val output = memory.mapReadWrite()
            try {
                java.io.FileInputStream(file).use { stream ->
                    while (output.hasRemaining()) check(stream.channel.read(output) > 0) { "Short processing input" }
                }
            } finally { SharedMemory.unmap(output) }
            check(memory.setProtect(OsConstants.PROT_READ))
            return memory
        } catch (error: Throwable) { memory.close(); throw error }
    }

    @Suppress("DEPRECATION")
    /** 250 ms, doubling up to 4 s. */
    internal fun busyBackoffMs(attempt: Int): Long = 250L shl attempt.coerceIn(0, 4)

    fun process(context: Context, payload: Bundle, cancelled: () -> Boolean, stats: Bundle? = null,
                portraitData: ((SharedMemory) -> Unit)? = null): ByteArray {
        val connected = ArrayBlockingQueue<Messenger>(1)
        val replies = ArrayBlockingQueue<Bundle>(1)
        val dead = java.util.concurrent.atomic.AtomicBoolean()
        val accepting = java.util.concurrent.atomic.AtomicBoolean(true)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { connected.offer(Messenger(binder)) }
            override fun onServiceDisconnected(name: ComponentName) { dead.set(true) }
            override fun onBindingDied(name: ComponentName) { dead.set(true) }
            override fun onNullBinding(name: ComponentName) { dead.set(true) }
        }
        val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            message.data.classLoader = SharedMemory::class.java.classLoader
            if (!accepting.get() || !replies.offer(message.data)) closeReply(message.data)
            true
        })
        if (!context.bindService(Intent(context, CameraProcessingService::class.java), connection, Context.BIND_AUTO_CREATE)) {
            try { context.unbindService(connection) } catch (_: IllegalArgumentException) { }
            error("Processor unavailable")
        }
        try {
            val end = SystemClock.elapsedRealtime() + 60_000
            var target: Messenger? = null
            while (target == null) {
                check(!cancelled() && !dead.get() && SystemClock.elapsedRealtime() < end) { "Processor unavailable" }
                target = connected.poll(100, TimeUnit.MILLISECONDS)
            }
            var result: Bundle? = null
            var attempt = 0
            while (result == null) {
                target.send(Message.obtain(null, 1).apply { data = payload; replyTo = reply })
                while (result == null) {
                    check(!cancelled() && !dead.get() && SystemClock.elapsedRealtime() < end) { "Processing cancelled or timed out" }
                    result = replies.poll(100, TimeUnit.MILLISECONDS)
                }
                // Another request still owns the worker: wait and resend instead of failing the photo.
                if (result.getBoolean("busy")) {
                    closeReply(result); result = null
                    val wait = busyBackoffMs(attempt++)
                    val until = SystemClock.elapsedRealtime() + wait
                    while (SystemClock.elapsedRealtime() < until) {
                        check(!cancelled() && !dead.get() && SystemClock.elapsedRealtime() < end) { "Processing cancelled or timed out" }
                        Thread.sleep(50)
                    }
                }
            }
            stats?.apply {
                putBoolean("colorMatchApplied", result.getBoolean("colorMatchApplied"))
                putBoolean("edgeAwareDemosaic", result.getBoolean("edgeAwareDemosaic"))
                putBoolean("frequencyDenoise", result.getBoolean("frequencyDenoise"))
                putBoolean("steadyPhotoFinish", result.getBoolean("steadyPhotoFinish"))
                putBoolean("rawShadingApplied", result.getBoolean("rawShadingApplied"))
                for (key in listOf("colorErrorBefore", "colorErrorAfter", "colorInliers")) putDouble(key, result.getDouble(key))
                for (key in listOf("processMs", "nativeMs", "decodeMs", "modelMs", "inferenceMs", "depthInferenceMs", "neuralDenoiseMs", "encodeMs")) putLong(key, result.getLong(key))
                putInt("cameraPermission", result.getInt("cameraPermission", -999))
                putDouble("effectiveFrames", result.getDouble("effectiveFrames", 0.0))
                putDouble("jpegRejectedFrames", result.getDouble("jpegRejectedFrames", 0.0))
                putDouble("jpegMaximumShift", result.getDouble("jpegMaximumShift", 0.0))
                putLong("jpegStagePssKiB", result.getLong("jpegStagePssKiB", 0))
            }
            val editData = result.getParcelable<SharedMemory>("portraitData")
            try { result.getParcelable<SharedMemory>("jpeg").use { memory ->
                check(result.getString("error") == null) { result.getString("error") ?: "Processing failed" }
                check(memory != null && memory.size in 1..20 * 1024 * 1024)
                editData?.let { require(it.size in 40..PortraitData.MAX_BYTES); portraitData?.invoke(it) }
                val mapped = memory.mapReadOnly()
                try { return ByteArray(mapped.remaining()).also(mapped::get) }
                finally { SharedMemory.unmap(mapped) }
            } } finally { editData?.close() }
        } finally {
            accepting.set(false)
            context.unbindService(connection)
            while (true) closeReply(replies.poll() ?: break)
        }
    }
}
