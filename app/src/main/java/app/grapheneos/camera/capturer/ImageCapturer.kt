package app.grapheneos.camera.capturer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import app.grapheneos.camera.CameraMode
import app.grapheneos.camera.App
import app.grapheneos.camera.CapturedItem
import app.grapheneos.camera.R
import app.grapheneos.camera.ui.activities.MainActivity
import app.grapheneos.camera.ui.activities.SecureMainActivity
import app.grapheneos.camera.ui.showIgnoringShortEdgeMode
import app.grapheneos.camera.util.printStackTraceToString
import com.google.android.material.dialog.MaterialAlertDialogBuilder

private const val imageFileFormat = ".jpg"

class ImageCapturer(val mActivity: MainActivity) {
    val camConfig = mActivity.camConfig

    val isTakingPicture: Boolean
        get() = currentImageSaver != null || rawFileCapture != null || processedCapture != null

    /** Captures the UI must not interrupt: processed photos and split JPEG + RAW pairs. */
    val isProcessingCapture: Boolean get() = processedCapture != null || rawFileCapture?.split == true

    var captureZoom: Float? = null
        private set

    private var currentImageSaver : ImageSaver? = null
    private var rawFileCapture: RawFileCapture? = null
    @Volatile private var processedCapture: ProcessedCapture? = null

    internal fun isCurrent(capture: ProcessedCapture) = processedCapture === capture
    internal fun isCurrent(capture: RawFileCapture) = rawFileCapture === capture

    fun cancelAcquiringCapture() {
        if (processedCapture?.isProcessing != true) cancelPendingCaptureRequest()
    }

    internal fun showProcessingProgress(capture: ProcessedCapture, frame: Int, count: Int) {
        if (isCurrent(capture)) mActivity.showProcessingStatus(frame, count)
    }

    internal fun showProcessingEstimate(count: Int,exposureNs: Long) {
        if(processedCapture!=null) mActivity.setCaptureEstimate(count,exposureNs)
    }

    private fun fadeCaptureButton() {
        captureZoom = camConfig.displayZoom
        mActivity.captureButton.isEnabled = false
        if (isProcessingCapture) {
            mActivity.settingsIcon.isEnabled = false
            mActivity.exposureBar.hidePanel()
            mActivity.zoomBar.hidePanel()
        }

        val animation: Animation = AlphaAnimation(mActivity.captureButton.alpha, 0.6f)
        animation.duration = 200
        animation.interpolator = LinearInterpolator()
        animation.fillAfter = true

        mActivity.captureButton.startAnimation(animation)
    }

    private fun unfadeCaptureButton() {
        mActivity.captureButton.isEnabled = true
        mActivity.settingsIcon.isEnabled = !camConfig.directBurstActive

        val animation: Animation = AlphaAnimation(mActivity.captureButton.alpha, 1f)
        animation.duration = 200
        animation.interpolator = LinearInterpolator()
        animation.fillAfter = true

        mActivity.captureButton.startAnimation(animation)
    }

