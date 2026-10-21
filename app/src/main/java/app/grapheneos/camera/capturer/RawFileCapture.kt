package app.grapheneos.camera.capturer

import android.graphics.ImageDecoder
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
    private val main = Handler(Looper.getMainLooper())
    private val sequence = RawCaptureSequence(CameraPort(), format == PhotoOutputFormat.JPEG_RAW, split)
    private val timeout = Runnable { sequence.timedOut() }

    private val rawOutput = ImageCapture.OutputFileOptions.Builder(raw).setMetadata(metadata).build()
    private val jpegOutput = ImageCapture.OutputFileOptions.Builder(jpeg).setMetadata(metadata).build()

    /** The JPEG's own capture settings, repeated for the RAW so both show the same exposure and focus. */
    private var jpegSettings: android.hardware.camera2.TotalCaptureResult? = null
    /** CameraX keeps camera-level options across rebinds: what to put back after the RAW. */
    private var restoreControl: androidx.camera.camera2.interop.Camera2CameraControl? = null
    private var restoreOptions: androidx.camera.camera2.interop.CaptureRequestOptions? = null
    private var cursor: Pair<Long, Long>? = null
    private var first: ImageCapture? = null
    private var rawCapture: ImageCapture? = null

    fun start(capture: ImageCapture) {
        first = capture
        if (split) cursor = config.captureResults.captureCursor()
        sequence.start()
    }

    override fun onImageSaved(result: ImageCapture.OutputFileResults) = sequence.saved(result.imageFormat)
    override fun onError(exception: ImageCaptureException) = sequence.failed(exception)
    fun cancel() = sequence.cancel()

    private inner class CameraPort : RawCaptureSequence.Port {
        override fun scheduleTimeout() { main.postDelayed(timeout, 60_000) }
        override fun cancelTimeout() { main.removeCallbacks(timeout) }

        override fun takeFirst() {
            val capture = checkNotNull(first)
            if (split) {
                check(format == PhotoOutputFormat.JPEG_RAW && capture.outputFormat == ImageCapture.OUTPUT_FORMAT_JPEG)
                capture.takePicture(jpegOutput, executor, this@RawFileCapture)
            } else if (format == PhotoOutputFormat.JPEG_RAW) {
                capture.takePicture(rawOutput, jpegOutput, executor, this@RawFileCapture)
            } else capture.takePicture(rawOutput, executor, this@RawFileCapture)
        }

        override fun onMain(action: () -> Unit) = context.mainExecutor.execute(action)
        override fun isCurrent() = owner.isCurrent(this@RawFileCapture)

        override fun rememberJpegSettings() {
            jpegSettings = cursor?.let { config.captureResults.stillFrames(it).lastOrNull() }
        }

        override fun bindRaw() {
            config.splitRawPhase = true
            config.startCamera(forced = true, preserveCapture = true)
            val capture = checkNotNull(config.imageCapture)
            check(capture.outputFormat == ImageCapture.OUTPUT_FORMAT_RAW)
            rawCapture = capture
        }

        /**
         * A fresh session meters again. Give the RAW the JPEG's focus, and its exposure and
         * sensitivity when the sensor can be set to them manually. Gains beyond the manual range
         * are left to auto exposure, which settles on the same values for an unchanged scene.
         */
        override fun matchJpegSettings() {
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

        override fun takeRaw() { checkNotNull(rawCapture).takePicture(rawOutput, executor, this@RawFileCapture) }

        override fun restoreOptions() {
            val control = restoreControl ?: return
            val options = restoreOptions ?: return
            restoreControl = null; restoreOptions = null
            try { control.setCaptureRequestOptions(options).get(1, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { }
        }

        override fun leaveRawPhase(): Boolean {
            if (!config.splitRawPhase) return false
            config.splitRawPhase = false
            return true
        }

        override fun rebindJpeg() {
            // Rebind only the RAW-phase binding: a lens or mode change already rebound the camera.
            if (config.imageCapture?.outputFormat == ImageCapture.OUTPUT_FORMAT_RAW)
                config.startCamera(forced = true, preserveCapture = true)
        }

        override fun discard() { executor.execute { directory.deleteRecursively() } }

        override fun reportError(error: Exception) {
            owner.onCaptureError(error as? ImageCaptureException
                ?: ImageCaptureException(ImageCapture.ERROR_CAPTURE_FAILED, error.message ?: "RAW capture failed", error.cause))
        }

        override fun save() {
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
                    if (owner.isCurrent(this@RawFileCapture)) owner.onCaptureSuccess()
                    items.forEach(owner::onImageSaverSuccess)
                    if (config.lastCapturedItem == items.last()) {
                        if (thumbnail != null) owner.onThumbnailGenerated(thumbnail,items.last())
                        else activity.updateThumbnail()
                    }
                }
            } catch (error: Exception) {
                context.mainExecutor.execute {
                    if (owner.isCurrent(this@RawFileCapture))
                        owner.onCaptureError(ImageCaptureException(ImageCapture.ERROR_FILE_IO, "Cannot save RAW capture", error))
                }
            } finally { directory.deleteRecursively() }
        }
    }

    companion object { private val executor = Executors.newSingleThreadExecutor() }
}
