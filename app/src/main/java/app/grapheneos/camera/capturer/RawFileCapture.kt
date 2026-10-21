package app.grapheneos.camera.capturer

import android.graphics.ImageDecoder
import android.graphics.ImageFormat
import android.os.Handler
import android.os.Looper
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidxc.camera.core.impl.utils.Exif
import app.grapheneos.camera.clearExif
import app.grapheneos.camera.fixExif
import app.grapheneos.camera.util.ImageResizer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX writes standard DNGs to private temporary storage. Never send RAW through JPEG extraction.
 * A split JPEG + RAW capture takes the JPEG first, rebinds ImageCapture for RAW alone, takes the
 * RAW and rebinds back. Both files keep one capture time, as a paired capture does.
 */
@androidx.annotation.OptIn(markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class])
internal class RawFileCapture(
    private val owner: ImageCapturer,
    private val format: PhotoOutputFormat,
    private val metadata: ImageCapture.Metadata,
    internal val split: Boolean = false,
) : ImageCapture.OnImageSavedCallback {
    private val activity = owner.mActivity
    private val context = activity.applicationContext
    private val config = owner.camConfig
    private val storageLocation = config.storageLocation
    private val removeExif = config.removeExifAfterCapture
    private val time = Date()
    private val directory = Files.createTempDirectory(context.cacheDir.toPath(), "capture-").toFile()
    private val raw = File(directory, "photo.dng")
    private val jpeg = File(directory, "photo.jpg")
    private val ended = AtomicBoolean()
    private val received = hashSetOf<Int>()
    private val main = Handler(Looper.getMainLooper())
    private val timeout = Runnable { fail(ImageCaptureException(ImageCapture.ERROR_CAPTURE_FAILED, "RAW capture timed out", null)) }

    private val rawOutput = ImageCapture.OutputFileOptions.Builder(raw).setMetadata(metadata).build()
    private val jpegOutput = ImageCapture.OutputFileOptions.Builder(jpeg).setMetadata(metadata).build()

    /** The JPEG's own capture settings, repeated for the RAW so both show the same exposure and focus. */
    private var jpegSettings: android.hardware.camera2.TotalCaptureResult? = null
    /** CameraX keeps camera-level options across rebinds: what to put back after the RAW. */
    private var restoreControl: androidx.camera.camera2.interop.Camera2CameraControl? = null
    private var restoreOptions: androidx.camera.camera2.interop.CaptureRequestOptions? = null
    private var cursor: Pair<Long, Long>? = null

    fun start(capture: ImageCapture) {
        main.postDelayed(timeout, 60_000)
        if (split) cursor = config.captureResults.captureCursor()
        try {
            if (split) {
                check(format == PhotoOutputFormat.JPEG_RAW && capture.outputFormat == ImageCapture.OUTPUT_FORMAT_JPEG)
                capture.takePicture(jpegOutput, executor, this)
            } else if (format == PhotoOutputFormat.JPEG_RAW) {
                capture.takePicture(rawOutput, jpegOutput, executor, this)
            } else capture.takePicture(rawOutput, executor, this)
        } catch (error: Exception) {
            fail(ImageCaptureException(ImageCapture.ERROR_CAPTURE_FAILED, "Cannot start RAW capture", error))
        }
    }

    /** Main thread: the JPEG is saved, so take the RAW from an ImageCapture bound for RAW alone. */
    private fun takeSplitRaw() {
        if (ended.get() || !owner.isCurrent(this)) return
        try {
            // Read the JPEG's settings before the rebind starts a new result generation.
            jpegSettings = cursor?.let { config.captureResults.stillFrames(it).lastOrNull() }
            config.splitRawPhase = true
            config.startCamera(forced = true, preserveCapture = true)
            val capture = checkNotNull(config.imageCapture)
            check(capture.outputFormat == ImageCapture.OUTPUT_FORMAT_RAW)
            matchJpegSettings()
            capture.takePicture(rawOutput, executor, this)
        } catch (error: Exception) {
            fail(ImageCaptureException(ImageCapture.ERROR_CAPTURE_FAILED, "Cannot start RAW capture", error))
        }
    }

    /**
     * A fresh session meters again. Give the RAW the JPEG's focus, and its exposure and
     * sensitivity when the sensor can be set to them manually. Gains beyond the manual range
     * are left to auto exposure, which settles on the same values for an unchanged scene.
     */
    @androidx.annotation.OptIn(markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class])
    private fun matchJpegSettings() {
        val result = jpegSettings ?: return
        val camera = config.camera ?: return
        val info = androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.cameraInfo)
        val options = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
        val exposure = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
        val iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
        val range = info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        if (exposure != null && iso != null && range != null && iso in range.lower..range.upper) {
            options.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
                .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, iso)
                .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_FRAME_DURATION, exposure)
        }
        result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE)?.let { focus ->
            options.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.LENS_FOCUS_DISTANCE, focus)
        }
        try {
            val control = androidx.camera.camera2.interop.Camera2CameraControl.from(camera.cameraControl)
            restoreOptions = control.getCaptureRequestOptions(); restoreControl = control
            control.addCaptureRequestOptions(options.build()).get(1, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) { }
    }

    /** Main thread: give the camera back its own options, before any rebind. */
    @androidx.annotation.OptIn(markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class])
    private fun restoreCameraOptions() {
        val control = restoreControl ?: return
        val options = restoreOptions ?: return
        restoreControl = null; restoreOptions = null
        try { control.setCaptureRequestOptions(options).get(1, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { }
    }

    /** Back to the JPEG binding after the RAW phase, whatever the outcome. */
    private fun endSplit() {
        if (!split) return
        context.mainExecutor.execute {
            // Always undo the RAW's manual exposure and focus, even if the lens changed meanwhile.
            restoreCameraOptions()
            if (!config.splitRawPhase) return@execute
            config.splitRawPhase = false
            // Rebind only the RAW-phase binding: a lens or mode change already rebound the camera.
            if (config.imageCapture?.outputFormat == ImageCapture.OUTPUT_FORMAT_RAW)
                config.startCamera(forced = true, preserveCapture = true)
        }
    }

    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
        if (ended.get()) { directory.deleteRecursively(); return }
        val expected = if (format == PhotoOutputFormat.JPEG_RAW) setOf(ImageFormat.RAW_SENSOR, ImageFormat.JPEG)
            else setOf(ImageFormat.RAW_SENSOR)
        if (result.imageFormat !in expected || !received.add(result.imageFormat)) {
            fail(ImageCaptureException(ImageCapture.ERROR_CAPTURE_FAILED, "Unexpected RAW capture output", null))
            return
        }
        if (received != expected) {
            if (split && received == setOf(ImageFormat.JPEG)) context.mainExecutor.execute { takeSplitRaw() }
            return
        }
        if (!ended.compareAndSet(false, true)) return
        main.removeCallbacks(timeout)
        endSplit()
        try {
            if (metadata.isReversedHorizontal) app.grapheneos.camera.util.DngOrientation.mirrorHorizontally(raw)
            val photos = arrayListOf(PhotoFileStore.Photo(raw, ".dng", "image/x-adobe-dng"))
            if (format == PhotoOutputFormat.JPEG_RAW) {
                val bytes = jpeg.readBytes()
                val exif = Exif.createFromInputStream(ByteArrayInputStream(bytes))
                if (removeExif) exif.exifInterface.clearExif() else exif.exifInterface.fixExif(time)
                metadata.location?.let { exif.attachLocation(it) }
                val rewritten = ByteArrayOutputStream(bytes.size + 64 * 1024)
                exif.exifInterface.saveAttributes(ByteArrayInputStream(bytes), rewritten)
                jpeg.writeBytes(rewritten.toByteArray())
                photos.add(PhotoFileStore.Photo(jpeg, ".jpg", "image/jpeg"))
            }
            val thumbnailFile = if (jpeg.isFile) jpeg else raw
            val thumbnail = try {
                val resizer = ImageResizer(activity.imagePreview.width.coerceAtLeast(1), activity.imagePreview.height.coerceAtLeast(1))
                if (thumbnailFile == raw) app.grapheneos.camera.util.DngPreview.decode(context, raw, resizer)
                else ImageDecoder.decodeBitmap(ImageDecoder.createSource(jpeg), resizer)
            }
                catch (_: Exception) { null }
            val items = PhotoFileStore.save(context, photos, storageLocation, time)
            context.mainExecutor.execute {
                if (owner.isCurrent(this)) owner.onCaptureSuccess()
                items.forEach(owner::onImageSaverSuccess)
                if (config.lastCapturedItem == items.last()) {
                    if (thumbnail != null) owner.onThumbnailGenerated(thumbnail,items.last())
                    else activity.updateThumbnail()
                }
            }
        } catch (error: Exception) {
            context.mainExecutor.execute {
                if (owner.isCurrent(this))
                    owner.onCaptureError(ImageCaptureException(ImageCapture.ERROR_FILE_IO, "Cannot save RAW capture", error))
            }
        } finally { directory.deleteRecursively() }
    }

    override fun onError(exception: ImageCaptureException) { fail(exception) }

    private fun fail(error: ImageCaptureException) {
        if (!ended.compareAndSet(false, true)) return
        main.removeCallbacks(timeout)
        endSplit()
        executor.execute { directory.deleteRecursively() }
        context.mainExecutor.execute { if (owner.isCurrent(this)) owner.onCaptureError(error) }
    }

    fun cancel() {
        if (!ended.compareAndSet(false, true)) return
        main.removeCallbacks(timeout)
        endSplit()
        // Late CameraX callbacks also remove the directory after their file handles close.
        executor.execute { directory.deleteRecursively() }
    }

    companion object { private val executor = Executors.newSingleThreadExecutor() }
}
