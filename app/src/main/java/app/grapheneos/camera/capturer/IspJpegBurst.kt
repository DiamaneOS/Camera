package app.grapheneos.camera.capturer

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.os.Bundle
import android.os.SharedMemory
import android.os.SystemClock
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLongArray

/** Private evaluation acquisition. CameraX keeps its framing, AF and output geometry. */
internal class IspJpegBurst private constructor(val payload: Bundle, val preview: Bitmap,
    val profile: JSONObject, private val memories: List<SharedMemory>) : AutoCloseable {
    override fun close() { memories.forEach(SharedMemory::close); preview.recycle() }

    companion object {
        private val direct = Executor { it.run() }
        fun capture(owner: ImageCapturer, count: Int, quality: Int, metadata: ImageCapture.Metadata,
                    cancelled: AtomicBoolean, current: () -> Boolean,
                    progress: (Int, Int) -> Unit): IspJpegBurst {
            check(app.grapheneos.camera.BuildConfig.DEBUG && app.grapheneos.camera.BuildConfig.CAMERA_JPEG_BURST_EVALUATION)
            require(count in 2..4)
            val context = owner.mActivity.applicationContext
            val capture = checkNotNull(owner.camConfig.imageCapture)
            val directory = Files.createTempDirectory(context.cacheDir.toPath(), "isp-jpeg-").toFile()
            val memories = arrayListOf<SharedMemory>()
            var preview: Bitmap? = null
            var transferred = false
            val started = SystemClock.elapsedRealtime()
            val metadataCursor = owner.camConfig.captureResults.debugCaptureCursor()
            val deadline = started + 20_000
            var width = 0; var height = 0; var total = 0L
            val files = List(count) { File(directory, "frame-$it.jpg") }
            val results = List(count) { CompletableFuture<Unit>() }
            val delivered = AtomicLongArray(count)
            fun lateCleanup(index: Int) { files[index].delete(); directory.delete() }
            try {
                // CameraX owns a bounded queue of at most four still requests.
                // Submit together so JPEG saving/EXIF work can overlap the next
                // sensor capture, then consume each completed file in order.
                context.mainExecutor.execute {
                    repeat(count) { index ->
                        val saved = results[index]
                        try {
                            check(!cancelled.get() && current() && owner.camConfig.imageCapture === capture)
                            val options = ImageCapture.OutputFileOptions.Builder(files[index]).setMetadata(metadata).build()
                            capture.takePicture(options, direct, object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                    delivered.set(index, SystemClock.elapsedRealtime()-started)
                                    if (!saved.complete(Unit)) lateCleanup(index)
                                }
                                override fun onError(error: ImageCaptureException) {
                                    if (!saved.completeExceptionally(error)) lateCleanup(index)
                                }
                            })
                        } catch (error: Exception) { saved.completeExceptionally(error) }
                    }
                }
                repeat(count) { index ->
                    val file = files[index]
                    val saved = results[index]
                    while (true) {
                        check(!cancelled.get() && current() && SystemClock.elapsedRealtime() < deadline)
                        try { saved.get(50, TimeUnit.MILLISECONDS); break }
                        catch (_: TimeoutException) { }
                    }
                    check(!cancelled.get() && current())
                    require(file.length() in 4..32L*1024*1024)
                    total += file.length(); require(total <= 240L*1024*1024)
                    if (index == 0) {
                        // ImageDecoder reports the logical dimensions after CameraX's
                        // EXIF rotation/mirroring; the worker uses the same decoder.
                        preview = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                            width = info.size.width; height = info.size.height
                            require(width in 8..8192 && height in 8..8192 && width.toLong()*height <= 16_000_000)
                            val scale = minOf(1.0, 720.0/maxOf(width, height))
                            decoder.setTargetSize((width*scale).toInt().coerceAtLeast(1), (height*scale).toInt().coerceAtLeast(1))
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                        }
                    }
                    memories.add(ProcessingClient.fileMemory("captured-jpeg", file))
                    file.delete()
                    context.mainExecutor.execute { if (!cancelled.get() && current()) progress(index+1, count) }
                }
                val payload = Bundle().apply {
                    putInt("mode", 4); putInt("width", width); putInt("height", height); putInt("quality", quality)
                    putParcelableArrayList("frames", ArrayList(memories))
                }
                val profile = JSONObject().put("ispJpegBurst", true).put("frames", count)
                    .put("cameraXCaptureMode", capture.captureMode)
                capture.resolutionInfo?.let { info ->
                    profile.put("cameraXBufferWidth", info.resolution.width).put("cameraXBufferHeight", info.resolution.height)
                        .put("cameraXCropRect", org.json.JSONArray(listOf(info.cropRect.left, info.cropRect.top, info.cropRect.right, info.cropRect.bottom)))
                }
                val sensorFrames = owner.camConfig.captureResults.debugStillFrames(metadataCursor)
                val firstTimestamp = sensorFrames.firstOrNull()?.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: 0L
                profile.put("sensorFrames", org.json.JSONArray(sensorFrames.map { result ->
                    org.json.JSONObject().apply {
                        put("sensorElapsedNs", (result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: firstTimestamp)-firstTimestamp)
                        put("exposureNs", result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME))
                        put("iso", result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY))
                        put("boost", result.get(android.hardware.camera2.CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST))
                        put("noiseReductionMode", result.get(android.hardware.camera2.CaptureResult.NOISE_REDUCTION_MODE))
                        put("shadingMode", result.get(android.hardware.camera2.CaptureResult.SHADING_MODE))
                        put("afState", result.get(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE))
                        result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE)?.takeIf { it.isFinite() }?.let { put("focus", it) }
                    }
                }))
                profile
                    .put("width", width).put("height", height).put("acquiredMs", SystemClock.elapsedRealtime()-started)
                    .put("frameDeliveryMs", org.json.JSONArray((0 until count).map { delivered.get(it) }))
                return IspJpegBurst(payload, checkNotNull(preview), profile, memories).also { transferred = true }
            } finally {
                results.forEach { it.cancel(false) }
                directory.deleteRecursively()
                if (!transferred) { memories.forEach(SharedMemory::close); preview?.recycle() }
            }
        }
    }
}