    @SuppressLint("RestrictedApi")
    fun takePicture() {
        if (camConfig.camera == null) {
            return
        }

        if (!camConfig.canTakePicture) {
            mActivity.showMessage(R.string.unsupported_taking_picture_while_recording)
            return
        }

        if (isTakingPicture) {
            return
        }

        if (camConfig.usesProcessingCapture &&
            !mActivity.ensureProcessingNotificationPermission()) return

        val imageMetadata = ImageCapture.Metadata()
        imageMetadata.isReversedHorizontal = camConfig.lensFacing == CameraSelector.LENS_FACING_FRONT
                && camConfig.saveImageAsPreviewed

        if (camConfig.requireLocation) {
            val location = (mActivity.applicationContext as App).getLocation()
            if (location == null) {
                mActivity.showMessage(R.string.location_unavailable)
            } else {
                imageMetadata.location = location
            }
        }

        val preview = mActivity.imagePreview

        val imageCapture = camConfig.imageCapture!!

        if (camConfig.usesProcessingCapture) {
            // Snapshot routing and frame count once, before reserving or handing
            // ownership to the asynchronous acquisition.
            val jpegBurst = camConfig.softwareJpegBurst
            val frames = if (jpegBurst) {
                if (camConfig.softwareJpegNight || camConfig.prioritizeCaptureQuality) 4 else 2
            } else camConfig.processingFrameCount
            val bytes = if (jpegBurst) frames*32L*1024*1024 + 1024*1024 else
                if (camConfig.softwarePortrait || camConfig.softwareFastPhoto) 21L * 1024 * 1024 else
                minOf(240L * 1024 * 1024, frames * 32L * 1024 * 1024) + 1024 * 1024 + RawShadingData.MAX_BYTES
            val reservation = PhotoProcessingQueue.reserve(bytes)
            if (reservation == null) { mActivity.showMessage(when {
                PhotoProcessingQueue.isRecovering -> R.string.camera_preparing
                PhotoProcessingQueue.lacksStorage(bytes) -> R.string.photo_storage_unavailable
                else -> R.string.photo_queue_full
            }); return }
            val capture = ProcessedCapture(this, imageMetadata, reservation, frames, jpegBurst)
            processedCapture = capture
            fadeCaptureButton()
            mActivity.showProcessingStatus(0, frames)
            try { capture.start() }
            catch (error: Exception) {
                PhotoProcessingQueue.release(reservation)
                onCaptureError(ImageCaptureException(ImageCapture.ERROR_CAPTURE_FAILED, "Photo acquisition failed", error))
            }
            return
        }

        if (camConfig.activePhotoOutputFormat != PhotoOutputFormat.JPEG) {
            val capture = RawFileCapture(this, camConfig.activePhotoOutputFormat, imageMetadata, camConfig.splitsRawJpeg)
            rawFileCapture = capture
            fadeCaptureButton()
            capture.start(imageCapture)
            return
        }

        val imageSaver = ImageSaver(
            this,
            mActivity.applicationContext,
            imageCapture.jpegQuality,
            camConfig.storageLocation,
            imageFileFormat,
            imageMetadata,
            camConfig.removeExifAfterCapture,
            targetThumbnailWidth = preview.width,
            targetThumbnailHeight = preview.height,
        )

        currentImageSaver = imageSaver

        imageCapture.takePicture(ImageSaver.imageCaptureCallbackExecutor, imageSaver)
        fadeCaptureButton()
    }

    fun cancelPendingCaptureRequest() {
        if (isTakingPicture) {
            currentImageSaver?.cancelCaptureRequest()
            rawFileCapture?.cancel()
            processedCapture?.cancel()
            processedCapture = null
            mActivity.clearProcessingStatus()
            rawFileCapture = null

            unfadeCaptureButton()
            currentImageSaver = null
            captureZoom = null
            mActivity.refreshCaptureHints()
        }
    }

    fun onCaptureSuccess() {
        currentImageSaver = null
        rawFileCapture = null
        processedCapture = null
        captureZoom = null
        unfadeCaptureButton()
        mActivity.clearProcessingStatus()
        mActivity.refreshCaptureHints()
        mActivity.updateLensControls()

        camConfig.mPlayer.playShutterSound()
        camConfig.snapPreview()

        mActivity.previewLoader.visibility = View.VISIBLE
        if (camConfig.selfIlluminate) {

            val animation: Animation = AlphaAnimation(0.8f, 0f)
            animation.duration = 200
            animation.interpolator = LinearInterpolator()
            animation.fillAfter = true

            mActivity.mainOverlay.setImageResource(android.R.color.white)

            animation.setAnimationListener(
                object : Animation.AnimationListener {
                    override fun onAnimationStart(p0: Animation?) {
                        mActivity.mainOverlay.visibility = View.VISIBLE
                    }

                    override fun onAnimationEnd(p0: Animation?) {
                        mActivity.mainOverlay.visibility = View.INVISIBLE
                        mActivity.mainOverlay.setImageResource(android.R.color.transparent)
                    }

                    override fun onAnimationRepeat(p0: Animation?) {}

                }
            )

            mActivity.mainOverlay.startAnimation(animation)
        }
    }

