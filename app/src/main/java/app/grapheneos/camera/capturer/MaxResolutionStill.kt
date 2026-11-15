package app.grapheneos.camera.capturer

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraState
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * DiamaneOS: one photo at the sensor's full resolution (8192 x 6144 on a 50 MP sensor). The camera offers
 * that size only in the maximum-resolution sensor pixel mode, which CameraX does not use. So the photo takes
 * the camera over the way [DirectRawBurst] does: CameraX lets go, a Camera2 session meters on a small preview
 * in the default mode, one still is taken in the maximum-resolution mode, and CameraX binds again.
 */
@SuppressLint("MissingPermission", "UnsafeOptInUsageError")
internal class MaxResolutionStill(private val owner: ImageCapturer, private val id: String, private val size: Size) {
    private val context = owner.mActivity.applicationContext
    private val config = owner.camConfig

    /** Returns the camera's JPEG of the full sensor, not rotated (the caller passes the rotation on). */
    fun capture(jpegQuality: Int): ByteArray {
        val oldCamera = config.camera ?: error("Camera unavailable")
        val manager = context.getSystemService(CameraManager::class.java)
        val characteristics = manager.getCameraCharacteristics(id)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: error("No stream map")
        val previews = map.getOutputSizes(ImageFormat.YUV_420_888).filter { it.width <= 1280 }
        val previewSize = previews.filter { abs(it.width.toDouble() / it.height - size.width.toDouble() / size.height) < .02 }
            .maxByOrNull { it.width * it.height } ?: previews.maxByOrNull { it.width * it.height } ?: error("No preview size")
        val thread = HandlerThread("max-resolution-still").apply { start() }
        val handler = Handler(thread.looper)
        val closed = AtomicBoolean()
        val lease = DirectCameraLease<CameraDevice>()
        val recoveryHandler = Handler(Looper.getMainLooper())
        val closure = BurstClosure(lease, object : BurstClosure.Env {
            override fun now() = SystemClock.elapsedRealtime()
            override fun previousCameraClosed() = oldCamera.cameraInfo.cameraState.value?.type == CameraState.Type.CLOSED
            override fun ownsPreviousBinding() = config.ownsDirectBurst(lease) && config.camera === oldCamera
            override fun unbindPrevious() { config.cameraProvider?.unbindAll(); config.camera = null }
            override fun runOffMain(action: () -> Unit) = closureExecutor.execute(action)
            override fun joinThread(): Boolean { thread.quitSafely(); thread.join(1000); return !thread.isAlive }
            override fun post(delayMs: Long, action: () -> Unit) { recoveryHandler.postDelayed(action, delayMs) }
            override fun finish() {
                if (config.finishDirectBurst(lease) && owner.mActivity.isStarted &&
                    !owner.mActivity.isDestroyed && !owner.mActivity.isFinishing)
                    config.startCamera(true, preserveCapture = true)
            }
            override fun activityDestroyed() = owner.mActivity.isDestroyed
            override fun activityVisible() = owner.mActivity.isStarted && !owner.mActivity.isDestroyed
            override fun notifySlowRecovery() = owner.mActivity.showMessage(app.grapheneos.camera.R.string.camera_recovery_unavailable)
        })
        val jpegs = ArrayBlockingQueue<Any>(1)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var preview: ImageReader? = null
        var jpeg: ImageReader? = null
        var result: ByteArray? = null
        var cameraXId = id
        try {
            onMain {
                cameraXId = Camera2CameraInfo.from(oldCamera.cameraInfo).cameraId
                check(lease.acquire { config.beginDirectBurst(lease) }) { "Camera is being released" }
                owner.mActivity.updateLastFrame()
                config.closePreviewProcessing()
                config.cameraProvider!!.unbindAll()
                config.camera = null
            }
            val releaseDeadline = SystemClock.elapsedRealtime() + 5000
            while (oldCamera.cameraInfo.cameraState.value?.type != CameraState.Type.CLOSED) {
                check(SystemClock.elapsedRealtime() < releaseDeadline) { "CameraX did not release its camera" }
                Thread.sleep(20)
            }
            // CameraX keeps its last camera device open after reporting it closed. The camera service lets the
            // same app open that camera again (the newer open wins) but refuses a camera that shares a sensor
            // with it, as on a device whose main sensor is part of its logical back camera. So take it over and close it.
            if (cameraXId != id) {
                val released = ArrayBlockingQueue<Any>(1)
                lease.beginOpen()
                try {
                    manager.openCamera(cameraXId, object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) { lease.arrived(camera); camera.close() }
                        override fun onDisconnected(camera: CameraDevice) { lease.arrived(camera); camera.close() }
                        override fun onError(camera: CameraDevice, error: Int) { lease.arrived(camera); camera.close() }
                        override fun onClosed(camera: CameraDevice) {
                            lease.closed(camera); released.offer(Unit)
                            if (closed.get()) recoveryHandler.post(closure::recover)
                        }
                    }, handler)
                } catch (error: Exception) { lease.openFailed(); throw error }
                checkNotNull(released.poll(5, TimeUnit.SECONDS)) { "CameraX's camera was not released" }
            }
            val opened = ArrayBlockingQueue<Any>(1)
            lease.beginOpen()
            try {
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (lease.arrived(camera) || !opened.offer(camera)) camera.close()
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        lease.arrived(camera); camera.close()
                        val error = IllegalStateException("Camera disconnected")
                        opened.offer(error); jpegs.offer(error)
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        lease.arrived(camera); camera.close()
                        val failure = IllegalStateException("Camera error $error")
                        opened.offer(failure); jpegs.offer(failure)
                    }
                    override fun onClosed(camera: CameraDevice) {
                        lease.closed(camera)
                        if (closed.get()) recoveryHandler.post(closure::recover)
                    }
                }, handler)
            } catch (error: Exception) { lease.openFailed(); throw error }
            val openedResult = opened.poll(5, TimeUnit.SECONDS)
            device = openedResult as? CameraDevice ?: throw (openedResult as? Exception ?: IllegalStateException("Camera open timed out"))
            preview = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ reader -> try { reader.acquireLatestImage()?.close() } catch (_: IllegalStateException) { } }, handler)
            }
            jpeg = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1).apply {
                setOnImageAvailableListener({ reader ->
                    try {
                        reader.acquireNextImage()?.use { image ->
                            val buffer = image.planes[0].buffer
                            jpegs.offer(ByteArray(buffer.remaining()).also { buffer.get(it) })
                        }
                    } catch (error: IllegalStateException) { jpegs.offer(error) }
                }, handler)
            }
            val still = OutputConfiguration(jpeg.surface).apply {
                addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            }
            val configured = ArrayBlockingQueue<Any>(1)
            device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(preview.surface), still), { command -> handler.post(command) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(value: CameraCaptureSession) {
                        if (closed.get() || !configured.offer(value)) value.close()
                    }
                    override fun onConfigureFailed(value: CameraCaptureSession) {
                        configured.offer(IllegalStateException("The camera refused the full-resolution session"))
                    }
                }))
            val configuredResult = configured.poll(5, TimeUnit.SECONDS)
            session = configuredResult as? CameraCaptureSession
                ?: throw (configuredResult as? Exception ?: IllegalStateException("Session configuration timed out"))

            // Meter and focus on the preview in the default mode, as the viewfinder did.
            val settled = CountDownLatch(1)
            var frames = 0
            val metering = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }.build()
            session.setRepeatingRequest(metering, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    frames++
                    val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                    val af = result.get(CaptureResult.CONTROL_AF_STATE)
                    val exposed = ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                    val focused = af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED || af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        af == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED
                    if (frames >= 4 && exposed && focused) settled.countDown()
                }
            }, handler)
            // A scene that never settles (dark, moving) still gets its photo after 2 s.
            settled.await(2, TimeUnit.SECONDS)
            session.stopRepeating()

            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jpeg.surface)
                set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_LOCK, true)
                set(CaptureRequest.CONTROL_AWB_LOCK, true)
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                set(CaptureRequest.JPEG_QUALITY, jpegQuality.coerceIn(1, 100).toByte())
                set(CaptureRequest.JPEG_ORIENTATION, 0)
            }.build()
            session.capture(request, null, handler)
            val delivered = jpegs.poll(10, TimeUnit.SECONDS)
            result = delivered as? ByteArray ?: throw (delivered as? Exception ?: IllegalStateException("The full-resolution photo timed out"))
            return result
        } finally {
            closed.set(true)
            closure.begin()
            try { session?.abortCaptures() } catch (_: Exception) { }
            try { session?.close() } catch (_: Exception) { }
            lease.requestClose().forEach { try { it.close() } catch (_: Exception) { } }
            val readersClosed = CountDownLatch(1)
            val closeReaders = Runnable {
                try { jpeg?.setOnImageAvailableListener(null, null); jpeg?.close() } catch (_: Exception) { }
                try { preview?.setOnImageAvailableListener(null, null); preview?.close() } catch (_: Exception) { }
                readersClosed.countDown()
            }
            if (!handler.post(closeReaders)) closeReaders.run()
            if (!readersClosed.await(2, TimeUnit.SECONDS)) Log.w(TAG, "Readers closed late")
            lease.resourcesClosed(); recoveryHandler.post(closure::recover)
            if (!closure.recovered.await(3, TimeUnit.SECONDS)) {
                // A finished photo stays valid: a slow rebind must not discard it.
                if (result != null) Log.w(TAG, "Camera rebind is slow, closure recovery remains active")
                else throw IllegalStateException("Camera release timed out, closure recovery remains active")
            }
        }
    }

    private fun onMain(action: () -> Unit) {
        val task = java.util.concurrent.FutureTask { action() }
        context.mainExecutor.execute(task)
        try { task.get(8, TimeUnit.SECONDS) } catch (error: Exception) { task.cancel(false); throw error }
    }

    companion object {
        private const val TAG = "MaxResolutionStill"
        private val closureExecutor = Executors.newSingleThreadExecutor()
        val worker = Executors.newSingleThreadExecutor()

        /** The full-resolution JPEG size of this camera, if it has an ultra-high-resolution sensor. */
        fun sensorSize(characteristics: CameraCharacteristics): Size? {
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return null
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR !in capabilities) return null
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION) ?: return null
            // Sizes slower than 20 fps (the full 50 MP one) are only in the high-resolution list.
            return (map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty() + map.getHighResolutionOutputSizes(ImageFormat.JPEG)?.toList().orEmpty())
                .maxByOrNull { it.width.toLong() * it.height }
        }
    }
}
