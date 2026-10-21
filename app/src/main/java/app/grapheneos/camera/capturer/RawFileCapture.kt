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

/** CameraX writes standard DNGs to private temporary storage. Never send RAW through JPEG extraction. */
internal class RawFileCapture(
    private val owner: ImageCapturer,
    private val format: PhotoOutputFormat,
    private val metadata: ImageCapture.Metadata,
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

    fun start(capture: ImageCapture) {
        main.postDelayed(timeout, 60_000)
        val rawOutput = ImageCapture.OutputFileOptions.Builder(raw).setMetadata(metadata).build()
        try {
            if (format == PhotoOutputFormat.JPEG_RAW) {
                val jpegOutput = ImageCapture.OutputFileOptions.Builder(jpeg).setMetadata(metadata).build()
                capture.takePicture(rawOutput, jpegOutput, executor, this)
            } else capture.takePicture(rawOutput, executor, this)
        } catch (error: Exception) {
            fail(ImageCaptureException(ImageCapture.ERROR_CAPTURE_FAILED, "Cannot start RAW capture", error))
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
        if (received != expected) return
        if (!ended.compareAndSet(false, true)) return
        main.removeCallbacks(timeout)
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
        executor.execute { directory.deleteRecursively() }
        context.mainExecutor.execute { if (owner.isCurrent(this)) owner.onCaptureError(error) }
    }

    fun cancel() {
        if (!ended.compareAndSet(false, true)) return
        main.removeCallbacks(timeout)
        // Late CameraX callbacks also remove the directory after their file handles close.
        executor.execute { directory.deleteRecursively() }
    }

    companion object { private val executor = Executors.newSingleThreadExecutor() }
}