    internal fun onProcessedAcquisitionComplete(capture: ProcessedCapture, item: CapturedItem) {
        if (!isCurrent(capture)) return
        onCaptureSuccess()
        onImageSaverSuccess(item)
        mActivity.previewLoader.visibility = View.GONE
        mActivity.updateThumbnail()
    }

    fun onCaptureError(exception: ImageCaptureException) {
        Log.e(TAG, "onCaptureError", exception)

        unfadeCaptureButton()
        currentImageSaver = null
        rawFileCapture = null
        processedCapture = null
        captureZoom = null
        mActivity.clearProcessingStatus()
        mActivity.refreshCaptureHints()
        mActivity.previewLoader.visibility = View.GONE

        if (mActivity.isStarted) {
            val msg = mActivity.getString(R.string.unable_to_capture_image_verbose, exception.imageCaptureError)
            showErrorDialog(msg, exception)
        }
    }

    fun onImageSaverSuccess(item: CapturedItem) {
        camConfig.updateLastCapturedItem(item)

        if (mActivity is SecureMainActivity) {
            mActivity.capturedItems.add(item)
        }
    }

    fun onStorageLocationNotFound() {
        camConfig.onStorageLocationNotFound()
    }

    fun onImageSaverError(exception: ImageSaverException, skipErrorDialog: Boolean) {
        Log.e(TAG, "onImageSaverError", exception)
        mActivity.previewLoader.visibility = View.GONE

        if (!mActivity.isStarted) {
            val channelId = "image_saver_error"
            val channel = NotificationChannel(channelId, mActivity.getString(R.string.unable_to_save_image),
                NotificationManager.IMPORTANCE_HIGH)

            val notif = Notification.Builder(mActivity, channelId).apply {
                setSmallIcon(R.drawable.info)
                setContentTitle(mActivity.getString(R.string.unable_to_save_image))
            }.build()

            mActivity.getSystemService(NotificationManager::class.java).let {
                it.createNotificationChannel(channel)
                it.notify(1, notif)
            }
            return
        }

        if (skipErrorDialog) {
            mActivity.showMessage(R.string.unable_to_save_image)
        } else {
            val msg = mActivity.getString(R.string.unable_to_save_image_verbose, exception.place.name)
            showErrorDialog(msg, exception)
        }
    }

    private fun showErrorDialog(message: String, exception: Throwable) {
        val ctx = mActivity

        MaterialAlertDialogBuilder(ctx).apply {
            setMessage(message)
            setPositiveButton(R.string.show_details) { _, _ ->
                val pkgName = ctx.packageName
                val pkgVersion = ctx.packageManager.getPackageInfo(pkgName, 0).longVersionCode
                val text = "osVersion: ${Build.FINGERPRINT}" +
                        "\npackage: $pkgName:$pkgVersion" +
                        "\n\n${exception.printStackTraceToString()}"

                MaterialAlertDialogBuilder(ctx).apply {
                    setItems(text.lines().toTypedArray(), null)
                    setNeutralButton(R.string.copy_to_clipboard) { _, _ ->
                        val clipData = ClipData.newPlainText(exception.javaClass.name, text)
                        val cm = mActivity.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(clipData)
                        ctx.showMessage(R.string.copied_text_to_clipboard)
                    }
                    showIgnoringShortEdgeMode()
                }
            }
            showIgnoringShortEdgeMode()
        }
    }

    fun onThumbnailGenerated(thumbnail: Bitmap,item: CapturedItem) {
        if(camConfig.lastCapturedItem?.uri!=item.uri) {thumbnail.recycle();return}
        mActivity.isThumbnailLoaded=true
        mActivity.previewLoader.visibility = View.GONE
        mActivity.imagePreview.setImageBitmap(thumbnail)
    }

    companion object {
        private const val TAG = "ImageCapturer"
    }
}
