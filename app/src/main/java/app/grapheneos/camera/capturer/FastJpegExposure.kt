package app.grapheneos.camera.capturer

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CaptureRequest as Q
import android.hardware.camera2.CaptureResult as R
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.ImageCapture
import app.grapheneos.camera.R as Res
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Capture-executor lease; the same camera's automatic options are restored before handoff. */
@SuppressLint("UnsafeOptInUsageError")
internal class FastJpegExposure private constructor(private val control: Camera2CameraControl,
    private val original: CaptureRequestOptions, val profile: JSONObject, val restoreGain: Float,
    private val recover: () -> Unit) : AutoCloseable {
    private var applied = false
    private var restoreRequested = false
    private var restoreFuture: Future<*>? = null
    private val recoveryRequested = AtomicBoolean()
    private fun failedRestore() {
        profile.put("restoredOptions", false)
        if (recoveryRequested.compareAndSet(false, true)) recover()
    }
    /** Enqueue on the cancellation caller before a later bind can change this camera's options. */
    @Synchronized fun restoreAsync(): Future<*>? {
        if (!applied || restoreRequested) return restoreFuture
        restoreRequested = true
        try { restoreFuture = control.setCaptureRequestOptions(original) }
        catch (_: Exception) { failedRestore() }
        return restoreFuture
    }
    override fun close() {
        val future = restoreAsync() ?: return
        try { future.get(3, TimeUnit.SECONDS); profile.put("restoredOptions", true) }
        catch (_: Exception) { failedRestore() }
    }
    companion object {
        private val refused = ConcurrentHashMap.newKeySet<String>()
        fun prepare(owner: ImageCapturer, cancelled: AtomicBoolean, enhancedIspEvaluation: Boolean = false,
                    nightIspEvaluation: Boolean = false, nightHeadroom: Double = 1.0,
                    own: (FastJpegExposure) -> Unit): FastJpegExposure? {
            val config = owner.camConfig
            if (nightIspEvaluation && !config.softwareJpegNight) return null
            if ((!nightIspEvaluation &&
                    (!owner.mActivity.resources.getBoolean(Res.bool.config_camera_ultra_wide_jpeg_exposure_compensation) ||
                        (!config.softwareFastPhoto && !enhancedIspEvaluation))) ||
                config.flashMode != ImageCapture.FLASH_MODE_OFF || config.isTorchOn) return null
            val camera = config.camera ?: return null
            val id = Camera2CameraInfo.from(camera.cameraInfo).cameraId
            val refusalKey = id + if (nightIspEvaluation) ":night-jpeg" else ":photo-jpeg"
            if (refusalKey in refused) return null
            val initial = config.captureResults.latestFor(id) ?: return null
            val context = owner.mActivity.applicationContext
            val chars = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(id)
            if (chars.get(C.REQUEST_AVAILABLE_CAPABILITIES)?.contains(C.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) != true ||
                chars.get(C.CONTROL_AE_AVAILABLE_MODES)?.contains(Q.CONTROL_AE_MODE_OFF) != true) return null
            val exposureRange = chars.get(C.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: return null
            val isoRange = chars.get(C.SENSOR_INFO_SENSITIVITY_RANGE) ?: return null
            val boostRange = chars.get(C.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE) ?: return null
            val oldExposure = initial.get(R.SENSOR_EXPOSURE_TIME) ?: return null
            val oldIso = initial.get(R.SENSOR_SENSITIVITY) ?: return null
            val oldBoost = initial.get(R.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: return null
            val choice = if (nightIspEvaluation) {
                // No steady-scene assumption: use only the device's existing
                // handheld bound, with the same conservative generic fallback.
                val handheld = context.resources.getInteger(Res.integer.config_camera_night_handheld_max_exposure_ms)
                    .takeIf { it in 1..1000 }?.toLong()?.times(1_000_000) ?: 66_666_667L
                val limit = context.resources.getInteger(Res.integer.config_camera_night_isp_jpeg_max_exposure_ms)
                    .takeIf { it in 1..1000 }?.toLong()?.times(1_000_000)?.coerceAtMost(handheld) ?: handheld
                JpegExposurePolicy.chooseNight(oldExposure, oldIso, oldBoost, exposureRange.lower,
                    exposureRange.upper, isoRange.lower, isoRange.upper, boostRange.lower, boostRange.upper, limit,
                    nightHeadroom)
            } else JpegExposurePolicy.choose(oldExposure, oldIso, oldBoost, exposureRange.lower,
                exposureRange.upper, isoRange.lower, isoRange.upper, boostRange.lower, boostRange.upper)
            if (choice == null) return null
            val frameDuration = maxOf(choice.exposureNs, initial.get(R.SENSOR_FRAME_DURATION) ?: choice.exposureNs)
            if (frameDuration > (chars.get(C.SENSOR_INFO_MAX_FRAME_DURATION) ?: frameDuration)) return null
            val control = Camera2CameraControl.from(camera.cameraControl)
            val profile = JSONObject().put("singleJpegCompensation", true).put("meteredExposureNs", oldExposure)
                .put("meteredIso", oldIso).put("meteredBoost", oldBoost).put("requestedExposureNs", choice.exposureNs)
                .put("requestedIso", choice.iso).put("requestedBoost", choice.boost).put("restoreGain", choice.restoreGain)
            if (app.grapheneos.camera.BuildConfig.DEBUG &&
                app.grapheneos.camera.BuildConfig.CAMERA_JPEG_BURST_EVALUATION && nightIspEvaluation)
                profile.put("nightJpegEvaluation", true)
            val lease = FastJpegExposure(control, control.getCaptureRequestOptions(), profile, choice.restoreGain) {
                owner.mActivity.runOnUiThread {
                    if (config.camera === camera) config.startCamera(forced = true, preserveCapture = true)
                }
            }
            val started = SystemClock.elapsedRealtime()
            try {
                check(!cancelled.get())
                val options = CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(Q.CONTROL_AE_MODE, Q.CONTROL_AE_MODE_OFF)
                    .setCaptureRequestOption(Q.SENSOR_EXPOSURE_TIME, choice.exposureNs)
                    .setCaptureRequestOption(Q.SENSOR_FRAME_DURATION, frameDuration)
                    .setCaptureRequestOption(Q.SENSOR_SENSITIVITY, choice.iso)
                    .setCaptureRequestOption(Q.CONTROL_POST_RAW_SENSITIVITY_BOOST, choice.boost)
                    .setCaptureRequestOption(Q.CONTROL_AWB_LOCK, true)
                    .setCaptureRequestOption(Q.CONTROL_ENABLE_ZSL, false)
                val applied = synchronized(lease) {
                    check(!cancelled.get())
                    own(lease)
                    check(!cancelled.get())
                    lease.applied = true
                    control.addCaptureRequestOptions(options.build())
                }
                applied.get(3, TimeUnit.SECONDS)
                val deadline = SystemClock.elapsedRealtime() + 1500
                while (SystemClock.elapsedRealtime() < deadline) {
                    check(!cancelled.get() && config.camera === camera)
                    config.captureResults.latestFor(id)?.let { result ->
                        val exposure = result.get(R.SENSOR_EXPOSURE_TIME) ?: 0
                        val iso = result.get(R.SENSOR_SENSITIVITY) ?: 0
                        val boost = result.get(R.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 0
                        if (result.get(R.CONTROL_AE_MODE) == R.CONTROL_AE_MODE_OFF &&
                            abs(exposure - choice.exposureNs) <= choice.exposureNs / 100 &&
                            abs(iso - choice.iso) <= maxOf(1, choice.iso / 50) && abs(boost - choice.boost) <= 2) {
                            profile.put("settleMs", SystemClock.elapsedRealtime() - started)
                                .put("actualExposureNs", exposure).put("actualIso", iso).put("actualBoost", boost)
                            return lease
                        }
                    }
                    Thread.sleep(25)
                }
                refused.add(refusalKey)
            } catch (error: Exception) {
                lease.close()
                if (cancelled.get()) throw error
                refused.add(refusalKey)
                return null
            }
            lease.close()
            return null
        }
    }
}
