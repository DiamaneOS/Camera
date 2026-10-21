package app.grapheneos.camera.capturer

import android.graphics.Bitmap
import android.location.Location
import android.os.Bundle
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import app.grapheneos.camera.CameraMode
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns acquisition only. The durable queue owns processing after the shutter becomes available. */
internal class ProcessedCapture(private val owner: ImageCapturer, metadata: ImageCapture.Metadata,
                                private val reservation: PhotoProcessingQueue.Reservation,
                                private val frames: Int, private val jpegBurst: Boolean) {
    private val cancelled = AtomicBoolean()
    @Volatile private var transferred = false
    @Volatile private var queuedId: String? = null
    @Volatile private var jpegExposure: FastJpegExposure? = null
    val isProcessing: Boolean get() = transferred
    private val context = owner.mActivity.applicationContext
    private val config = owner.camConfig
    private val portrait = config.currentMode == CameraMode.PORTRAIT
    private val enhanced = config.softwarePhoto
    private val fastJpeg = config.softwareFastPhoto
    private val nightJpeg = jpegBurst && config.softwareJpegNight
    private val cropAspect = if (config.aspectRatio == androidx.camera.core.AspectRatio.RATIO_16_9) 16f / 9 else 4f / 3
    private val capturedAt = System.currentTimeMillis()
    private val dateString = ProcessedPhotoSpec.date(capturedAt)
    private val quality = config.photoQuality
    private val storage = config.storageLocation
    private val removeExif = config.removeExifAfterCapture
    private val location = metadata.location?.let(::Location)
    private val horizontal = metadata.isReversedHorizontal
    private val vertical = metadata.isReversedVertical
    private val x = config.portraitSubjectX
    private val y = config.portraitSubjectY
    private val front = config.lensFacing == CameraSelector.LENS_FACING_FRONT
    private val saveAsPreviewed = config.saveImageAsPreviewed
    private val blur = config.portraitBlur

    private fun previewGuide(): RawPreviewGuide {
        val future = CompletableFuture<RawPreviewGuide>()
        context.mainExecutor.execute {
            try {
                check(!cancelled.get() && owner.isCurrent(this))
                val rotation = config.camera!!.cameraInfo.getSensorRotationDegrees(config.imageCapture!!.targetRotation)
                val guide = samplePreview(rotation)
                if (!future.complete(guide)) guide.thumbnail?.recycle()
            } catch (error: Exception) { future.completeExceptionally(error) }
        }
        try { return future.get(2, TimeUnit.SECONDS) }
        catch (error: Exception) { future.cancel(false); throw error }
    }

    private fun samplePreview(rotation: Int): RawPreviewGuide {
        val preview = owner.mActivity.previewView.bitmap ?: return RawPreviewGuide(0f, null, null, rotation, null)
        var small: Bitmap? = null
        var thumbnail: Bitmap? = null
        try {
            small = Bitmap.createScaledBitmap(preview, 96, 96, true)
            val pixels = IntArray(96 * 96)
            small.getPixels(pixels, 0, 96, 0, 0, 96, 96)
            PhotoGeometry.unmirrorReference(pixels, 96, 96, front)
            val previewMean = pixels.sumOf { p -> (p shr 16 and 255) + 2 * (p shr 8 and 255) + (p and 255) }.toFloat() / (pixels.size * 4 * 255)
            val gain = if (enhanced) 1f else NightPreviewTonePolicy.gain(previewMean)
            if (gain != 1f) for (index in pixels.indices) {
                pixels[index]=NightPreviewTonePolicy.lift(pixels[index],gain)
            }
            if(app.grapheneos.camera.BuildConfig.DEBUG && File(context.filesDir,"processing-test/keep-color-reference").delete()) {
                File(File(context.filesDir,"processing-test").apply { mkdirs() },"night-color-reference.json").writeText(org.json.JSONObject()
                    .put("pixels",org.json.JSONArray(pixels.toList())).put("rotation",rotation).put("previewMean",previewMean).toString())
            }
            val levels = IntArray(pixels.size) { index ->
                val p = pixels[index]
                (p shr 16 and 255) + 2 * (p shr 8 and 255) + (p and 255)
            }
            val mean = levels.sumOf { it.toLong() }.toFloat() / (pixels.size * 4 * 255)
            levels.sort()
            val targets = floatArrayOf(levels[(levels.size - 1) / 100] / 1020f,
                levels[(levels.size - 1) / 2] / 1020f, levels[(levels.size - 1) * 99 / 100] / 1020f)
            val scale = minOf(1f, 720f / maxOf(preview.width, preview.height))
            thumbnail = Bitmap.createScaledBitmap(preview, (preview.width * scale).toInt().coerceAtLeast(1),
                (preview.height * scale).toInt().coerceAtLeast(1), true)
            if (thumbnail === preview) thumbnail = preview.copy(Bitmap.Config.ARGB_8888, false)
            if (front && !saveAsPreviewed) {
                val previous = thumbnail!!
                thumbnail = Bitmap.createBitmap(previous, 0, 0, previous.width, previous.height,
                    android.graphics.Matrix().apply { setScale(-1f, 1f) }, true)
                if (thumbnail !== previous) previous.recycle()
            }
            return RawPreviewGuide(mean.coerceIn(.08f, .75f), targets, pixels.takeIf {NightPreviewTonePolicy.reliableColor(previewMean)}, rotation, thumbnail)
        } catch (_: Exception) {
            thumbnail?.recycle()
            return RawPreviewGuide(0f, null, null, rotation, null)
        } finally {
            if (small !== preview) small?.recycle()
            preview.recycle()
        }
    }

    private fun spec(rotation: Int, alreadyMirrored: Boolean) = ProcessedPhotoSpec(capturedAt, dateString, quality,
        storage, removeExif, rotation, horizontal && !alreadyMirrored, vertical && !alreadyMirrored,
        location, portrait)

    fun start() {
        if (jpegBurst) executor.execute {
            try {
                jpegExposure = FastJpegExposure.prepare(owner, cancelled,
                    enhancedIspEvaluation = !nightJpeg, nightIspEvaluation = nightJpeg) { jpegExposure = it }
                val metadata = ImageCapture.Metadata().apply {
                    location = this@ProcessedCapture.location
                    isReversedHorizontal = horizontal; isReversedVertical = vertical
                }
                IspJpegBurst.capture(owner, frames, quality, metadata, cancelled, { owner.isCurrent(this) },
                    { frame, count -> owner.showProcessingProgress(this, frame, count) }).use { acquired ->
                    jpegExposure?.close()
                    jpegExposure?.profile?.let { exposure ->
                        exposure.keys().forEach { key -> acquired.profile.put(key, exposure.get(key)) }
                    }
                    handoff(PhotoProcessingQueue.enqueue(reservation, spec(0, true), acquired.payload,
                        null, acquired.preview, acquired.profile, cancelled::get))
                }
            } catch (error: Exception) { jpegExposure?.close(); fail(error) }
        } else if (portrait || fastJpeg) {
            val directory = Files.createTempDirectory(context.cacheDir.toPath(), "portrait-").toFile()
            val file = File(directory, "source.jpg")
            val metadata = ImageCapture.Metadata().apply {
                location = this@ProcessedCapture.location
                isReversedHorizontal = horizontal
                isReversedVertical = vertical
            }
            fun jpegFailed(error: Exception) {
                executor.execute { jpegExposure?.close(); directory.deleteRecursively(); fail(error) }
            }
            val acquire = {
                try {
                    check(!cancelled.get() && owner.isCurrent(this))
                    val output = ImageCapture.OutputFileOptions.Builder(file).setMetadata(metadata).build()
                    config.imageCapture!!.takePicture(output, executor, object : ImageCapture.OnImageSavedCallback {
                        override fun onError(exception: ImageCaptureException) { jpegFailed(exception) }
                        override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                            try {
                                jpegExposure?.close()
                                check(!cancelled.get())
                                val payload = Bundle().apply {
                                    putInt("mode", if(portrait) 2 else 3); putFloat("x", PhotoGeometry.savedSubjectX(x, front, saveAsPreviewed)); putFloat("y", y)
                                    putInt("blur", blur); putInt("quality", quality)
                                }
                                handoff(PhotoProcessingQueue.enqueue(reservation, spec(0, true), payload,
                                    file, null, jpegExposure?.profile, cancelled::get))
                            } catch (error: Exception) { fail(error) }
                            finally { directory.deleteRecursively() }
                        }
                    })
                } catch (error: Exception) { jpegFailed(error) }
            }
            if (fastJpeg) executor.execute {
                try {
                    jpegExposure = FastJpegExposure.prepare(owner, cancelled) { jpegExposure = it }
                    context.mainExecutor.execute { acquire() }
                } catch (error: Exception) { jpegFailed(error) }
            } else acquire()
        } else executor.execute {
            try {
                NightCapture(owner, cancelled, ::previewGuide,
                    { frame, count -> owner.showProcessingProgress(this, frame, count) }, frames, !enhanced, cropAspect).capture().use { acquired ->
                    handoff(PhotoProcessingQueue.enqueue(reservation, spec(acquired.guide.rotation, false), acquired.payload,
                        null, acquired.guide.thumbnail, acquired.profile, cancelled::get))
                }
            } catch (error: Exception) { fail(error) }
        }
    }

    private fun handoff(item: app.grapheneos.camera.CapturedItem) {
        queuedId = item.processingId!!
        transferred = true
        context.mainExecutor.execute {
            if (cancelled.get() || !owner.isCurrent(this)) { PhotoProcessingQueue.abandon(item.processingId!!); return@execute }
            owner.onProcessedAcquisitionComplete(this, item)
        }
    }

    private fun fail(error: Exception) {
        if (!transferred) PhotoProcessingQueue.release(reservation)
        context.mainExecutor.execute {
            if (cancelled.get() || !owner.isCurrent(this)) return@execute
            owner.onCaptureError(if (error is ImageCaptureException) error else
                ImageCaptureException(ImageCapture.ERROR_CAPTURE_FAILED, "Photo acquisition failed", error))
        }
    }

    fun cancel() {
        cancelled.set(true)
        jpegExposure?.restoreAsync()
        queuedId?.let(PhotoProcessingQueue::abandon)
    }
    companion object { private val executor = Executors.newSingleThreadExecutor() }
}
