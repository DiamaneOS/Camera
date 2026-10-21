package app.grapheneos.camera.capturer

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.os.*
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** No camera, files, network, or inherited app permissions. Inputs are read-only shared memory. */
class CameraProcessingService : Service() {
    private val executor = Executors.newSingleThreadExecutor { work ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            work.run()
        }, "photo-processing")
    }
    /** Set when this instance is unbound: a request it accepted but has not started is dropped. */
    private val stopped = AtomicBoolean()
    @Volatile private var task: RawNativeCore.Task? = null
    @Volatile private var rgbTask: RgbDenoiser? = null
    @Volatile private var depthTask: PortraitDepthEstimator? = null
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        val input = message.data
        input.classLoader = SharedMemory::class.java.classLoader
        val reply = message.replyTo
        val admitted = admission.tryEnter() && try {
            executor.execute { process(input, reply) }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // This instance is already destroyed.
            admission.leave()
            false
        }
        if (!admitted) {
            @Suppress("DEPRECATION")
            val extra = input.getParcelableArrayList<SharedMemory>("frames")
            extra?.forEach(SharedMemory::close)
            @Suppress("DEPRECATION")
            val jpeg = input.getParcelable<SharedMemory>("jpeg")
            jpeg?.close()
            input.getParcelable<SharedMemory>("depthModel")?.close()
            input.getParcelable<SharedMemory>("rawShading")?.close()
            try { reply?.send(Message.obtain(null, 1).apply { data = Bundle().apply { putString("error", "Processor busy"); putBoolean("busy", true) } }) }
            catch (_: RemoteException) { }
        }
        true
    })

    @Suppress("DEPRECATION")
    private fun process(input: Bundle, reply: Messenger?) {
        val mappings = arrayListOf<ByteBuffer>()
        val memories = arrayListOf<SharedMemory>()
        val output = Bundle()
        var encodedMemory: SharedMemory? = null
        var portraitMemory: SharedMemory? = null
        var bitmap: Bitmap? = null
        try {
            check(!stopped.get()) { "Processor stopped" }
            val rawShading = input.getParcelable<SharedMemory>("rawShading")
            rawShading?.let { memories.add(it); require(input.getInt("mode") == 1 && it.size in 24..RawShadingData.MAX_BYTES) }
            val start = SystemClock.elapsedRealtime()
            RawNativeCore.Task().use { native ->
                task = native
                // onUnbind sets stopped before it cancels task: one of the two sees the other.
                if (stopped.get()) native.cancel()
                when (input.getInt("mode")) {
                    1 -> {
                        val frames = input.getParcelableArrayList<SharedMemory>("frames") ?: error("Missing RAW frames")
                        memories.addAll(frames)
                        require(frames.size in 2..8 && frames.sumOf { it.size.toLong() } <= 240L * 1024 * 1024)
                        frames.forEach { mappings.add(it.mapReadOnly()) }
                        val width = input.getInt("width"); val height = input.getInt("height")
                        require(width >= 8 && height >= 8 && width.toLong() * height <= 16_000_000)
                        val stats = DoubleArray(6)
                        val aspect = input.getFloat("aspect", 4f / 3)
                        require(aspect.isFinite() && aspect in 1f..2f)
                        val edgeDemosaic=input.getInt("edgeAwareDemosaic",0);require(edgeDemosaic in 0..1)
                        output.putBoolean("edgeAwareDemosaic",edgeDemosaic==1)
                        val frequencyDenoise=input.getInt("frequencyDenoise",0);require(frequencyDenoise in 0..1)
                        output.putBoolean("frequencyDenoise",frequencyDenoise==1)
                        val steadyPhotoFinish=input.getInt("steadyPhotoFinish",0);require(steadyPhotoFinish in 0..1)
                        output.putBoolean("steadyPhotoFinish",steadyPhotoFinish==1)
                        val nativeStarted = SystemClock.elapsedRealtime()
                        val rawMappings = mappings.toTypedArray()
                        val shadingMapping = rawShading?.mapReadOnly()?.also { mappings.add(it) }
                        val pixels = native.run(rawMappings, input.getIntArray("strides"),
                            input.getFloatArray("calibration"), width, height, input.getInt("cfa"),
                            input.getInt("reference"), input.getFloat("exposure"), input.getFloatArray("color"), stats, input.getFloat("targetLuminance", 0f), input.getFloat("zoom", 1f), aspect, input.getFloatArray("toneTargets"), input.getIntArray("colorReference"), input.getInt("referenceRotation"), input.getIntArray("periodicGrid"), edgeDemosaic==1, frequencyDenoise==1, steadyPhotoFinish==1, shadingMapping)
                        output.putBoolean("rawShadingApplied", shadingMapping != null)
                        output.putLong("nativeMs", SystemClock.elapsedRealtime() - nativeStarted)
                        val full = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                        val zoom = input.getFloat("zoom", 1f)
                        require(zoom.isFinite() && zoom in 1f..100f)
                        val baseWidth = minOf(width, (height * aspect).toInt())
                        val baseHeight = minOf(height, (width / aspect).toInt())
                        val cropWidth = ((baseWidth / zoom).toInt() / 2 * 2).coerceAtLeast(8)
                        val cropHeight = ((baseHeight / zoom).toInt() / 2 * 2).coerceAtLeast(8)
                        bitmap = Bitmap.createBitmap(full, (width - cropWidth) / 2, (height - cropHeight) / 2, cropWidth, cropHeight)
                        if (bitmap !== full) full.recycle()
                        output.putDouble("effectiveFrames", stats[0])
                        output.putBoolean("colorMatchApplied", stats[2] != 0.0)
                        output.putDouble("colorErrorBefore", stats[3])
                        output.putDouble("colorErrorAfter", stats[4])
                        output.putDouble("colorInliers", stats[5])
                    }
                    2 -> {
                        val jpeg = input.getParcelable<SharedMemory>("jpeg") ?: error("Missing Portrait input")
                        memories.add(jpeg)
                        require(jpeg.size in 1..20 * 1024 * 1024)
                        val mapping = jpeg.mapReadOnly(); mappings.add(mapping)
                        val decodeStarted = SystemClock.elapsedRealtime()
                        bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(mapping)) { decoder, info, _ ->
                            require(info.size.width.toLong() * info.size.height <= 16_000_000)
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                        }
                        output.putLong("decodeMs", SystemClock.elapsedRealtime() - decodeStarted)
                        val image = bitmap!!
                        val modelStarted = SystemClock.elapsedRealtime()
                        val modelBytes = assets.open("portrait_subject_v1.tflite").use { it.readBytes() }
                        check(modelBytes.size == 6227884)
                        val expected = "e24338a717c1b7ad8d159666677ef400babb7f33b8ad60c4d96db4ecf694cd25"
                        check(MessageDigest.getInstance("SHA-256").digest(modelBytes).joinToString("") { "%02x".format(it.toInt() and 255) } == expected)
                        val model = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder()).apply { put(modelBytes); rewind() }
                        output.putLong("modelMs", SystemClock.elapsedRealtime() - modelStarted)
                        val mask = PortraitSegmenter(model).use { it.segment(image, input.getFloat("x"), input.getFloat("y")) }
                        output.putLong("inferenceMs", mask.inferenceMs)
                        val foreground = mask.confidence.count { it > .5f }.toDouble() / mask.confidence.size
                        require(foreground in .005..0.95) { "Select a subject with a visible background" }
                        val depth = if (input.getBoolean("depthEnabled")) {
                            assets.openFd("portrait_depth_v2_small.tflite").use { asset ->
                                require(asset.length == 98_928_904L)
                                FileInputStream(asset.fileDescriptor).channel.use { channel ->
                                    val mapping = channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.length)
                                    PortraitDepthEstimator(mapping).use {
                                        depthTask = it
                                        try { check(!native.isCancelled); it.estimate(image) }
                                        finally { depthTask = null }
                                    }
                                }
                            }
                        } else null
                        output.putLong("depthInferenceMs", depth?.inferenceMs ?: 0)
                        val retained = PortraitData(image.width, image.height, mask.width, mask.height,
                            depth?.width ?: 0, depth?.height ?: 0, input.getFloat("x"), input.getFloat("y"),
                            input.getInt("blur", 8), mask.confidence, depth?.values ?: floatArrayOf()).encode()
                        val editData = SharedMemory.create("portrait-edit-data", retained.remaining())
                        portraitMemory = editData
                        val editMapping = editData.mapReadWrite()
                        try { editMapping.put(retained) } finally { SharedMemory.unmap(editMapping) }
                        check(editData.setProtect(OsConstants.PROT_READ))
                        output.putParcelable("portraitData", editData)
                        val source = IntArray(image.width * image.height)
                        image.getPixels(source, 0, image.width, 0, 0, image.width, image.height)
                        val nativeStarted = SystemClock.elapsedRealtime()
                        val pixels = native.renderPortrait(source, image.width, image.height, mask.confidence,
                            mask.width, mask.height, input.getInt("blur", 8), depth?.values, depth?.width ?: 0, depth?.height ?: 0,
                            input.getFloat("x"), input.getFloat("y"))
                        output.putLong("nativeMs", SystemClock.elapsedRealtime() - nativeStarted)
                        val rendered = Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
                        image.recycle(); bitmap = rendered
                    }
                    3 -> {
                        val jpeg=input.getParcelable<SharedMemory>("jpeg") ?: error("Missing JPEG input")
                        memories.add(jpeg);require(jpeg.size in 1..20*1024*1024)
                        val mapping=jpeg.mapReadOnly();mappings.add(mapping)
                        val image=ImageDecoder.decodeBitmap(ImageDecoder.createSource(mapping)) {decoder,info,_ ->
                            require(info.size.width>=8 && info.size.height>=8 && info.size.width.toLong()*info.size.height<=16_000_000)
                            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                        }
                        val pixels=IntArray(image.width*image.height);image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
                        val nativeStarted=SystemClock.elapsedRealtime()
                        var clean=native.denoiseJpeg(pixels,image.width,image.height)
                        if(app.grapheneos.camera.BuildConfig.DEBUG && app.grapheneos.camera.BuildConfig.CAMERA_NEURAL_DENOISE_TEST && input.getBoolean("neuralDenoiseTest")) {
                            val modelBytes=assets.open("experimental_rgb_denoise.tflite").use {it.readBytes()}
                            RgbDenoiser(modelBytes).use { model ->
                                rgbTask=model
                                try {
                                    if(input.getBoolean("neuralForegroundTest"))android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
                                    val at=SystemClock.elapsedRealtime()
                                    clean=model.denoise(clean,image.width,image.height)
                                    output.putLong("neuralDenoiseMs",SystemClock.elapsedRealtime()-at)
                                } finally {rgbTask=null;android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)}
                            }
                        }
                        output.putLong("nativeMs",SystemClock.elapsedRealtime()-nativeStarted)
                        bitmap=Bitmap.createBitmap(clean,image.width,image.height,Bitmap.Config.ARGB_8888);image.recycle()
                    }
                    4 -> {
                        check(IspJpegNight.enabled(this@CameraProcessingService))
                        val frames = input.getParcelableArrayList<SharedMemory>("frames") ?: error("Missing JPEG frames")
                        memories.addAll(frames)
                        require(frames.size in 2..8 && frames.sumOf { it.size.toLong() } <= 240L * 1024 * 1024)
                        val width = input.getInt("width"); val height = input.getInt("height")
                        require(width in 8..8192 && height in 8..8192 && width.toLong()*height <= 16_000_000)
                        val source = IntArray(width*height)
                        var decodeMs = 0L; var nativeMs = 0L
                        var stagePssKiB = Debug.getPss()
                        native.beginJpegMerge(width, height).use { merger ->
                            for (frame in frames) {
                                check(!native.isCancelled)
                                require(frame.size in 4..32*1024*1024)
                                val mapping = frame.mapReadOnly(); mappings.add(mapping)
                                require((mapping.get(0).toInt() and 255) == 255 && (mapping.get(1).toInt() and 255) == 216)
                                val at = SystemClock.elapsedRealtime()
                                val image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(mapping)) { decoder, info, _ ->
                                    require(info.size.width == width && info.size.height == height)
                                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                                    decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                                }
                                try { image.getPixels(source, 0, width, 0, 0, width, height) }
                                finally { image.recycle() }
                                stagePssKiB = maxOf(stagePssKiB, Debug.getPss())
                                decodeMs += SystemClock.elapsedRealtime()-at
                                val startedNative = SystemClock.elapsedRealtime()
                                merger.add(source)
                                nativeMs += SystemClock.elapsedRealtime()-startedNative
                                stagePssKiB = maxOf(stagePssKiB, Debug.getPss())
                            }
                            val measured = DoubleArray(4)
                            val startedNative = SystemClock.elapsedRealtime()
                            val pixels = merger.finish(measured)
                            nativeMs += SystemClock.elapsedRealtime()-startedNative
                            bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                            stagePssKiB = maxOf(stagePssKiB, Debug.getPss())
                            output.putDouble("effectiveFrames", measured[0])
                            output.putDouble("jpegRejectedFrames", measured[1])
                            output.putDouble("jpegMaximumShift", measured[2])
                        }
                        output.putLong("decodeMs", decodeMs); output.putLong("nativeMs", nativeMs)
                        output.putLong("jpegStagePssKiB", stagePssKiB)
                    }
                    else -> error("Unknown processing mode")
                }
                val encodeStarted = SystemClock.elapsedRealtime()
                val encoded = ByteArrayOutputStream()
                check(bitmap!!.compress(Bitmap.CompressFormat.JPEG, input.getInt("quality", 95).coerceIn(1, 100), encoded))
                val bytes = encoded.toByteArray()
                require(bytes.size in 1..20 * 1024 * 1024)
                val memory = SharedMemory.create("camera-result", bytes.size)
                encodedMemory = memory
                val mapping = memory.mapReadWrite()
                try { mapping.put(bytes) } finally { SharedMemory.unmap(mapping) }
                check(memory.setProtect(OsConstants.PROT_READ))
                output.putParcelable("jpeg", memory)
                output.putLong("encodeMs", SystemClock.elapsedRealtime() - encodeStarted)
                output.putInt("cameraPermission", checkSelfPermission(android.Manifest.permission.CAMERA))
                output.putLong("processMs", SystemClock.elapsedRealtime() - start)
            }
        } catch (error: Throwable) {
            output.putString("error", error.javaClass.simpleName + ": " + error.message)
        } finally {
            task = null
            // Release admission before publishing completion. A reply can trigger
            // the next request before this executor returns; it will queue behind
            // our remaining output-handle cleanup on the same serial executor.
            try {
                bitmap?.recycle()
                mappings.forEach(SharedMemory::unmap)
                memories.forEach(SharedMemory::close)
            } finally { admission.leave() }
        }
        try { reply?.send(Message.obtain(null, 1).apply { data = output }) }
        catch (_: RemoteException) { }
        finally { encodedMemory?.close(); portraitMemory?.close() }
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onUnbind(intent: Intent): Boolean { stop(); return false }
    // The running task ends at its next cancellation check and then leaves the admission. Its
    // thread is not interrupted, and a queued request still runs far enough to leave it.
    override fun onDestroy() { stop(); executor.shutdown(); super.onDestroy() }
    private fun stop() { stopped.set(true); task?.cancel(); depthTask?.cancel(); rgbTask?.cancel() }

    companion object {
        /**
         * One task per process, across service instances. After a cancel and unbind the old
         * instance's native task can still run while a new instance is bound for the next photo.
         * The new one answers busy until it ends, and the client backs off and resends.
         */
        internal val admission = ProcessingAdmission()
    }
}
