package app.grapheneos.camera

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.SharedPreferences
import android.hardware.camera2.CaptureResult
import android.os.SystemClock
import androidx.camera.camera2.interop.Camera2Interop
import app.grapheneos.camera.capturer.CameraBlockRecovery
import app.grapheneos.camera.capturer.CaptureResultMonitor
import app.grapheneos.camera.capturer.NightSceneDetector
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.widget.Button
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.TorchState
import androidx.camera.core.UseCase
import androidx.camera.core.ZoomState
import androidx.camera.core.featuregroup.GroupableFeature
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.DynamicRange
import androidx.camera.video.GroupableFeatures
import androidx.camera.video.HighSpeedVideoSessionConfig
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.internal.muxer.MediaMuxerImpl
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import app.grapheneos.camera.analyzer.QRAnalyzer
import app.grapheneos.camera.capturer.PhotoCapturePolicy
import app.grapheneos.camera.capturer.RawLens
import app.grapheneos.camera.capturer.RawLensCatalog
import app.grapheneos.camera.capturer.PhotoOutputFormat
import app.grapheneos.camera.capturer.VideoFrameRate
import app.grapheneos.camera.ktx.applyPreviewRatio
import app.grapheneos.camera.ui.activities.CaptureActivity
import app.grapheneos.camera.ui.activities.MainActivity
import app.grapheneos.camera.ui.activities.MoreSettings
import app.grapheneos.camera.ui.activities.SecureActivity
import app.grapheneos.camera.ui.activities.SecureMainActivity
import app.grapheneos.camera.ui.activities.VideoCaptureActivity
import app.grapheneos.camera.ui.activities.VideoOnlyActivity
import app.grapheneos.camera.ui.showIgnoringShortEdgeMode
import app.grapheneos.camera.util.edit
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.concurrent.thread

// note that enum constant name is used as a name of a SharedPreferences instance
enum class CameraMode(val extensionMode: Int, val uiName: Int) {
    QR_SCAN(ExtensionMode.NONE, R.string.qr_scan_mode),
    AUTO(ExtensionMode.AUTO, R.string.auto_mode),
    FACE_RETOUCH(ExtensionMode.FACE_RETOUCH, R.string.face_retouch_mode),
    PORTRAIT(ExtensionMode.BOKEH, R.string.portrait_mode),
    NIGHT(ExtensionMode.NIGHT, R.string.night_mode),
    HDR(ExtensionMode.HDR, R.string.hdr_mode),
    CAMERA(ExtensionMode.NONE, R.string.camera),
    VIDEO(ExtensionMode.NONE, R.string.video),
    SLOW_MOTION(ExtensionMode.NONE, R.string.slow_motion_mode),
}

@SuppressLint("UnsafeOptInUsageError")
class CamConfig(private val mActivity: MainActivity) {

    enum class GridType {
        NONE,
        THREE_BY_THREE,
        FOUR_BY_FOUR,
        GOLDEN_RATIO
    }

    object SettingValues {

        object Key {
            const val SELF_ILLUMINATION = "self_illumination"
            const val GEO_TAGGING = "geo_tagging"
            const val FLASH_MODE = "flash_mode"
            const val GRID = "grid"
            // obsolete, split into WAIT_FOR_FOCUS_LOCK and PHOTO_QUALITY
            const val EMPHASIS_ON_QUALITY = "emphasis_on_quality"
            const val FOCUS_TIMEOUT = "focus_timeout"
            const val VIDEO_QUALITY = "video_quality"
            const val ASPECT_RATIO = "aspect_ratio"
            const val INCLUDE_AUDIO = "include_audio"
            const val ENABLE_EIS = "enable_eis"
            const val SCAN = "scan"
            const val SCAN_ALL_CODES = "scan_all_codes"
            const val SAVE_IMAGE_AS_PREVIEW = "save_image_as_preview"
            const val SAVE_VIDEO_AS_PREVIEW = "save_video_as_preview"

            const val STORAGE_LOCATION = "storage_location"
            const val PREVIOUS_SAF_TREES = "previous_saf_trees"

            const val LAST_CAPTURED_ITEM_TYPE = "last_captured_item_type"
            const val LAST_CAPTURED_ITEM_DATE_STRING = "last_captured_item_date_string"
            const val LAST_CAPTURED_ITEM_URI = "last_captured_item_uri"

            const val PHOTO_QUALITY = "photo_quality"

            const val REMOVE_EXIF_AFTER_CAPTURE = "remove_exif_after_capture"

            const val GYROSCOPE_SUGGESTIONS = "gyroscope_suggestions"

            const val CAMERA_SOUNDS = "camera_sounds"

            const val ENABLE_ZSL = "enable_zsl"

            const val SELECT_HIGHEST_RESOLUTION = "select_highest_resolution"

            // Legacy name: this setting selected CameraX's capture processing mode.
            const val PRIORITIZE_CAPTURE_QUALITY = "prioritize_capture_quality"

            const val SELF_TIMER_DURATION = "self_timer_duration"
        }

        object Default {

            val GRID_TYPE = GridType.NONE
            const val GRID_TYPE_INDEX = 0

            const val ASPECT_RATIO = AspectRatio.RATIO_4_3

            val VIDEO_QUALITY = Quality.HIGHEST

            const val SELF_ILLUMINATION = false

            const val GEO_TAGGING = false

            const val FLASH_MODE = ImageCapture.FLASH_MODE_OFF

            const val FOCUS_TIMEOUT = "5s"

            const val INCLUDE_AUDIO = true

            const val ENABLE_EIS = true

            const val SCAN_ALL_CODES = false

            const val SAVE_IMAGE_AS_PREVIEW = true

            const val SAVE_VIDEO_AS_PREVIEW = true

            const val STORAGE_LOCATION = ""

            const val PHOTO_QUALITY = 95

            const val REMOVE_EXIF_AFTER_CAPTURE = true

            const val GYROSCOPE_SUGGESTIONS = false

            const val CAMERA_SOUNDS = true

            const val ENABLE_ZSL = false

            const val SELECT_HIGHEST_RESOLUTION = false

            const val SELF_TIMER_DURATION = 0
        }
    }

    companion object {
        private const val TAG = "CamConfig"

        private const val PREVIEW_SNAP_DURATION = 200L
        private const val PREVIEW_SL_OVERLAY_DUR = 200L

        const val DEFAULT_LENS_FACING = CameraSelector.LENS_FACING_BACK

        val commonFormats = arrayOf(
            BarcodeFormat.AZTEC,
            BarcodeFormat.QR_CODE,
            BarcodeFormat.DATA_MATRIX,
            BarcodeFormat.PDF_417,
        )

        val imageCollectionUri: Uri = MediaStore.Images.Media.getContentUri(
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        )!!

        val videoCollectionUri: Uri = MediaStore.Video.Media.getContentUri(
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        )!!

        val DEFAULT_CAMERA_MODE = CameraMode.CAMERA

        const val COMMON_SHARED_PREFS_NAME = "commons"

        val FRONT_CAMERA_SELECTOR = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build()

        val REAR_CAMERA_SELECTOR = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        // Whether a vendor extension is usable, keyed by lens facing and extension mode (see
        // probeExtension). A verdict describes the device rather than any one activity and costs
        // binder round trips to reach, so it is kept for the process: every lock screen launch used
        // to re-probe what the session underneath it had already answered.
        private val extensionUsability = HashMap<Pair<Int, Int>, Boolean>()

        // The provider the verdicts above were probed through. A different instance means the
        // camera stack was reinitialized and none of them describe it any more.
        private var probedCameraProvider: ProcessCameraProvider? = null
    }

    private val nightDetector = NightSceneDetector()
    @Volatile private var lastNightObservation = 0L
    @Volatile private var nightAvailable = false
    private var diagnosticNoiseCamera:Camera?=null
    private var diagnosticNoiseOriginal:androidx.camera.camera2.interop.CaptureRequestOptions?=null
    private var temporalPreview:app.grapheneos.camera.ui.TemporalPreviewEffect?=null
    private var temporalPreviewUnavailable=false
    private var temporalPreviewZoom=1f
    private val requestedVideoZoom=mutableMapOf<Int,Float>()
    private var diagnosticTemporalEnabled=true
    private var diagnosticSpatialLuma:Int?=null

    fun closePreviewProcessing() {temporalPreview?.close();temporalPreview=null}
    /** The rear ultra-wide feeds the viewfinder (its own physical camera or a zoom below 1x). */
    val ultraWideViewfinder: Boolean get() = lensFacing==CameraSelector.LENS_FACING_BACK &&
        (physicalWideBound || (zoomState?.zoomRatio ?: requestedPhotoZoom)<.999f)
    private fun refreshTemporalPreview() {
        val zoom=zoomState?.zoomRatio ?: requestedPhotoZoom
        val wide=ultraWideViewfinder
        temporalPreview?.parameters(wide && (!BuildConfig.DEBUG || diagnosticTemporalEnabled),kotlin.math.abs(zoom-temporalPreviewZoom)>.002f)
        temporalPreviewZoom=zoom
    }
    private fun temporalPreviewFailed(generation:Long) {mActivity.runOnUiThread {
        if(generation!=previewRecoveryGeneration || temporalPreviewUnavailable || !mActivity.isStarted)return@runOnUiThread
        temporalPreviewUnavailable=true
        val deadline=SystemClock.elapsedRealtime()+3000
        fun retry() {
            if(generation!=previewRecoveryGeneration || !mActivity.isStarted || mActivity.isDestroyed)return
            if(!mActivity.imageCapturer.isTakingPicture && !mActivity.videoCapturer.isRecording && !directBurstActive)
                startCamera(forced=true,preserveCapture=true)
            else if(SystemClock.elapsedRealtime()<deadline)mActivity.previewView.postDelayed({retry()},100)
        }
        retry()
    }}

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun applyDiagnosticPreviewNoise(mode:Int) {
        if(!BuildConfig.DEBUG || mode !in -1..4 || currentMode !in setOf(CameraMode.CAMERA,CameraMode.VIDEO) ||
            mActivity.imageCapturer.isTakingPicture || mActivity.videoCapturer.isRecording || directBurstActive)return
        val current=camera ?: return
        val control=androidx.camera.camera2.interop.Camera2CameraControl.from(current.cameraControl)
        if(mode==-1) {
            if(diagnosticNoiseCamera===current)diagnosticNoiseOriginal?.let {control.setCaptureRequestOptions(it)}
            diagnosticNoiseCamera=null;diagnosticNoiseOriginal=null
            return
        }
        val modes=Camera2CameraInfo.from(current.cameraInfo).getCameraCharacteristic(
            CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
        if(modes?.contains(mode)!=true)return
        if(diagnosticNoiseCamera!==current) {
            diagnosticNoiseCamera=current;diagnosticNoiseOriginal=control.getCaptureRequestOptions()
        }
        control.addCaptureRequestOptions(androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
            .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE,mode).build())
    }
    val captureResults = CaptureResultMonitor { result ->
        val now = SystemClock.elapsedRealtime()
        val hold = mActivity.previewHold
        if (hold.awaitingResult && app.grapheneos.camera.capturer.PreviewSettle.settled(result)) {
            val generation = hold.current()
            mActivity.mainExecutor.execute { mActivity.previewSettled(generation) }
        }
        if(BuildConfig.DEBUG && result.get(CaptureResult.CONTROL_CAPTURE_INTENT)==android.hardware.camera2.CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE) {
            app.grapheneos.camera.capturer.PortraitPreviewDiagnostics.stillCapturedAtMs=now
            app.grapheneos.camera.capturer.PortraitPreviewDiagnostics.stillNoiseReductionMode=result.get(CaptureResult.NOISE_REDUCTION_MODE) ?: -1
            app.grapheneos.camera.capturer.PortraitPreviewDiagnostics.apply {
                stillExposureNs=result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                stillIso=result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                stillBoost=result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 0
                stillAeMode=result.get(CaptureResult.CONTROL_AE_MODE) ?: -1
            }
        }
        if (now - lastNightObservation >= 250) {
            lastNightObservation = now
            if(BuildConfig.DEBUG) {
                app.grapheneos.camera.capturer.PortraitPreviewDiagnostics.temporalPreviewRequest?.let {enabled ->
                    app.grapheneos.camera.capturer.PortraitPreviewDiagnostics.temporalPreviewRequest=null
                    mActivity.runOnUiThread {diagnosticTemporalEnabled=enabled;refreshTemporalPreview()}
                }
                app.grapheneos.camera.capturer.PortraitPreviewDiagnostics.noiseReductionRequest?.let {mode ->
                    app.grapheneos.camera.capturer.PortraitPreviewDiagnostics.noiseReductionRequest=null
                    mActivity.runOnUiThread {applyDiagnosticPreviewNoise(mode)}
                }
                app.grapheneos.camera.capturer.PortraitPreviewDiagnostics.spatialLumaRequest?.let {value ->
                    app.grapheneos.camera.capturer.PortraitPreviewDiagnostics.spatialLumaRequest=null
                    if(currentMode==CameraMode.VIDEO)mActivity.runOnUiThread {
                        diagnosticSpatialLuma=value
                        temporalPreview?.luminance(value/100f)
                        app.grapheneos.camera.capturer.PortraitPreviewDiagnostics.spatialLumaApplied=value
                    }
                }
                app.grapheneos.camera.capturer.PortraitPreviewDiagnostics.exposureNs=result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                app.grapheneos.camera.capturer.PortraitPreviewDiagnostics.frameDurationNs=result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L
                app.grapheneos.camera.capturer.PortraitPreviewDiagnostics.apply {
                    capturedAtMs=now
                    noiseReductionMode=result.get(CaptureResult.NOISE_REDUCTION_MODE) ?: -1
                    edgeMode=result.get(CaptureResult.EDGE_MODE) ?: -1
                    iso=result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                    boost=result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 0
                    aeMode=result.get(CaptureResult.CONTROL_AE_MODE) ?: -1
                    val fps=result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)
                    fpsLower=fps?.lower ?: 0;fpsUpper=fps?.upper ?: 0
                }
            }
            val suggestion = nightDetector.observe(now,
                result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0,
                result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                result.get(CaptureResult.LENS_APERTURE)?.toDouble() ?: 0.0,
                currentMode == CameraMode.CAMERA && !isVideoMode && nightAvailable,
                flashMode != ImageCapture.FLASH_MODE_OFF || isTorchOn, true)
            mActivity.runOnUiThread { mActivity.updateNightSuggestion(suggestion) }
        }
    }
    fun dismissNightSuggestion() {
        nightDetector.dismiss()
        mActivity.updateNightSuggestion(false)
    }

    var portraitSubjectX = .5f
    var portraitSubjectY = .5f
    var portraitBlur: Int
        get() = commonPref.getInt("portrait_blur", 8).coerceIn(1, 20)
        set(value) { commonPref.edit { putInt("portrait_blur", value.coerceIn(1, 20)) }; mActivity.refreshPortraitPreview() }

    var camera: Camera? = null

    // Asking CameraInfo for the zoom state is cheap for a plain camera but costs ~100 ms once an
    // extension is bound, because CameraX then queries the extension's zoom range through
    // CameraExtensionCharacteristics, which enumerates every vendor key. Read this snapshot instead
    // of the camera on any path that runs more than once per bind.
    var zoomState: ZoomState? = null
        private set

    private var zoomStateSource: LiveData<ZoomState>? = null

    private val zoomStateObserver = Observer<ZoomState> {
        zoomState = photoZoomState(it)
        refreshTemporalPreview()
        if (mActivity.imageCapturer.isTakingPicture || directBurstActive) return@Observer
        if (it.linearZoom != 0f || it.zoomRatio != 1f) {
            mActivity.zoomBar.updateThumb()
        } else mActivity.zoomBar.updateThumb(false)
    }

    val acquisitionZoomRatio: Float get() = when {
        usesPhysicalModeLens -> nightZoom
        prefersPhysicalMain -> requestedPhotoZoom/photoLensScale
        else -> zoomState?.zoomRatio ?: 1f
    }
    val displayZoom: Float get() = mActivity.imageCapturer.captureZoom ?: when {
        usesPhysicalModeLens -> nightZoom * photoLensScale
        prefersPhysicalMain -> requestedPhotoZoom
        else -> zoomState?.zoomRatio ?: 1f
    }
    private var requestedPhotoZoom = 1f
    private var physicalMainBound = false
    private var physicalWideBound = false
    private var photoLensScale = 1f
    private var photoMaximum = 1f
    private var photoWideMinimum = 1f
    private val rawCatalogCache = HashMap<Int, List<RawLens>>()
    private val physicalZoomRanges = HashMap<String, app.grapheneos.camera.capturer.PhotoZoomMapping.Range>()
    private var wideScaleCache: Float? = null
    private val usesPhysicalModeLens: Boolean get() = usesRawLens || softwarePortrait

    private fun wideDisplayScale(): Float = wideScaleCache ?: (cameraProvider?.availableCameraInfos
        ?.filter { Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.LENS_FACING) == CameraSelector.LENS_FACING_BACK }
        ?.minOfOrNull { minZoomRatio(it) } ?: 1f).also { wideScaleCache = it }

    private fun lensDisplayScale(lens: RawLens): Float =
        if (lensFacing == CameraSelector.LENS_FACING_BACK && lens.label == R.string.lens_ultra_wide) wideDisplayScale() else 1f
    private val prefersPhysicalMain: Boolean get() = currentMode == CameraMode.CAMERA &&
        lensFacing == CameraSelector.LENS_FACING_BACK && !isVideoMode && photoOutputFormat == PhotoOutputFormat.JPEG &&
        mActivity.resources.getBoolean(R.bool.config_camera_prefer_physical_main)

    private fun photoZoomState(state: ZoomState): ZoomState {
        if (currentMode == CameraMode.SLOW_MOTION || usesPhysicalModeLens) {
            val mapping = app.grapheneos.camera.capturer.PhotoZoomMapping
            val native = app.grapheneos.camera.capturer.PhotoZoomMapping.Range(state.minZoomRatio, state.maxZoomRatio)
            val scale = if (usesPhysicalModeLens) photoLensScale else 1f
            val range = if (currentMode == CameraMode.SLOW_MOTION) mapping.highSpeedRange(native) else {
                val companions = switchablePhysicalLenses().map { lens ->
                    physicalZoomRanges.getOrPut(lens.id) {
                        val c = Camera2CameraInfo.from(lens.info)
                        val advertised = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                            c.getCameraCharacteristic(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
                        app.grapheneos.camera.capturer.PhotoZoomMapping.Range(
                            advertised?.lower ?: 1f,
                            advertised?.upper ?: c.getCameraCharacteristic(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
                    }.let { local -> app.grapheneos.camera.capturer.PhotoZoomMapping.Range(
                        mapping.toDisplay(local.minimum, lensDisplayScale(lens)),
                        mapping.toDisplay(local.maximum, lensDisplayScale(lens))) }
                }
                mapping.combinedRange(native, scale, companions)
            }
            val ratio = (state.zoomRatio * scale).coerceIn(range.minimum, range.maximum)
            return object : ZoomState {
                override fun getZoomRatio() = ratio
                override fun getMinZoomRatio() = range.minimum
                override fun getMaxZoomRatio() = range.maximum
                override fun getLinearZoom() = if (range.maximum == range.minimum) 0f else
                    ((1f / range.minimum - 1f / ratio) / (1f / range.minimum - 1f / range.maximum)).coerceIn(0f, 1f)
            }
        }
        if (!prefersPhysicalMain || photoWideMinimum >= 1f) return state
        if (physicalMainBound) photoMaximum = state.maxZoomRatio
        val ratio = app.grapheneos.camera.capturer.PhotoZoomMapping.toDisplay(state.zoomRatio, photoLensScale)
        val maximum = maxOf(photoMaximum, state.maxZoomRatio * photoLensScale)
        return object : ZoomState {
            override fun getZoomRatio() = ratio
            override fun getMinZoomRatio() = photoWideMinimum
            override fun getMaxZoomRatio() = maximum
            override fun getLinearZoom(): Float = ((1f / photoWideMinimum - 1f / ratio) /
                (1f / photoWideMinimum - 1f / maximum)).coerceIn(0f, 1f)
        }
    }

    fun setZoomRatio(value: Float, allowLensSwitch: Boolean = true, gesture: Boolean = false) {
        if (!value.isFinite() || mActivity.imageCapturer.isTakingPicture) return
        val state = zoomState ?: return
        var target = value.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        if (usesPhysicalModeLens) {
            val lenses = switchablePhysicalLenses()
            val current = selectedRawLens()
            if (lenses.isNotEmpty() && current != null) {
                val useMain = app.grapheneos.camera.capturer.PhotoZoomMapping.useMainLens(
                    target, current.label == R.string.lens_main, gesture)
                val selected = lenses.first { it.label == if (useMain) R.string.lens_main else R.string.lens_ultra_wide }
                if (selected.id != current.id) {
                    if (!allowLensSwitch) return
                    rawLensId = selected.id
                    nightZoom = (target / lensDisplayScale(selected)).coerceAtLeast(1f)
                    startCamera(true)
                    return
                }
            }
        }
        if (prefersPhysicalMain) {
            if (kotlin.math.abs(target - 1f) < .05f) target = 1f
            val mainAvailable = rawLenses().any { it.label == R.string.lens_main }
            if (mainAvailable && (if (gesture) (if (physicalMainBound) target < .92f else target >= 1.04f) else (target >= 1f) != physicalMainBound)) {
                if (!allowLensSwitch) return
                requestedPhotoZoom = target
                startCamera(true)
                return
            }
            requestedPhotoZoom = target
        }
        val requestedLocal = if (prefersPhysicalMain || usesPhysicalModeLens) target / photoLensScale else target
        val local = zoomStateSource?.value?.let { requestedLocal.coerceIn(it.minZoomRatio, it.maxZoomRatio) } ?: requestedLocal
        if (usesPhysicalModeLens) nightZoom = local
        if(currentMode==CameraMode.VIDEO)requestedVideoZoom[lensFacing]=target
        camera?.cameraControl?.setZoomRatio(local)
    }

    /** The lens buttons this mode and camera support, from the zoom range of the current binding. */
    fun zoomPresets(): List<Float> {
        val state = zoomState ?: return emptyList()
        return app.grapheneos.camera.capturer.LensPresets.choose(state.minZoomRatio, state.maxZoomRatio)
    }

    fun selectZoomPreset(value: Float) {
        if (mActivity.imageCapturer.isTakingPicture || directBurstActive) return
        if (usesPhysicalModeLens) {
            val lens = selectableRawLenses().firstOrNull { it.label == if (value < 1f) R.string.lens_ultra_wide else R.string.lens_main }
                ?: return
            if (rawLensId != lens.id) {
                rawLensId = lens.id
                nightZoom = (value / lensDisplayScale(lens)).coerceAtLeast(1f)
                startCamera(true)
            } else setZoomRatio(value)
        } else setZoomRatio(value)
    }

    var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null

    var imageCapture: ImageCapture? = null
        private set

    private var nightZoom = 1f
    var rawLensId: String?
        get() = commonPref.getString("raw_lens_id_$lensFacing", null)
        set(value) {
            commonPref.edit { putString("raw_lens_id_$lensFacing", value) }
            if (softwareNight) nightZoom = 1f
        }

    fun rawLenses(): List<RawLens> = rawCatalogCache.getOrPut(lensFacing) {
        RawLensCatalog.lenses(cameraProvider?.availableCameraInfos ?: emptyList(), lensFacing)
    }

    fun selectableRawLenses(): List<RawLens> = rawLenses().let { lenses ->
        if (softwareNight) lenses.filter { supportsNight(it.info) } else lenses
    }

    private fun selectedRawLens(): RawLens? = selectableRawLenses().let { lenses ->
        lenses.firstOrNull { it.id == rawLensId } ?: lenses.firstOrNull()
    }

    private fun switchablePhysicalLenses(): List<RawLens> {
        if (!usesPhysicalModeLens || lensFacing != CameraSelector.LENS_FACING_BACK) return emptyList()
        val lenses = selectableRawLenses().filter { it.label == R.string.lens_main || it.label == R.string.lens_ultra_wide }
        return if (wideDisplayScale() < 1f && lenses.any { it.label == R.string.lens_main } &&
            lenses.any { it.label == R.string.lens_ultra_wide } && selectedRawLens()?.id in lenses.map { it.id }) lenses else emptyList()
    }

    private fun vendorModeAvailable(mode: CameraMode, facing: Int = lensFacing): Boolean =
        isExtensionUsable(if (facing == CameraSelector.LENS_FACING_FRONT) FRONT_CAMERA_SELECTOR else REAR_CAMERA_SELECTOR,
            facing, mode.extensionMode, probeOnMiss = false)

    val softwareNight: Boolean get() = currentMode == CameraMode.NIGHT && !vendorModeAvailable(CameraMode.NIGHT)
    val softwarePortrait: Boolean get() = currentMode == CameraMode.PORTRAIT && !vendorModeAvailable(CameraMode.PORTRAIT)

    val enhancedPhotoAvailable: Boolean get() = mActivity !is CaptureActivity &&
        lensFacing == CameraSelector.LENS_FACING_BACK &&
        mActivity.resources.getBoolean(R.bool.config_camera_direct_raw_burst) &&
        mActivity.resources.getBoolean(R.bool.config_camera_prefer_physical_main) &&
        rawLenses().any { enhancedLensEnabled(it) && supportsNight(it.info) }
    private fun enhancedLensEnabled(lens: RawLens): Boolean = when(lens.label) {
        R.string.lens_main -> mActivity.resources.getBoolean(R.bool.config_camera_main_raw_burst)
        R.string.lens_ultra_wide -> mActivity.resources.getBoolean(R.bool.config_camera_ultra_wide_burst)
        else -> false
    }
    val softwarePhoto: Boolean get() = prefersPhysicalMain && prioritizeCaptureQuality &&
        mActivity !is CaptureActivity && mActivity.resources.getBoolean(R.bool.config_camera_direct_raw_burst) &&
        rawLenses().any { enhancedLensEnabled(it) && supportsNight(it.info) &&
            ((physicalMainBound && it.label==R.string.lens_main) || (physicalWideBound && it.label==R.string.lens_ultra_wide)) }
    val softwareFastPhoto: Boolean get() = prefersPhysicalMain && physicalWideBound && !prioritizeCaptureQuality &&
        mActivity.resources.getBoolean(R.bool.config_camera_ultra_wide_jpeg_denoise)
    val usesProcessingCapture: Boolean get() = softwareNight || softwarePortrait || softwarePhoto || softwareFastPhoto
    val softwareJpegNight: Boolean get() = app.grapheneos.camera.capturer.IspJpegNight.enabled(mActivity) &&
        softwareNight && lensFacing == CameraSelector.LENS_FACING_BACK && mActivity !is CaptureActivity &&
        mActivity.resources.getBoolean(R.bool.config_camera_direct_raw_burst)
    val softwareJpegBurst: Boolean get() = softwareJpegNight ||
        (BuildConfig.DEBUG && BuildConfig.CAMERA_JPEG_BURST_EVALUATION &&
            currentMode == CameraMode.CAMERA && !isVideoMode && mActivity !is CaptureActivity &&
            lensFacing == CameraSelector.LENS_FACING_BACK && physicalWideBound &&
            activePhotoOutputFormat == PhotoOutputFormat.JPEG && flashMode == ImageCapture.FLASH_MODE_OFF && !isTorchOn &&
            (softwarePhoto || softwareFastPhoto))
    val processingFrameCount: Int get() = if (softwarePortrait || softwareFastPhoto) 0 else if (softwarePhoto) 4 else 8

    private val usesRawLens: Boolean get() = softwareNight ||
        (currentMode == CameraMode.CAMERA && !isVideoMode && mActivity !is CaptureActivity && photoOutputFormat != PhotoOutputFormat.JPEG)

    var photoOutputFormat: PhotoOutputFormat
        get() = PhotoOutputFormat.stored(commonPref.getString("photo_output_format", null))
        set(value) { commonPref.edit { putString("photo_output_format", value.name) } }

    var supportedPhotoOutputFormats: List<PhotoOutputFormat> = listOf(PhotoOutputFormat.JPEG)
        private set

    var activePhotoOutputFormat = PhotoOutputFormat.JPEG
        private set

    /** The physical RAW lens of the current binding, if any. */
    private var boundRawLensLabel: Int? = null

    /** RAW phase of a split JPEG + RAW capture: ImageCapture is bound for RAW alone. */
    internal var splitRawPhase = false

    val splitsRawJpeg: Boolean get() = PhotoOutputFormat.splitsRawJpeg(activePhotoOutputFormat,
        supportedPhotoOutputFormats, lensFacing == CameraSelector.LENS_FACING_BACK && boundRawLensLabel == R.string.lens_ultra_wide,
        mActivity.resources.getBoolean(R.bool.config_camera_ultra_wide_split_raw_jpeg))

    var preview: Preview? = null

    val allowedFormats: ArrayList<BarcodeFormat> = arrayListOf()

    private val cameraExecutor by lazy {
        Executors.newSingleThreadExecutor()
    }

    var videoCapture: VideoCapture<Recorder>? = null

    private var qrAnalyzer: QRAnalyzer? = null

    var iAnalyzer: ImageAnalysis? = null

    @set:VisibleForTesting
    var mPlayer = TunePlayer(mActivity)

    // note that Activities which implement SecureActivity interface (meaning they are accessible
    // from the lock screen) are forced to override getSharedPreferences()
    // and return an instance of in-memory EphemeralSharedPrefs, which are based on "real" prefs,
    // but never modify them
    val commonPref: SharedPreferences = mActivity.getSharedPreferences(COMMON_SHARED_PREFS_NAME, Context.MODE_PRIVATE)
    private lateinit var modePref: SharedPreferences

    var lastCapturedItem: CapturedItem? = null

    init {
        if (mActivity !is SecureActivity) {
            CapturedItems.init(mActivity, this)
            fetchLastCapturedItemFromSharedPrefs()
        }
    }

    fun fetchLastCapturedItemFromSharedPrefs() {
        val type = commonPref.getInt(SettingValues.Key.LAST_CAPTURED_ITEM_TYPE, -1)
        val dateStr = commonPref.getString(SettingValues.Key.LAST_CAPTURED_ITEM_DATE_STRING, null)
        val uri = commonPref.getString(SettingValues.Key.LAST_CAPTURED_ITEM_URI, null)

        var item: CapturedItem? = null
        if (dateStr != null && uri != null) {
            val skip = type == ITEM_TYPE_IMAGE && mActivity is VideoOnlyActivity
            if (!skip) {
                item = CapturedItem(type, dateStr, Uri.parse(uri), commonPref.getBoolean("last_captured_item_is_raw", false))
            }
        }
        lastCapturedItem = item
        item?.let(app.grapheneos.camera.capturer.PhotoProcessingQueue::resolve)?.let { ready ->
            if (ready.uri != item.uri) updateLastCapturedItem(ready)
        }
    }


    var isVideoMode = false
        private set
        get() {
            return field ||
                    mActivity is VideoCaptureActivity ||
                    mActivity is VideoOnlyActivity
        }

    val canTakePicture : Boolean
        get() {
            return imageCapture != null
        }

    var isQRMode = false
        private set

    val isFlashAvailable: Boolean
        get() = camera?.cameraInfo?.hasFlashUnit() ?: false

    var isTorchOn: Boolean = false
        get() {
            return camera?.cameraInfo?.torchState?.value == TorchState.ON
        }
        set(value) {
            field = if (isFlashAvailable) {
                camera?.cameraControl?.enableTorch(value)
                value
            } else {
                false
            }
        }

    var currentMode: CameraMode = DEFAULT_CAMERA_MODE
        private set

    var aspectRatio: Int
        get() {
            return when {
                isVideoMode -> {
                    AspectRatio.RATIO_16_9
                }
                isQRMode || usesRawLens -> {
                    AspectRatio.RATIO_4_3
                }
                else -> {
                    commonPref.getInt(
                        SettingValues.Key.ASPECT_RATIO,
                        SettingValues.Default.ASPECT_RATIO
                    )
                }
            }
        }
        set(value) {
            val editor = commonPref.edit()
            editor.putInt(SettingValues.Key.ASPECT_RATIO, value)
            editor.apply()
        }

    var lensFacing = DEFAULT_LENS_FACING

    private var cameraSelector: CameraSelector = CameraSelector.Builder()
        .requireLensFacing(DEFAULT_LENS_FACING)
        .build()

    var gridType: GridType = SettingValues.Default.GRID_TYPE
        set(value) {
            val editor = commonPref.edit()
            editor.putInt(SettingValues.Key.GRID, GridType.values().indexOf(value))
            editor.apply()

            field = value
        }

    var videoQuality: Quality = SettingValues.Default.VIDEO_QUALITY
        get() {
            return if (modePref.contains(videoQualityKey)) {
                when (modePref.getString(videoQualityKey, "")) {
                    "UHD", "2160p (UHD)", "4K (2160p)" -> Quality.UHD
                    "FHD", "1080p (FHD)" -> Quality.FHD
                    "HD", "720p (HD)" -> Quality.HD
                    "SD", "480p (SD)" -> Quality.SD
                    else -> SettingValues.Default.VIDEO_QUALITY
                }
            } else {
                if(currentMode==CameraMode.SLOW_MOTION) slowMotionDefaultQualities[lensFacing] ?: Quality.FHD
                else SettingValues.Default.VIDEO_QUALITY
            }
        }
        set(value) {
            modePref.edit {
                putString(videoQualityKey, videoQualityPreference(value))
            }

            field = value
        }

    var availableVideoFrameRates: List<VideoFrameRate> = emptyList()
        private set
    var selectedVideoFrameRate: VideoFrameRate? = null
        private set
    var slowMotionVideoFrameRates: List<VideoFrameRate> = emptyList()
        private set
    private var videoFrameRatePreferenceKey: String? = null
    private var lastUnavailableFrameRate: Pair<String, String>? = null

    fun setVideoFrameRate(rate: VideoFrameRate?) {
        if (mActivity.videoCapturer.isRecording) return
        val key = videoFrameRatePreferenceKey ?: return
        if (rate != null && rate !in availableVideoFrameRates) return
        if (rate == selectedVideoFrameRate) return
        commonPref.edit {
            if (rate == null) remove(key) else putString(key, rate.preferenceValue())
        }
        startCamera(true)
    }

    private val videoQualityKey: String
        get() {

            val pf = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                "FRONT"
            } else {
                "BACK"
            }

            return "${SettingValues.Key.VIDEO_QUALITY}_$pf"
        }

    var flashMode: Int
        get() = if (imageCapture != null) imageCapture!!.flashMode else
            SettingValues.Default.FLASH_MODE
        set(flashMode) {

            if (::modePref.isInitialized) {
                modePref.edit {
                    putInt(SettingValues.Key.FLASH_MODE, flashMode)
                }
            }

            imageCapture?.flashMode = flashMode
            mActivity.settingsDialog.updateFlashMode()
        }

    var focusTimeout = 5L
        set(value) {
            val option = if (value == 0L) {
                "Off"
            } else {
                "${value}s"
            }

            val editor = commonPref.edit()
            editor.putString(SettingValues.Key.FOCUS_TIMEOUT, option)
            editor.apply()

            field = value
        }

    var enableCameraSounds: Boolean
        get() {
            return commonPref.getBoolean(
                SettingValues.Key.CAMERA_SOUNDS,
                SettingValues.Default.CAMERA_SOUNDS
            )
        }
        set(value) {
            val editor = commonPref.edit()
            editor.putBoolean(SettingValues.Key.CAMERA_SOUNDS, value)
            editor.apply()
        }

    var scanAllCodes: Boolean
        get() {
            return commonPref.getBoolean(
                SettingValues.Key.SCAN_ALL_CODES,
                SettingValues.Default.SCAN_ALL_CODES
            )
        }
        set(value) {
            val editor = commonPref.edit()
            editor.putBoolean(SettingValues.Key.SCAN_ALL_CODES, value)
            editor.apply()

            if (isQRMode) {
                if (value) {
                    mActivity.setFlipCameraIcon(
                        R.drawable.cancel, R.string.stop_scanning_all_formats
                    )
                    mActivity.qrScanToggles.visibility = View.GONE
                } else {
                    mActivity.setFlipCameraIcon(
                        R.drawable.auto, R.string.scan_all_formats
                    )
                    mActivity.qrScanToggles.visibility = View.VISIBLE
                }
            }

            qrAnalyzer?.refreshHints()
        }

    var includeAudio: Boolean
        get() {
            // High-speed slow motion has no audio track. Keep the ordinary Video
            // preference so returning to Video restores the user's choice.
            return currentMode != CameraMode.SLOW_MOTION &&
                mActivity.settingsDialog.includeAudioToggle.isChecked
        }
        set(value) {
            val editor = commonPref.edit()
            editor.putBoolean(SettingValues.Key.INCLUDE_AUDIO, value)
            editor.apply()

            mActivity.settingsDialog.includeAudioToggle.isChecked = value
        }

    var enableEIS: Boolean
        get() {
            return mActivity.settingsDialog.enableEISToggle.isChecked
        }
        set(value) {
            val editor = commonPref.edit()
            editor.putBoolean(SettingValues.Key.ENABLE_EIS, value)
            editor.apply()

            mActivity.settingsDialog.enableEISToggle.isChecked = value
        }

    var enableZsl: Boolean
        get() {
            return commonPref.getBoolean(
                SettingValues.Key.ENABLE_ZSL,
                SettingValues.Default.ENABLE_ZSL
            )
        }
        set(value) {
            val editor = commonPref.edit()
            editor.putBoolean(SettingValues.Key.ENABLE_ZSL, value)
            editor.apply()
        }

    var saveImageAsPreviewed: Boolean
        get() {
            return commonPref.getBoolean(
                SettingValues.Key.SAVE_IMAGE_AS_PREVIEW,
                SettingValues.Default.SAVE_IMAGE_AS_PREVIEW
            )
        }
        set(value) {
            val editor = commonPref.edit()
            editor.putBoolean(SettingValues.Key.SAVE_IMAGE_AS_PREVIEW, value)
            editor.apply()
        }

    var saveVideoAsPreviewed: Boolean
        get() {
            return commonPref.getBoolean(
                SettingValues.Key.SAVE_VIDEO_AS_PREVIEW,
                SettingValues.Default.SAVE_VIDEO_AS_PREVIEW
            )
        }
        set(value) {
            val editor = commonPref.edit()
            editor.putBoolean(SettingValues.Key.SAVE_VIDEO_AS_PREVIEW, value)
            editor.apply()
        }

    var storageLocation: String
        get() {
            return commonPref.getString(
                SettingValues.Key.STORAGE_LOCATION,
                SettingValues.Default.STORAGE_LOCATION
            )!!
        }
        set(value) {
            val cur = storageLocation
            if (cur != SettingValues.Default.STORAGE_LOCATION) {
                CapturedItems.savePreviousSafTree(Uri.parse(cur), commonPref)
            }

            val editor = commonPref.edit()
            editor.putString(SettingValues.Key.STORAGE_LOCATION, value)
            editor.apply()

            // Strictly after the write: the tree being picked only becomes tracked once it is the
            // stored location, and re-picking a tree that savePreviousSafTree() just pushed off the
            // tail of the tracked list would otherwise have its grant revoked out from under it.
            CapturedItems.releaseUntrackedSafTrees(mActivity, commonPref)
        }

    var photoQuality: Int
        get() {
            return commonPref.getInt(
                SettingValues.Key.PHOTO_QUALITY,
                SettingValues.Default.PHOTO_QUALITY
            )
        }
        set(value) {
            val editor = commonPref.edit()
            editor.putInt(SettingValues.Key.PHOTO_QUALITY, value)
            editor.apply()
        }

    var removeExifAfterCapture: Boolean
        get() {
            return commonPref.getBoolean(
                SettingValues.Key.REMOVE_EXIF_AFTER_CAPTURE,
                SettingValues.Default.REMOVE_EXIF_AFTER_CAPTURE
            )
        }
        set(value) {
            val editor = commonPref.edit()
            editor.putBoolean(
                SettingValues.Key.REMOVE_EXIF_AFTER_CAPTURE,
                value
            )
            editor.apply()
        }

    var gSuggestions: Boolean
        get() {
            return commonPref.getBoolean(
                SettingValues.Key.GYROSCOPE_SUGGESTIONS,
                SettingValues.Default.GYROSCOPE_SUGGESTIONS
            )
        }
        set(value) {
            val editor = commonPref.edit()
            editor.putBoolean(
                SettingValues.Key.GYROSCOPE_SUGGESTIONS,
                value
            )
            editor.apply()
        }

    val isZslSupported : Boolean by lazy {
        camera!!.cameraInfo.isZslSupported
    }

    // Whether the EIS toggle has anything to act on. Stabilization is only ever requested through
    // the feature group (see startCamera), which in turn needs the platform to be able to verify
    // feature combinations, so a camera that can stabilize is not on its own enough: where the
    // group cannot be used nothing applies EIS, and offering the toggle there is offering a
    // control that does nothing.
    fun canApplyVideoStabilization() : Boolean {
        return canVerifyFeatureCombinations() && isVideoStabilizationSupported()
    }

    private fun isVideoStabilizationSupported() : Boolean {
        // The toggle asks for both kinds of stabilization (see the preferred feature group in
        // startCamera), preferring the preview kind and falling back to the recording-only kind,
        // so it is meaningful whenever either one is available. Testing only the recorder
        // capability both hid the toggle on cameras that can stabilize the preview but not the
        // recording, and offered it on cameras where only the recorder can stabilize - where
        // the bind used to ask exclusively for preview stabilization, leaving the toggle with
        // nothing to do.
        return isPreviewStabilizationSupported() || isRecorderStabilizationSupported()
    }

    private fun isPreviewStabilizationSupported() : Boolean {
        return Preview.getPreviewCapabilities(getCurrentCameraInfo()).isStabilizationSupported
    }


    private fun isRecorderStabilizationSupported() : Boolean {
        return Recorder.getVideoCapabilities(getCurrentCameraInfo()).isStabilizationSupported
    }

    fun shouldShowGyroscope(): Boolean {
        return isInPhotoMode && gSuggestions
    }

    private val isInPhotoMode: Boolean
        get() {
            return !(isQRMode || isVideoMode)
        }

    val isInCaptureMode: Boolean
        get() {
            return mActivity is CaptureActivity
        }

    private fun saveLastCapturedItem(item: CapturedItem, editor: SharedPreferences.Editor) {
        editor.putBoolean("last_captured_item_is_raw", item.isRaw)
        editor.putInt(SettingValues.Key.LAST_CAPTURED_ITEM_TYPE, item.type)
        editor.putString(SettingValues.Key.LAST_CAPTURED_ITEM_DATE_STRING, item.dateString)
        editor.putString(SettingValues.Key.LAST_CAPTURED_ITEM_URI, item.uri.toString())
    }

    fun replaceLastCapturedItem(item: CapturedItem?) {
        lastCapturedItem = null
        if (item != null) { updateLastCapturedItem(item); return }
        fun clear(editor: SharedPreferences.Editor) {
            editor.remove(SettingValues.Key.LAST_CAPTURED_ITEM_TYPE)
            editor.remove(SettingValues.Key.LAST_CAPTURED_ITEM_DATE_STRING)
            editor.remove(SettingValues.Key.LAST_CAPTURED_ITEM_URI)
            editor.remove("last_captured_item_is_raw")
        }
        commonPref.edit { clear(this) }
        if (mActivity is SecureMainActivity) {
            mActivity.applicationContext.getSharedPreferences(COMMON_SHARED_PREFS_NAME, Context.MODE_PRIVATE).edit { clear(this) }
        }
    }

    fun updateLastCapturedItem(item: CapturedItem) {
        if (lastCapturedItem?.dateString?.let { it > item.dateString } == true) return
        commonPref.edit {
            saveLastCapturedItem(item, this)
        }

        if (mActivity is SecureMainActivity) {
            // previous call updated ephemeral SharedPreferences that won't be accessible by the
            // "regular" MainActivity
            mActivity.applicationContext.getSharedPreferences(COMMON_SHARED_PREFS_NAME, Context.MODE_PRIVATE).edit {
                saveLastCapturedItem(item, this)
            }
        }

        lastCapturedItem = item
    }

    var requireLocation: Boolean = false
        get() {
            return mActivity.settingsDialog.locToggle.isChecked
        }
        set(value) {
            mActivity.locationCamConfigChanged(value)

            // A permission result is delivered before the first onResume of an activity the system
            // recreated, so this can run before startCamera() has picked the prefs for a mode
            if (::modePref.isInitialized) {
                modePref.edit {
                    putBoolean(SettingValues.Key.GEO_TAGGING, value)
                }
            }

            mActivity.settingsDialog.locToggle.isChecked = value

            field = value
        }

    var selfIlluminate: Boolean
        get() {
            return modePref.getBoolean(
                SettingValues.Key.SELF_ILLUMINATION,
                SettingValues.Default.SELF_ILLUMINATION
            )
                    && lensFacing == CameraSelector.LENS_FACING_FRONT
        }
        set(value) {
            modePref.edit {
                putBoolean(SettingValues.Key.SELF_ILLUMINATION, value)
            }

            mActivity.settingsDialog.selfIlluminationToggle.isChecked = value
            mActivity.settingsDialog.selfIllumination()
        }

    private fun getString(@StringRes id: Int) = mActivity.getString(id)

    fun setQRScanningFor(format: String, selected: Boolean) {

        val formatSRep = "${SettingValues.Key.SCAN}_$format"

        commonPref.edit {
            putBoolean(formatSRep, selected)
        }

        if (selected) {
            if (BarcodeFormat.valueOf(format) !in allowedFormats) {
                allowedFormats.add(BarcodeFormat.valueOf(format))
            }
        } else {
            if (allowedFormats.size == 1) {
                mActivity.showMessage(
                    getString(R.string.no_barcode_selected)
                )
            } else {
                allowedFormats.remove(BarcodeFormat.valueOf(format))
            }
        }

        qrAnalyzer?.refreshHints()
    }

    fun reloadSettings() {
        // pref config needs to be created
        modePref.edit {
            if (!modePref.contains(SettingValues.Key.FLASH_MODE)) {
                putInt(SettingValues.Key.FLASH_MODE, SettingValues.Default.FLASH_MODE)
            }

            if (!modePref.contains(SettingValues.Key.GEO_TAGGING)) {
                putBoolean(SettingValues.Key.GEO_TAGGING, SettingValues.Default.GEO_TAGGING)
            }

            if (isVideoMode) {
                mActivity.settingsDialog.reloadQualities()
            }

            if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                if (!modePref.contains(SettingValues.Key.SELF_ILLUMINATION)) {
                    putBoolean(
                        SettingValues.Key.SELF_ILLUMINATION,
                        SettingValues.Default.SELF_ILLUMINATION
                    )
                }
            }
        }

        flashMode = modePref.getInt(
            SettingValues.Key.FLASH_MODE,
            SettingValues.Default.FLASH_MODE
        )

        // A stored "on" is written before a permission request resolves, and it outlives a later
        // revocation, so it cannot be asserted on its own: doing so opened a permission dialog on
        // startup that the user never asked for. Coercing it here settles the stale value through
        // the setter, and leaves every dialog in the app originating from an explicit toggle.
        requireLocation = modePref.getBoolean(
            SettingValues.Key.GEO_TAGGING,
            SettingValues.Default.GEO_TAGGING
        ) && !(mActivity.applicationContext as App).shouldAskForLocationPermission()

        selfIlluminate = modePref.getBoolean(
            SettingValues.Key.SELF_ILLUMINATION,
            SettingValues.Default.SELF_ILLUMINATION
        )

        mActivity.settingsDialog.showOnlyRelevantSettings()
    }

    fun loadSettings() {

        // Create common config. if it's not created
        val editor = commonPref.edit()

        if (!commonPref.contains(SettingValues.Key.CAMERA_SOUNDS)) {
            editor.putBoolean(SettingValues.Key.CAMERA_SOUNDS, SettingValues.Default.CAMERA_SOUNDS)
        }

        // Note: This is a workaround to keep save image/video as previewed 'on' by 
        // default starting from v73 and 'off' by default for versions before that
        //
        // If its not a fresh install (before v73)
        if (commonPref.contains(SettingValues.Key.SAVE_IMAGE_AS_PREVIEW)) {
            // If save video as previewed was not previously set
            if (!commonPref.contains(SettingValues.Key.SAVE_VIDEO_AS_PREVIEW)) {
                // Explicitly set the value for this setting as false for them
                // to ensure consistent behavior
                editor.putBoolean(
                    SettingValues.Key.SAVE_VIDEO_AS_PREVIEW,
                    false
                )
            }
        } else {
            editor.putBoolean(
                SettingValues.Key.SAVE_IMAGE_AS_PREVIEW,
                SettingValues.Default.SAVE_IMAGE_AS_PREVIEW
            )

            editor.putBoolean(
                SettingValues.Key.SAVE_VIDEO_AS_PREVIEW,
                SettingValues.Default.SAVE_VIDEO_AS_PREVIEW
            )
        }

        if (!commonPref.contains(SettingValues.Key.GRID)) {
            // Index for Grid.values() Default: NONE
            editor.putInt(SettingValues.Key.GRID, SettingValues.Default.GRID_TYPE_INDEX)
        }

        if (!commonPref.contains(SettingValues.Key.FOCUS_TIMEOUT)) {
            editor.putString(SettingValues.Key.FOCUS_TIMEOUT, SettingValues.Default.FOCUS_TIMEOUT)
        }

        migrateFromLegacyPhotoQuality()

        if (!commonPref.contains(SettingValues.Key.INCLUDE_AUDIO)) {
            editor.putBoolean(
                SettingValues.Key.INCLUDE_AUDIO,
                SettingValues.Default.INCLUDE_AUDIO
            )
        }

        if (!commonPref.contains(SettingValues.Key.ENABLE_EIS)) {
            editor.putBoolean(
                SettingValues.Key.ENABLE_EIS,
                SettingValues.Default.ENABLE_EIS
            )
        }

        if (!commonPref.contains(SettingValues.Key.ASPECT_RATIO)) {
            editor.putInt(
                SettingValues.Key.ASPECT_RATIO,
                SettingValues.Default.ASPECT_RATIO
            )
        }

        if (!commonPref.contains(SettingValues.Key.SCAN_ALL_CODES)) {
            editor.putBoolean(
                SettingValues.Key.SCAN_ALL_CODES,
                SettingValues.Default.SCAN_ALL_CODES
            )
        }

        val qrRep = "${SettingValues.Key.SCAN}_${BarcodeFormat.QR_CODE.name}"

        if (!commonPref.contains(qrRep)) {
            for (format in BarcodeFormat.values()) {
                val formatSRep = "${SettingValues.Key.SCAN}_${format.name}"

                editor.putBoolean(
                    formatSRep,
                    false
                )
            }

            editor.putBoolean(
                qrRep,
                true
            )
        }


        editor.apply()

        gridType = GridType.values()[commonPref.getInt(
            SettingValues.Key.GRID,
            SettingValues.Default.GRID_TYPE_INDEX
        )]

        mActivity.settingsDialog.updateGridToggleUI()

        commonPref.getString(SettingValues.Key.FOCUS_TIMEOUT, SettingValues.Default.FOCUS_TIMEOUT)
            ?.let {
                mActivity.settingsDialog.updateFocusTimeout(it)
            }

        aspectRatio = commonPref.getInt(
            SettingValues.Key.ASPECT_RATIO,
            SettingValues.Default.ASPECT_RATIO
        )

        includeAudio = commonPref.getBoolean(
            SettingValues.Key.INCLUDE_AUDIO,
            SettingValues.Default.INCLUDE_AUDIO
        )

        enableEIS = commonPref.getBoolean(
            SettingValues.Key.ENABLE_EIS,
            SettingValues.Default.ENABLE_EIS
        )

        allowedFormats.clear()

        for (format in BarcodeFormat.values()) {
            val formatSRep = "${SettingValues.Key.SCAN}_${format.name}"

            val isEnabled = commonPref.getBoolean(
                formatSRep,
                false
            )

            if (isEnabled) {
                if (format !in allowedFormats) {
                    allowedFormats.add(format)
                }

                if (format == BarcodeFormat.QR_CODE) {
                    mActivity.qrToggle.isSelected = true
                }

                if (format == BarcodeFormat.AZTEC) {
                    mActivity.azToggle.isSelected = true
                }

                if (format == BarcodeFormat.PDF_417) {
                    mActivity.cBToggle.isSelected = true
                }

                if (format == BarcodeFormat.DATA_MATRIX) {
                    mActivity.dmToggle.isSelected = true
                }
            }
        }

        qrAnalyzer?.refreshHints()
    }

    var prioritizeCaptureQuality: Boolean
        get() {
            fun read(key: String): Boolean? = if (commonPref.contains(key)) {
                commonPref.getBoolean(key, false)
            } else {
                null
            }
            return PhotoCapturePolicy.preferQuality(
                read(SettingValues.Key.PRIORITIZE_CAPTURE_QUALITY),
                mActivity.resources.getBoolean(R.bool.config_camera_prefer_capture_quality)
            )
        }
        set(value) {
            commonPref.edit {
                putBoolean(SettingValues.Key.PRIORITIZE_CAPTURE_QUALITY, value)
            }
        }

    var selectHighestResolution: Boolean
        get() {
            return commonPref.getBoolean(
                SettingValues.Key.SELECT_HIGHEST_RESOLUTION,
                SettingValues.Default.SELECT_HIGHEST_RESOLUTION
            )
        }
        set(value) {
            commonPref.edit {
                putBoolean(SettingValues.Key.SELECT_HIGHEST_RESOLUTION, value)
            }
        }

    private fun photoResolutionInfo()=try {cameraProvider?.getCameraInfo(cameraSelector)} catch(_:IllegalArgumentException) {null}
    private fun photoResolutionKey():String? = photoResolutionInfo()?.let {
        "photo_resolution_"+Camera2CameraInfo.from(it).cameraId+"_"+aspectRatio
    }
    val canSelectPhotoResolution:Boolean get()=currentMode==CameraMode.CAMERA && !isVideoMode &&
        activePhotoOutputFormat==PhotoOutputFormat.JPEG && !softwarePhoto
    internal fun photoResolutions():List<app.grapheneos.camera.capturer.PhotoResolution> {
        val info=photoResolutionInfo() ?: return emptyList()
        val map=Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        val sizes=(map.getOutputSizes(android.graphics.ImageFormat.JPEG)?.toList().orEmpty()+
            map.getHighResolutionOutputSizes(android.graphics.ImageFormat.JPEG)?.toList().orEmpty())
            .mapNotNull {try {app.grapheneos.camera.capturer.PhotoResolution(it.width,it.height)} catch(_:IllegalArgumentException) {null}}
            .filter { !usesProcessingCapture || app.grapheneos.camera.capturer.ProcessingGeometry.supported(it.width,it.height) }
        return app.grapheneos.camera.capturer.PhotoResolutionPolicy.advertised(sizes,
            if(aspectRatio==AspectRatio.RATIO_16_9)16.0/9 else 4.0/3)
    }
    internal var photoResolution:app.grapheneos.camera.capturer.PhotoResolution?
        get() {
            val key=photoResolutionKey() ?: return null;val stored=commonPref.getString(key,null)
            val sizes=photoResolutions()
            if(stored==null && selectHighestResolution)return sizes.firstOrNull()
            return app.grapheneos.camera.capturer.PhotoResolution.parse(stored)?.takeIf {it in sizes}
        }
        set(value) {
            require(value==null || value in photoResolutions())
            photoResolutionKey()?.let {key->commonPref.edit {putString(key,value?.preference ?: "auto")}}
        }

    fun migrateFromLegacyPhotoQuality() {
        // If emphasis on quality/optimization was previously set by the user
        if (commonPref.contains(SettingValues.Key.EMPHASIS_ON_QUALITY)) {
            // If the photo quality key has not previously been set
            if (!commonPref.contains(SettingValues.Key.PHOTO_QUALITY)) {
                val optimizeForQuality =
                    commonPref.getBoolean(SettingValues.Key.EMPHASIS_ON_QUALITY, false)

                photoQuality = if (optimizeForQuality) {
                    100
                } else {
                    95
                }
            }

            // Remove the key to avoid re-execution of the above code
            commonPref.edit {
                remove(SettingValues.Key.EMPHASIS_ON_QUALITY)
            }
        }

        if (photoQuality == 0) {
            photoQuality = 95;
        }
    }


    fun toggleTorchState() {
        isTorchOn = !isTorchOn
    }

    fun toggleFlashMode() {
        if (isFlashAvailable) {

            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                else -> ImageCapture.FLASH_MODE_OFF
            }

        } else {
            mActivity.showMessage(
                getString(R.string.flash_unavailable_in_selected_mode)
            )
        }
    }

    fun toggleAspectRatio() {
        aspectRatio = if (aspectRatio == AspectRatio.RATIO_16_9) {
            AspectRatio.RATIO_4_3
        } else {
            AspectRatio.RATIO_16_9
        }
        startCamera(true)
    }

    private fun getCurrentCameraInfo() : CameraInfo {
        return cameraProvider!!.getCameraInfo(cameraSelector)
    }

    fun toggleCameraSelector() {

        // Manually switch to the opposite lens facing
        lensFacing =
            if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT
            else
                CameraSelector.LENS_FACING_BACK


        // Test whether the new lens facing is supported by the current device
        // If it is supported then restart the camera with the new configuration
        if (isLensFacingSupported(lensFacing)) {
            startCamera(true)
        } else {
            // Else revert back to the old facing (while displaying an error message
            // to the user)
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                mActivity.showMessage(getString(R.string.rear_camera_unavailable))
                CameraSelector.LENS_FACING_FRONT
            } else {
                mActivity.showMessage(getString(R.string.front_camera_unavailable))
                CameraSelector.LENS_FACING_BACK
            }
        }

    }

    private var initializationInFlight=false
    fun initializeCamera(forced: Boolean = false) {
        if (cameraProvider != null) {
            startCamera(forced = forced)
            return
        }
        if(initializationInFlight) return
        initializationInFlight=true
        val cameraProviderFuture = ProcessCameraProvider.getInstance(mActivity)

        cameraProviderFuture.addListener(fun() {
            initializationInFlight=false
            if(mActivity.isDestroyed || mActivity.isFinishing) return
            val provider: ProcessCameraProvider
            try {
                provider = cameraProviderFuture.get()
            } catch (e: ExecutionException) {
                mActivity.showMessage(mActivity.getString(R.string.camera_provider_init_failure))
                return
            }

            if (provider !== probedCameraProvider) {
                // A different provider instance means the camera stack was reinitialized:
                // extension verdicts probed through the previous instance (including bind-time
                // blacklists, see startCamera) describe vendor state that no longer exists.
                extensionUsability.clear()
                slowMotionRateCache.clear()
                probedCameraProvider = provider
            }
            cameraProvider = provider
            rawCatalogCache.clear()
            wideScaleCache = null

            // Manually switch to the other lens facing (if the default lens facing isn't
            // supported for the current device)
            if (!isLensFacingSupported(lensFacing)) {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
            }

            // Ordinary photo/video do not depend on vendor extensions. Open the
            // camera now, while extension discovery finishes in the background.
            if(currentMode.extensionMode==ExtensionMode.NONE) startCamera(forced=forced)

            // Despite the name, getInstanceAsync() runs its one-time initialization body
            // synchronously on the calling thread, and that body asks the vendor's extensions
            // proxy service for each camera's advertised extensions until it finds one that has
            // any -- measured at a handful of binder round trips on the main thread during
            // startup on a Pixel 7 Pro. Call it from a short-lived thread instead; only the
            // listener has to run on the main thread, for the field write and startCamera().
            thread {
                try {
                    val extensionsManagerFuture = ExtensionsManager.getInstanceAsync(mActivity, provider)

                    extensionsManagerFuture.addListener({
                        try {
                            extensionsManager = extensionsManagerFuture.get()
                        } catch (e: ExecutionException) {
                            mActivity.showMessage(mActivity.getString(R.string.extensions_manager_init_failure))
                        }
                        if(mActivity.isDestroyed || mActivity.isFinishing || !mActivity.isStarted) return@addListener
                        if(currentMode.extensionMode==ExtensionMode.NONE && camera!=null) loadTabs()
                        else startCamera(forced = forced)
                    }, ContextCompat.getMainExecutor(mActivity))
                } catch (e: Exception) {
                    // getInstanceAsync() runs its initialization synchronously (see above), so it
                    // -- or addListener -- can throw right here on this background thread, where an
                    // escaping exception would crash the process and, worse, leave the camera never
                    // started because the listener never runs. Recover exactly as the future-failure
                    // path does: report it and start the camera without extensions, on the main
                    // thread.
                    Log.e(TAG, "Extensions manager initialization failed", e)
                    ContextCompat.getMainExecutor(mActivity).execute {
                        mActivity.showMessage(mActivity.getString(R.string.extensions_manager_init_failure))
                        startCamera(forced = forced)
                    }
                }
            }

        }, ContextCompat.getMainExecutor(mActivity))
    }

    // ExtensionsManager.isExtensionAvailable() answers from CameraExtensionCharacteristics'
    // static advertisement data. Actually *binding* an advertised extension additionally makes
    // CameraX initialize a Camera2ExtensionsVendorExtender, which calls into the vendor's
    // advanced extender over binder. Some vendors advertise a mode there and then throw from
    // that init - Pixels raise "Framework size list map not supported in pixel path"
    // - which used to kill the process from inside bindToLifecycle(). CameraX 1.6 removed the
    // legacy OEM extender path, so there is no longer a working fallback on those devices and the
    // only safe option is to stop offering the mode.
    //
    // getCameraInfo() performs exactly the same vendor init that bindToLifecycle() does, so it is
    // a faithful probe. Verdicts are cached (extensionUsability) because every step of the probe
    // -- including the availability query, see probeExtension -- costs a binder round trip. A
    // negative verdict is only cached when the failure is known to be persistent: caching a
    // transient probe failure would make the mode's tab vanish for the rest of the process
    // lifetime over a condition that clears seconds later.
    //
    // The cache is read and written on the main thread only, which is also what keeps the two
    // activities that can share it (a secure session over a running one) from racing each other.
    // probeExtension() itself also runs on the probe thread that loadTabs() spawns, but its
    // results come back through the main executor.
    //
    // true/false is a verdict that is safe to cache; null is a failure that may be transient
    // and must not be (see extensionUsability above). The provider and manager are parameters
    // rather than the fields because this also runs off the main thread, where the fields could
    // be swapped out mid-probe.
    private fun probeExtension(
        provider: ProcessCameraProvider,
        em: ExtensionsManager,
        selector: CameraSelector,
        extensionMode: Int,
    ): Boolean? {
        // What the vendor advertises is as static as the vendor init verdict below, so a
        // negative answer is cached the same way -- notably, isExtensionAvailable() is *not*
        // the cheap in-process lookup it appears to be: on Android 17 every call costs a round
        // trip to the vendor's extensions proxy service, which is exactly the kind of work this
        // probe exists to keep off the main thread (see loadTabs). It sits inside the try below so
        // that a transient failure of that round trip is treated like any other -- returning null
        // to retry later -- rather than propagating out: on the background probe thread an escaping
        // exception would strand the round with extensionProbesInFlight still set, blocking every
        // later tab refresh.
        return try {
            when {
                !em.isExtensionAvailable(selector, extensionMode) -> false
                else -> {
                    provider.getCameraInfo(
                        em.getExtensionEnabledCameraSelector(selector, extensionMode)
                    )
                    true
                }
            }
        } catch (e: UnsupportedOperationException) {
            // The signature of a vendor extender that advertises the mode and then throws from
            // its own init (Pixels: "Framework size list map not supported in pixel path").
            // Nothing about it changes within a process lifetime, so this verdict is safe to
            // remember.
            Log.w(TAG, "Extension mode $extensionMode is advertised but unusable here", e)
            false
        } catch (e: Exception) {
            // Anything else may be transient — the camera service restarting, the camera briefly
            // held by another process. Fail this probe but leave the cache alone so the mode is
            // offered again once the underlying condition clears.
            Log.w(TAG, "Probing extension mode $extensionMode failed, will retry later", e)
            null
        }
    }

    private fun isExtensionUsable(
        selector: CameraSelector,
        lensFacing: Int,
        extensionMode: Int,
        probeOnMiss: Boolean = true,
    ): Boolean {
        if (extensionMode == ExtensionMode.NONE) return true

        val em = extensionsManager ?: return false
        val provider = cameraProvider ?: return false

        val key = lensFacing to extensionMode
        extensionUsability[key]?.let { return it }

        if (!probeOnMiss) return false

        // A background probe round (loadTabs) may already be asking the vendor about this very
        // key. Probing inline here would run a second synchronous vendor round trip on the main
        // thread and race that round's verdict write. While a round is in flight, treat the miss
        // as "unusable for now"; the round fills the cache and the next rebind/tab refresh picks
        // up the real verdict. The inline probe stays for the not-in-flight cold case, where it is
        // the only path to a verdict.
        if (extensionProbesInFlight) return false

        val verdict = probeExtension(provider, em, selector, extensionMode) ?: return false
        extensionUsability[key] = verdict
        return verdict
    }

    // Whether CameraX's feature-group resolution can actually *verify* feature combinations on
    // the current camera. The resolver (DefaultFeatureGroupResolver) keeps a candidate
    // combination only when the platform confirms it, and it treats "could not check" exactly
    // like "verified unsupported": below API 35 CameraX substitutes a no-op feature-combination
    // query whose isSupported() is unconditionally false (CameraSurfaceAdapter), and on API 35+
    // a HAL that does not implement the session-configuration query answers UNKNOWN, which is
    // folded into false as well (camera-pipe ConfigQueryResult). On such a camera, combinations
    // the device records fine every day resolve down to little or nothing: the stored video
    // quality is silently ignored (setQualitySelector must not be called while a feature group
    // is in use, see startCamera) and the divergence notice asserts unsupportedness that was
    // never actually verified. So the feature-group path is only taken when the platform can
    // genuinely answer, and startCamera otherwise falls back to the pre-1.6 configuration APIs,
    // which never drop the chosen quality.
    //
    // The per-camera INFO_SESSION_CONFIGURATION_QUERY_VERSION characteristic is what the
    // platform's answers ultimately hinge on (CameraDeviceSetup exists only for cameras
    // reporting >= 35 there), so it is checked in addition to the API level. This deliberately
    // mirrors CameraX's own SDK_INT >= 35 gate — which upstream may still move, see b/417839748
    // — plus the HAL capability that gate cannot see; re-check both on CameraX updates.
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun canVerifyFeatureCombinations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return false

        val cameraInfo = try {
            cameraProvider?.getCameraInfo(cameraSelector) ?: return false
        } catch (exception: IllegalArgumentException) {
            Log.w(TAG, "Unable to resolve camera info for feature combination gate", exception)
            return false
        }

        val queryVersion = try {
            Camera2CameraInfo.from(cameraInfo).getCameraCharacteristic(
                CameraCharacteristics.INFO_SESSION_CONFIGURATION_QUERY_VERSION
            )
        } catch (exception: IllegalArgumentException) {
            Log.w(TAG, "Camera info carries no camera2 characteristics", exception)
            null
        }

        return (queryVersion ?: 0) >= Build.VERSION_CODES.VANILLA_ICE_CREAM
    }

    // The stored video quality was chosen on the camera that the lens facing had at the time,
    // which is not necessarily the current one. Asking for a quality that the current camera
    // doesn't offer fails the whole bind, so the default takes its place there. The stored choice
    // is left alone.
    private fun supportedVideoQuality(): Quality {
        val quality = videoQuality

        val cameraInfo = try {
            cameraProvider?.getCameraInfo(cameraSelector) ?: return quality
        } catch (exception: IllegalArgumentException) {
            Log.w(TAG, "Unable to resolve camera info for quality lookup", exception)
            return quality
        }

        if (currentMode == CameraMode.SLOW_MOTION) {
            return app.grapheneos.camera.capturer.SlowMotionQualityPolicy.resolve(
                quality.takeUnless { it == Quality.HIGHEST }, eligibleSlowMotionQualities(cameraInfo)) ?: Quality.HIGHEST
        }
        if (quality == Quality.HIGHEST) return quality

        val supportedQualities = Recorder.getVideoCapabilities(cameraInfo)
            .getSupportedQualities(DynamicRange.SDR)

        return if (supportedQualities.isEmpty() || quality in supportedQualities) {
            quality
        } else {
            Log.w(TAG, "Video quality $quality isn't offered by this camera, using the default")
            SettingValues.Default.VIDEO_QUALITY
        }
    }

    // Maps the user-chosen video quality to the equivalent groupable feature, for use when a
    // feature group is passed to SessionConfig (see startCamera). Quality.HIGHEST has no
    // groupable equivalent and is resolved to the highest quality the current camera supports,
    // mirroring what QualitySelector.from(Quality.HIGHEST) would have selected.
    private fun videoQualityAsGroupableFeature(): GroupableFeature? {
        val supportedQuality = supportedVideoQuality()
        val quality = if (supportedQuality == Quality.HIGHEST) {
            val cameraInfo = try {
                cameraProvider?.getCameraInfo(cameraSelector) ?: return null
            } catch (exception: IllegalArgumentException) {
                Log.w(TAG, "Unable to resolve camera info for quality lookup", exception)
                return null
            }
            Recorder.getVideoCapabilities(cameraInfo)
                .getSupportedQualities(DynamicRange.SDR)
                .firstOrNull() ?: return null
        } else {
            supportedQuality
        }

        return when (quality) {
            Quality.UHD -> GroupableFeatures.UHD_RECORDING
            Quality.FHD -> GroupableFeatures.FHD_RECORDING
            Quality.HD -> GroupableFeatures.HD_RECORDING
            Quality.SD -> GroupableFeatures.SD_RECORDING
            else -> {
                // Not fatal: startCamera() then requests no quality feature at all and the
                // quality is left to Recorder's default selector. Worth a log because it means
                // the user's explicit choice is silently not being asked for.
                Log.w(TAG, "No groupable feature equivalent for video quality $quality")
                null
            }
        }
    }

    // A saved frame rate belongs to one camera and resolved recording quality.
    private fun videoFrameRateKey(cameraInfo: CameraInfo): Pair<String, Quality?> {
        val qualities = if (currentMode == CameraMode.SLOW_MOTION) eligibleSlowMotionQualities(cameraInfo)
            else Recorder.getVideoCapabilities(cameraInfo).getSupportedQualities(DynamicRange.SDR)
        val requestedQuality = supportedVideoQuality()
        val quality = if (requestedQuality == Quality.HIGHEST) qualities.firstOrNull() else requestedQuality
        val cameraId = Camera2CameraInfo.from(cameraInfo).cameraId
        return "${if(currentMode==CameraMode.SLOW_MOTION) "slow_motion_frame_rate" else "video_frame_rate"}/$cameraId/${videoQualityPreference(quality ?: Quality.HIGHEST)}" to quality
    }

    // Fixed high-speed rates (120 fps and up) the camera offers at this quality, recorded as slow
    // motion. Cameras without a high-speed profile for the quality offer none.
    private val slowMotionRateCache = java.util.concurrent.ConcurrentHashMap<Pair<String,Quality>,List<VideoFrameRate>>()

    internal fun eligibleSlowMotionQualities(cameraInfo: CameraInfo): List<Quality> =
        app.grapheneos.camera.capturer.SlowMotionQualityPolicy.eligible(
            Recorder.getHighSpeedVideoCapabilities(cameraInfo)?.getSupportedQualities(DynamicRange.SDR).orEmpty()
        ) { slowMotionFrameRates(cameraInfo,it).isNotEmpty() }

    private fun slowMotionFrameRates(cameraInfo: CameraInfo, quality: Quality): List<VideoFrameRate> {
        val key = Camera2CameraInfo.from(cameraInfo).cameraId to quality
        slowMotionRateCache[key]?.let { return it }
        val rates = querySlowMotionFrameRates(cameraInfo,quality)
        return slowMotionRateCache.putIfAbsent(key,rates) ?: rates
    }

    // The saved rate when it is a slow-motion rate this camera and quality still offer.
    private fun savedSlowMotionRate(): VideoFrameRate? {
        if(currentMode!=CameraMode.SLOW_MOTION) return null
        val cameraInfo = try {
            cameraProvider?.getCameraInfo(cameraSelector) ?: return null
        } catch (exception: IllegalArgumentException) {
            return null
        }
        val (key, quality) = videoFrameRateKey(cameraInfo)
        if(quality==null) return null
        val rates=slowMotionFrameRates(cameraInfo,quality)
        val saved=VideoFrameRate.fromPreference(commonPref.getString(key,null))
        return saved?.takeIf {it in rates} ?: rates.firstOrNull()
    }

    // Slow motion has its own mode and binds only video/preview in a constrained
    // high-speed session, encoded for slowed playback.
    private fun slowMotionSessionConfig(rate: VideoFrameRate): SessionConfig {
        val cameraInfo = cameraProvider!!.getCameraInfo(cameraSelector)
        val (key, quality) = videoFrameRateKey(cameraInfo)
        videoFrameRatePreferenceKey = key
        // The former combined Video menu queried ordinary recording rates here.
        // That result is unused now; avoid another recorder and capability probe
        // on every SLO-MO bind.
        slowMotionVideoFrameRates = slowMotionFrameRates(cameraInfo, requireNotNull(quality))
        availableVideoFrameRates = slowMotionVideoFrameRates
        selectedVideoFrameRate = rate
        lastUnavailableFrameRate = null
        return HighSpeedVideoSessionConfig(
            videoCapture!!, preview, Range(rate.lower, rate.upper), isSlowMotionEnabled = true
        )
    }

    /** Query the actual recording resolution/stream combination, never the camera-wide AE list. */
    private fun videoSessionConfig(
        useCases: List<UseCase>,
        preferredFeatures: List<GroupableFeature>,
        qualityFeature: GroupableFeature?,
    ): SessionConfig {
        val cameraInfo = cameraProvider!!.getCameraInfo(cameraSelector)
        val (key, quality) = videoFrameRateKey(cameraInfo)
        videoFrameRatePreferenceKey = key

        val baseFeatures = if (preferredFeatures.isNotEmpty() && qualityFeature != null) {
            setOf(qualityFeature)
        } else {
            emptySet()
        }
        val featureSets = if (preferredFeatures.isNotEmpty()) {
            listOf(baseFeatures + GroupableFeature.PREVIEW_STABILIZATION,
                baseFeatures + GroupableFeatures.VIDEO_STABILIZATION, baseFeatures)
        } else {
            listOf(emptySet())
        }
        // A still-photo stream must not hide an otherwise supported recording FPS. Keep snapshots
        // when compatible. The explicit resolution/FPS and then stabilization take precedence.
        val withoutSnapshot = useCases.filterNot { it is ImageCapture }
        val streamSets = listOf(useCases, withoutSnapshot).distinct()
        val profiles = arrayListOf<VideoSessionProfile>()
        for (features in featureSets) {
            for (streams in streamSets) {
                val probe = SessionConfig(useCases = streams, requiredFeatureGroup = features)
                try {
                    if (features.isNotEmpty() && !cameraInfo.isSessionConfigSupported(probe)) continue
                    val ranges = cameraInfo.getSupportedFrameRateRanges(probe)
                        .filter { it.lower > 0 && it.upper >= it.lower }
                        .map { VideoFrameRate(it.lower, it.upper) }.toSet()
                    if (ranges.isNotEmpty()) profiles.add(VideoSessionProfile(streams, features, ranges))
                } catch (exception: IllegalArgumentException) {
                    Log.w(TAG, "Recording session cannot be queried", exception)
                } catch (exception: UnsupportedOperationException) {
                    Log.w(TAG, "Recording FPS query is unavailable", exception)
                }
            }
        }

        val regular = VideoFrameRate.supportedOptions(profiles.map { it.frameRates })
        slowMotionVideoFrameRates = quality?.let { slowMotionFrameRates(cameraInfo, it) } ?: emptyList()
        availableVideoFrameRates = regular
        val stored = commonPref.getString(key, null)
        val requested = VideoFrameRate.fromPreference(stored)
        selectedVideoFrameRate = requested?.takeIf { it in regular }
        if (stored != null && selectedVideoFrameRate == null) {
            val unavailable = key to stored
            if (lastUnavailableFrameRate != unavailable) {
                mActivity.showMessage(R.string.video_frame_rate_unavailable)
                lastUnavailableFrameRate = unavailable
            }
        } else {
            lastUnavailableFrameRate = null
        }

        val index = VideoFrameRate.selectProfile(profiles.map { it.frameRates }, selectedVideoFrameRate)
        if (index == null) {
            // Older/limited providers may be unable to answer the constrained query. Auto keeps
            // the existing binding path. No unverified FPS choices are exposed or requested.
            return SessionConfig(useCases = useCases, preferredFeatureGroup = preferredFeatures)
        }
        val profile = profiles[index]
        val snapshot = imageCapture
        if (snapshot != null && snapshot !in profile.useCases) imageCapture = null
        if (preferredFeatures.isNotEmpty()) {
            onFeaturesSelected(lensFacing, preferredFeatures, qualityFeature, profile.features)
        }
        val automaticTarget=mActivity.resources.getInteger(R.integer.config_camera_default_preview_fps)
        val effectiveFrameRate=selectedVideoFrameRate ?: profile.frameRates.firstOrNull {
            automaticTarget in 1..60 && it.lower==automaticTarget && it.upper==automaticTarget
        }
        return effectiveFrameRate?.let {
            SessionConfig(useCases = profile.useCases, requiredFeatureGroup = profile.features,
                frameRateRange = Range(it.lower, it.upper))
        } ?: SessionConfig(useCases = profile.useCases, requiredFeatureGroup = profile.features)
    }

    private fun photoSessionConfig(useCases:List<UseCase>,features:List<GroupableFeature>,allowPreference:Boolean):SessionConfig {
        val automatic=SessionConfig(useCases=useCases,preferredFeatureGroup=features)
        val target=mActivity.resources.getInteger(R.integer.config_camera_default_preview_fps)
        if(!allowPreference || target !in 1..60)return automatic
        val info=cameraProvider?.getCameraInfo(cameraSelector) ?: return automatic
        return try {
            val supported=info.getSupportedFrameRateRanges(automatic).any {it.lower==target && it.upper==target}
            val fixed=if(supported)SessionConfig(useCases=useCases,preferredFeatureGroup=features,frameRateRange=Range(target,target)) else null
            if(fixed!=null && info.isSessionConfigSupported(fixed))fixed else automatic
        } catch(_:IllegalArgumentException) {automatic} catch(_:UnsupportedOperationException) {automatic}
    }

    // The quality labels shown in the settings spinner, so that a message about a quality can
    // name it exactly the way the user picked it (see SettingsDialog.getTitleFor).
    private fun describeQualityFeature(feature: GroupableFeature): String? = when (feature) {
        GroupableFeatures.UHD_RECORDING -> "2160p (UHD)"
        GroupableFeatures.FHD_RECORDING -> "1080p (FHD)"
        GroupableFeatures.HD_RECORDING -> "720p (HD)"
        GroupableFeatures.SD_RECORDING -> "480p (SD)"
        else -> null
    }

    // Avoids repeating an unchanged notice: startCamera() runs again on every tab switch,
    // settings change, camera flip and resume, and the outcome is usually the same each time.
    // Keyed by lens facing so that alternating between a fully-supported camera and a limited
    // one doesn't re-announce the limited camera's unchanged hardware fact on every flip: a
    // fully-satisfied bind clears only its own camera's entry, so the next divergence on that
    // camera is genuinely new information while the other camera's stays remembered.
    private val lastReportedDivergence = HashMap<Int, String>()

    // CameraX resolves a preferred feature group by dropping features until what is left is a
    // combination the camera actually supports, and it does so silently. This app only ever asks
    // for the video quality and the stabilization that the user selected, so a dropped feature
    // means a setting the UI still displays is not in effect. Say so rather than letting the
    // recording quietly disagree with the settings screen.
    //
    // What was asked for -- including which camera it was asked of -- is passed in rather than
    // read back from a field: the callback is delivered asynchronously, so a field could already
    // describe a later bind by the time this runs, and the message would then name settings (or
    // dedup against a camera) that this result never involved.
    private fun onFeaturesSelected(
        boundLensFacing: Int,
        requested: List<GroupableFeature>,
        qualityFeature: GroupableFeature?,
        selected: Set<GroupableFeature>,
    ) {
        // The full request-vs-result picture (including which stabilization feature, if any,
        // survived) is only ever logged, never shown: the lead wants EIS left silently in its
        // known state -- 4K keeps priority and stabilization is given up without a notice.
        Log.i(TAG, "Requested $requested but got $selected")

        val qualityLabel = qualityFeature?.let { describeQualityFeature(it) }
        // Only report a dropped quality that can be named: a message that can't say which
        // quality it means would be worse than the log line above.
        val droppedQuality = qualityLabel?.takeIf { qualityFeature !in selected }

        // A dropped quality is the only outcome worth a toast, and it is a genuine one: CameraX
        // tries the quality on its own before it tries either stabilization, and the preflight in
        // startCamera already gave up in-video snapshots wherever that would let the quality bind,
        // so a quality reported dropped here is one this camera cannot record at in the minimal
        // configuration either. Stabilization losses are deliberately not surfaced -- besides the
        // lead's no-EIS-messaging wish, a "stabilization unsupported" message would misattribute
        // the loss, because stabilization can be crowded out by the in-video snapshot stream
        // rather than by the quality.
        val message = droppedQuality?.let {
            mActivity.getString(R.string.quality_unsupported, it)
        }

        if (message == null) {
            lastReportedDivergence.remove(boundLensFacing)
            return
        }

        if (message != lastReportedDivergence[boundLensFacing]) {
            lastReportedDivergence[boundLensFacing] = message
            mActivity.showMessage(message)
        }
    }

    // Outside the extension modes the rear camera is the one that can zoom out to the wider
    // lenses, if there is one. The extension modes stay on the default camera of a lens facing,
    // which is commonly the only one that vendors offer their extensions for.
    private fun createCameraSelector(lensFacing: Int): CameraSelector {
        physicalMainBound = false
        physicalWideBound = false
        photoLensScale = 1f
        boundRawLensLabel = null
        if (usesPhysicalModeLens && lensFacing == this.lensFacing) selectedRawLens()?.let {
            boundRawLensLabel = it.label
            photoLensScale = lensDisplayScale(it)
            return it.selector
        }
        if (prefersPhysicalMain) {
            photoWideMinimum = wideDisplayScale()
            val main = rawLenses().firstOrNull { it.label == R.string.lens_main }
            physicalMainBound = requestedPhotoZoom >= 1f && main != null
            if (physicalMainBound) return main!!.selector
            if (requestedPhotoZoom < 1f && enhancedPhotoAvailable) {
                rawLenses().firstOrNull { it.label == R.string.lens_ultra_wide && supportsNight(it.info) }?.let { wide ->
                    physicalWideBound = true
                    photoLensScale = photoWideMinimum
                    return wide.selector
                }
            }
        }
        val builder = CameraSelector.Builder().requireLensFacing(lensFacing)

        if (lensFacing == CameraSelector.LENS_FACING_BACK &&
            currentMode.extensionMode == ExtensionMode.NONE && currentMode != CameraMode.SLOW_MOTION
        ) {
            builder.addCameraFilter(ZOOM_OUT_CAMERA_FILTER)
        }

        return builder.build()
    }

    private fun isLensFacingSupported(lensFacing : Int) : Boolean {
        if(currentMode==CameraMode.SLOW_MOTION) return lensFacing in slowMotionDefaultQualities
        if (currentMode == CameraMode.NIGHT && !vendorModeAvailable(CameraMode.NIGHT, lensFacing)) return RawLensCatalog.lenses(
            cameraProvider?.availableCameraInfos ?: emptyList(), lensFacing).any { supportsNight(it.info) }
        if (currentMode == CameraMode.PORTRAIT && !vendorModeAvailable(CameraMode.PORTRAIT, lensFacing)) {
            return cameraProvider?.hasCamera(CameraSelector.Builder().requireLensFacing(lensFacing).build()) ?: false
        }
        var tCameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        if (currentMode.extensionMode != ExtensionMode.NONE) {
            extensionsManager?.let { em ->
                if (!isExtensionUsable(tCameraSelector, lensFacing, currentMode.extensionMode))
                    return false

                try {
                    tCameraSelector = em.getExtensionEnabledCameraSelector(tCameraSelector, currentMode.extensionMode)
                } catch (e : IllegalArgumentException) {
                    return false
                }
            }
        }

        return cameraProvider?.hasCamera(tCameraSelector) ?: false
    }

    // Start the camera with latest hard configuration
    @Volatile var directBurstActive = false
        private set
    private var directBurstOwner: Any? = null
    internal fun beginDirectBurst(owner: Any) {
        check(!directBurstActive);directBurstOwner=owner;directBurstActive=true
    }
    internal fun ownsDirectBurst(owner: Any)=directBurstOwner===owner
    internal fun finishDirectBurst(owner: Any): Boolean {
        if(!ownsDirectBurst(owner))return false
        directBurstOwner=null;directBurstActive=false;return true
    }
    private var previewRecoveryGeneration=0L

    private val blockRecovery = CameraBlockRecovery()
    private var cameraStateSource: LiveData<CameraState>? = null
    private val cameraStateObserver = Observer<CameraState> { state ->
        val type = when (state.type) {
            CameraState.Type.OPEN -> CameraBlockRecovery.State.OPEN
            CameraState.Type.OPENING, CameraState.Type.PENDING_OPEN -> CameraBlockRecovery.State.OPENING
            else -> CameraBlockRecovery.State.CLOSED
        }
        blockRecovery.onCameraState(type, state.error?.code == CameraState.ERROR_CAMERA_DISABLED, cameraAccessAllowed())
        scheduleBlockRebind()
    }

    /** Android's camera block (sensor privacy) restricts the camera app op, so a check sees it. */
    private fun cameraAccessAllowed(): Boolean {
        val appOps = mActivity.getSystemService(AppOpsManager::class.java) ?: return true
        return appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_CAMERA, Process.myUid(), mActivity.packageName) !=
            AppOpsManager.MODE_IGNORED
    }

    /** The camera app op may have changed. Main thread only. */
    fun onCameraAccessChanged() {
        blockRecovery.onAccess(cameraAccessAllowed())
        scheduleBlockRebind()
    }

    fun resetBlockRecovery() {
        blockRecovery.reset()
        blockRecovery.onAccess(cameraAccessAllowed())
    }

    // Posted, as a LiveData observer can run inside startCamera().
    private fun scheduleBlockRebind() {
        if (blockRecovery.pending) mActivity.previewView.post { retryBlockRebind() }
    }

    /** A capture, recording or review screen defers the rebind. The preview watchdog retries it. */
    fun retryBlockRebind() {
        if (!blockRecovery.pending || mActivity.isDestroyed || mActivity.isFinishing || !mActivity.isStarted ||
            !mActivity.previewRebindAllowed) return
        val busy = mActivity.imageCapturer.isTakingPicture || mActivity.videoCapturer.isRecording || directBurstActive
        if (!blockRecovery.rebindNow(busy)) return
        if (!cameraAccessAllowed()) {
            blockRecovery.onAccess(false)
            return
        }
        Log.i(TAG, "Camera access is back, rebinding")
        startCamera(forced = true)
    }

    /** A stalled session must close before rebinding, rather than immediately reusing its graph. */
    fun recoverStalledPreview() {
        if(!mActivity.isStarted || mActivity.imageCapturer.isTakingPicture ||
            mActivity.videoCapturer.isRecording || directBurstActive) return
        val old=camera ?: return
        val generation=++previewRecoveryGeneration
        val deadline=android.os.SystemClock.elapsedRealtime()+3000
        mActivity.updateLastFrame();captureResults.clear();cameraProvider?.unbindAll();camera=null
        fun awaitClosed() {
            if(generation!=previewRecoveryGeneration || !mActivity.isStarted || mActivity.isDestroyed || mActivity.isFinishing) return
            if(old.cameraInfo.cameraState.value?.type==androidx.camera.core.CameraState.Type.CLOSED || android.os.SystemClock.elapsedRealtime()>=deadline) {
                startCamera(forced=true)
            } else mActivity.previewView.postDelayed({awaitClosed()},50)
        }
        awaitClosed()
    }

    @SuppressLint("RestrictedApi")
    fun startCamera(forced: Boolean = false, preserveCapture: Boolean = false) {
        if(mActivity.isDestroyed || mActivity.isFinishing || !mActivity.isStarted) return
        if (directBurstActive) {
            if (!preserveCapture) mActivity.imageCapturer.cancelAcquiringCapture()
            return
        }
        if ((!forced && camera != null) || cameraProvider == null) return
        previewRecoveryGeneration++
        blockRecovery.onRebind()

        // Cancel any pending capture requests
        if (!preserveCapture) mActivity.imageCapturer.cancelAcquiringCapture()
        captureResults.clear()
        nightDetector.reset(clearDismissal = false)
        mActivity.updateNightSuggestion(false)
        portraitSubjectX = .5f
        portraitSubjectY = .5f
        nightAvailable = rawLenses().any { supportsNight(it.info) }
        availableVideoFrameRates = emptyList()
        selectedVideoFrameRate = null
        slowMotionVideoFrameRates = emptyList()
        videoFrameRatePreferenceKey = null

        mActivity.exposureBar.hidePanel()
        modePref = mActivity.getSharedPreferences(currentMode.name, Context.MODE_PRIVATE)

        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val display = mActivity.display
            display?.rotation ?: @Suppress("DEPRECATION")
            mActivity.windowManager.defaultDisplay.rotation
        } else {
            // We don't really have any option here, but this initialization
            // ensures that the app doesn't break later when the below
            // deprecated option gets removed post Android R
            @Suppress("DEPRECATION")
            mActivity.windowManager.defaultDisplay.rotation
        }

        if (mActivity.isDestroyed || mActivity.isFinishing) return

        // Test whether the current lens facing is supported by the current device
        // If not then silently switch to the other lens facing
        // (Snackbar/popup message can be shown before startCamera is called
        // in specific cases of explicitly switching to another side or if
        // the camera is expected)
        if (!isLensFacingSupported(lensFacing)) {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
        }


        cameraSelector = createCameraSelector(lensFacing)

        if (currentMode == CameraMode.SLOW_MOTION &&
            eligibleSlowMotionQualities(cameraProvider!!.getCameraInfo(cameraSelector)).isEmpty()) {
            mActivity.showMessage(R.string.slow_motion_unavailable)
            switchMode(CameraMode.VIDEO)
            return
        }

        val builder = ImageCapture.Builder()

        // To use the last frame instead of showing a blank screen when
        // the camera that is being currently used gets unbind
        mActivity.updateLastFrame()
        mActivity.showLastFrame()

        // Unbind/close all other camera(s) [if any]
        mActivity.previewHold.disarm()
        cameraProvider?.unbindAll()
        closePreviewProcessing()

        val extMode = if (softwareNight || softwarePortrait) ExtensionMode.NONE else currentMode.extensionMode
        var appliedExtension: Pair<Int, Int>? = null
        if (extMode != ExtensionMode.NONE) {
            val em = extensionsManager
            if (em != null && isExtensionUsable(cameraSelector, lensFacing, extMode)) {
                appliedExtension = lensFacing to extMode
                cameraSelector = em.getExtensionEnabledCameraSelector(cameraSelector, extMode)
            } else {
                Log.e(TAG, "Mode $currentMode isn't available for this device")
            }
        }

        supportedPhotoOutputFormats = if (currentMode == CameraMode.CAMERA &&
            mActivity !is CaptureActivity && !isVideoMode && rawLenses().isNotEmpty()) {
            PhotoOutputFormat.supported(ImageCapture.getImageCaptureCapabilities(
                cameraProvider!!.getCameraInfo(cameraSelector)).supportedOutputFormats)
        } else listOf(PhotoOutputFormat.JPEG)
        activePhotoOutputFormat = PhotoOutputFormat.effective(photoOutputFormat,
            supportedPhotoOutputFormats, currentMode != CameraMode.CAMERA || isVideoMode)
        Camera2Interop.Extender(builder).setSessionCaptureCallback(captureResults.forSession())
        var qualifiedJpegQuality=false
        if ((!softwareNight || softwareJpegNight) && (!softwarePhoto || softwareJpegBurst) && !isVideoMode &&
            mActivity.resources.getBoolean(R.bool.config_camera_jpeg_high_quality_noise_reduction)) {
            val info = Camera2CameraInfo.from(cameraProvider!!.getCameraInfo(cameraSelector))
            // Photo bursts take FAST frames for cadence. Night frames keep HIGH_QUALITY: on some
            // HALs FAST means no noise reduction at all, which the merge cannot make up for.
            val mode = if (softwareJpegBurst && !softwareJpegNight) android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_FAST
                else android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            if (info.getCameraCharacteristic(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.contains(mode) == true) {
                qualifiedJpegQuality=mode == android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                Camera2Interop.Extender(builder).setCaptureRequestOption(android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE,
                    mode)
            }
        }
        // Direct bursts acquire RAW from their own exclusive Camera2 session. Keep
        // CameraX's framing graph JPEG in that case: CameraX rejects CameraEffect
        // even on PREVIEW when its ImageCapture is RAW. The user's RAW/DNG output
        // still uses the original RAW graph when no software burst is selected.
        val cameraXRawBurst = (softwareNight || softwarePhoto) && !softwareJpegBurst &&
            !mActivity.resources.getBoolean(R.bool.config_camera_direct_raw_burst)
        builder.setOutputFormat(if (cameraXRawBurst) ImageCapture.OUTPUT_FORMAT_RAW
            else if (softwareNight || softwarePhoto) ImageCapture.OUTPUT_FORMAT_JPEG
            else PhotoOutputFormat.bindingFormat(activePhotoOutputFormat, splitsRawJpeg, splitRawPhase))

        val useCasesList = arrayListOf<UseCase>()

        val aspectRatioStrategy = AspectRatioStrategy(
            aspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO
        )

        // CameraX 1.6.0's SessionConfig throws an IllegalArgumentException at construction time
        // if any use case configures a groupable feature through a non-groupable API while a
        // feature group is in use. Recorder.Builder.setQualitySelector() is such an API since
        // 1.6.0 introduced GroupableFeatures.*_RECORDING, so when EIS is requested through
        // GroupableFeature.PREVIEW_STABILIZATION (the only case where this app uses a feature
        // group), the video quality has to be requested through the feature group as well.
        //
        // The validation triggers on setQualitySelector() having been called at all, not on the
        // quality it was given, so this flag -- not the resolved feature below -- is what decides
        // whether that setter may be used. videoQualityAsGroupableFeature() can legitimately fail
        // to map the current quality, and falling back to setQualitySelector() in that case would
        // reintroduce the very exception this works around.
        //
        // Both halves of canApplyVideoStabilization() gate the group. The platform has to be able
        // to verify feature combinations: where it cannot, the resolver would conflate "could not
        // check" with "unsupported", quietly discard the stored video quality and produce
        // untruthful notices. Cameras behind that gate use the pre-1.6 non-groupable setters
        // below instead, which are legal exactly because no feature group is in use then. And the
        // group exists solely to negotiate stabilization against the quality, so a camera that
        // supports no stabilization at all has nothing to negotiate: it takes the plain fallback
        // path directly, which produces the identical output without a needless feature-group
        // round.
        // Slow motion is a constrained high-speed session: no stabilization, snapshots or mirroring.
        val slowMotion = if (isVideoMode && appliedExtension == null) savedSlowMotionRate() else null
        if (currentMode == CameraMode.SLOW_MOTION && slowMotion == null) {
            mActivity.showMessage(R.string.slow_motion_unavailable)
            switchMode(CameraMode.VIDEO)
            return
        }
        val usesFeatureGroup = isVideoMode && slowMotion == null && enableEIS && canApplyVideoStabilization()
        val videoQualityFeature: GroupableFeature? = when {
            usesFeatureGroup -> videoQualityAsGroupableFeature()
            else -> null
        }

        if (isQRMode) {
            val analyzer = QRAnalyzer(mActivity)
            val strategy = ResolutionStrategy(
                Size(960, 960),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
            )
            val mIAnalyzer = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder().setResolutionStrategy(strategy).build())
                .setOutputImageRotationEnabled(true)
                .build()
            qrAnalyzer = analyzer
            mActivity.startFocusTimer()
            iAnalyzer = mIAnalyzer
            mIAnalyzer.setAnalyzer(cameraExecutor, analyzer)
            cameraSelector = createCameraSelector(
                if (isLensFacingSupported(CameraSelector.LENS_FACING_BACK)) {
                    CameraSelector.LENS_FACING_BACK
                } else {
                    mActivity.showMessage(R.string.qr_rear_camera_unavailable)
                    CameraSelector.LENS_FACING_FRONT
                })
            useCasesList.add(mIAnalyzer)

        } else {
            if (isVideoMode) {

                mActivity.micOffIcon.visibility = when {
                    includeAudio -> View.GONE
                    else -> View.VISIBLE
                }

                val recorderBuilder = Recorder.Builder()

                // camera-video 1.6 writes mp4 through the media3 muxer, which cannot keep up with
                // 2160p on a Tensor device: the audio queue overflows a few seconds in and stop
                // then has to drain everything the muxer is behind by. The platform muxer, which
                // is what every release up to 1.5 used, keeps up. Both live in an internal
                // package, so this has to be re-checked on every camera-video upgrade.
                recorderBuilder.setMuxerFactory { MediaMuxerImpl() }

                if (slowMotion != null) {
                    val quality = videoFrameRateKey(cameraProvider!!.getCameraInfo(cameraSelector)).second
                    recorderBuilder.setQualitySelector(QualitySelector.from(quality!!))
                } else if (!usesFeatureGroup) {
                    recorderBuilder.setQualitySelector(QualitySelector.from(supportedVideoQuality()))
                }

                val videoCaptureBuilder = VideoCapture.Builder(recorderBuilder.build())

                // On cameras where the feature group is not used (see canApplyVideoStabilization)
                // EIS is deliberately left off. The pre-1.6 stabilization setters cannot be applied
                // on top of the recorder's higher qualities -- UHD in particular is in none of the
                // stabilization-guaranteed configurations -- so requesting them would either kill
                // the preview or force the quality down. We keep the selected quality (4K by
                // default) and simply do not stabilize. This is the app's long-standing behavior
                // (EIS regressed here when it moved off the Camera2 API); the toggle is hidden
                // rather than left inert on these cameras, and implementing EIS for them -- the
                // pre-1.6 setters, restricted to the qualities that permit them -- is a separate
                // change that needs a device the feature group cannot serve to test on.

                if (mActivity.camConfig.saveVideoAsPreviewed && slowMotion == null) {
                    videoCaptureBuilder.setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
                }

                videoCapture = videoCaptureBuilder.build()

                useCasesList.add(videoCapture!!)
            }

            if (slowMotion != null) {
                imageCapture = null
            } else if (!mActivity.requiresVideoModeOnly) {
                imageCapture = builder.let {
                    val qualitySingleJpeg=softwareFastPhoto && !softwareJpegBurst && qualifiedJpegQuality
                    val captureMode = PhotoCapturePolicy.captureMode(
                        preferQuality = (prioritizeCaptureQuality && currentMode != CameraMode.NIGHT && !softwarePhoto) || qualitySingleJpeg,
                        requestZsl = enableZsl && !softwareJpegBurst && currentMode != CameraMode.NIGHT && !softwarePhoto && !qualitySingleJpeg && activePhotoOutputFormat == PhotoOutputFormat.JPEG,
                        extensionEnabled = appliedExtension != null,
                        videoSnapshot = isVideoMode,
                        flashEnabled = flashMode != ImageCapture.FLASH_MODE_OFF,
                    )
                    it.setCaptureMode(when (captureMode) {
                        PhotoCapturePolicy.Mode.QUALITY -> ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                        PhotoCapturePolicy.Mode.ZERO_SHUTTER_LAG -> ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
                        PhotoCapturePolicy.Mode.LATENCY -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                    })


                    it.setTargetRotation(
                        imageCapture?.targetRotation
                            ?: rotation
                    )

                    val resolutionSelectorBuilder = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(aspectRatioStrategy)
                    if (usesProcessingCapture) {
                        // Also constrain automatic/highest selection, Portrait,
                        // and the serial CameraX RAW path, not only the menu.
                        resolutionSelectorBuilder.setResolutionFilter { sizes, _ ->
                            sizes.filter { app.grapheneos.camera.capturer.ProcessingGeometry.supported(
                                it.width, it.height, raw = cameraXRawBurst) }
                        }
                    }

                    val requestedSize=if(canSelectPhotoResolution)photoResolution else null
                    if(requestedSize!=null) {
                        resolutionSelectorBuilder.setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                        resolutionSelectorBuilder.setResolutionStrategy(ResolutionStrategy(Size(requestedSize.width,requestedSize.height),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    } else if (selectHighestResolution && !canSelectPhotoResolution) {
                        resolutionSelectorBuilder.setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                    }

                    it.setResolutionSelector(resolutionSelectorBuilder.build())

                    it.setFlashMode(if (softwareNight || softwarePhoto) ImageCapture.FLASH_MODE_OFF else flashMode)

                    it.setJpegQuality(photoQuality)

                    it.build()
                }

                useCasesList.add(imageCapture!!)
            }
        }

        val previewResolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspectRatioStrategy)
        val targetPreviewShortEdge = mActivity.resources.getInteger(R.integer.config_camera_preview_target_short_edge)
        if (targetPreviewShortEdge in 1..2160 && !isVideoMode && !isQRMode && appliedExtension == null) {
            val width = if (aspectRatio == AspectRatio.RATIO_16_9) targetPreviewShortEdge * 16 / 9 else targetPreviewShortEdge * 4 / 3
            previewResolution.setResolutionStrategy(ResolutionStrategy(Size(width, targetPreviewShortEdge),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
        }
        val maxPreviewShortEdge = mActivity.resources.getInteger(
            R.integer.config_camera_preview_max_short_edge
        )
        if (maxPreviewShortEdge > 0 && !isVideoMode && appliedExtension == null) {
            previewResolution.setResolutionFilter { sizes, _ ->
                val filtered = sizes.filter { minOf(it.width, it.height) <= maxPreviewShortEdge }
                // An invalid device limit must not make an otherwise supported camera unbindable.
                if (filtered.isEmpty()) {
                    Log.w(TAG, "No preview resolution satisfies the device limit")
                    sizes
                } else {
                    filtered
                }
            }
        }

        val previewBuilder = Preview.Builder()
            .setTargetRotation(
                preview?.targetRotation
                    ?: rotation
            )
        // A high-speed preview takes the recording's size and may not choose its own.
        if (slowMotion == null) {
            previewBuilder.setResolutionSelector(previewResolution.build())
        }

        // Pixels and potentially other devices enable EIS by default, which reduces the field of
        // view and image quality for image capture if it's not explicitly disabled.
        //
        // setPreviewStabilizationEnabled() is one of the non-groupable setters that SessionConfig
        // rejects outright once a feature group is in use, so it must not be called at all --
        // with either value -- when stabilization is being requested through the feature group
        // below. On every other path it is explicitly disabled: EIS is not applied off the feature
        // group (see the VideoCapture builder above), and leaving it unset would let a device
        // default preview stabilization cost photo capture its field of view.
        when {
            usesFeatureGroup || slowMotion != null -> {}

            else -> previewBuilder.setPreviewStabilizationEnabled(false)
        }

        if (slowMotion == null && appliedExtension == null) {
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(captureResults.forSession())
            mActivity.previewHold.arm()
        }
        if(appliedExtension==null && slowMotion==null) {
            val nr=mActivity.resources.getInteger(R.integer.config_camera_preview_noise_reduction)
            val info=Camera2CameraInfo.from(cameraProvider!!.getCameraInfo(cameraSelector))
            if(nr in 0..4 && info.getCameraCharacteristic(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.contains(nr)==true)
                Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE,nr)
        }
        mActivity.previewView.implementationMode = if (softwarePortrait && Build.VERSION.SDK_INT >= 33)
            androidx.camera.view.PreviewView.ImplementationMode.COMPATIBLE else androidx.camera.view.PreviewView.ImplementationMode.PERFORMANCE
        preview = previewBuilder.build().also {
            useCasesList.add(it)
            it.surfaceProvider = mActivity.previewView.surfaceProvider
        }

        mActivity.forceUpdateOrientationSensor()

        // The list ordering encodes priority (highest priority first): CameraX walks the subsets
        // of this list in order and binds the first one the camera supports, so trailing features
        // are the ones given up first.
        //
        // The video quality leads because it is an explicit, deliberate choice from a spinner,
        // whereas stabilization is a toggle that defaults to on and that most users never touch;
        // an explicit choice should not lose to a default. This inverts the CameraX 1.5.x
        // behaviour, where asking for 2160p on a Pixel silently recorded at 1080p because
        // stabilization won. Whatever is given up is now reported by onFeaturesSelected().
        //
        // Both stabilization features are listed because the EIS toggle is offered whenever
        // either kind is supported (see canApplyVideoStabilization). They share one feature
        // type, so CameraX never selects both and skips the subsets containing the pair; the
        // effective order is quality+preview-stabilization, quality+video-stabilization, quality
        // alone, then the same three without the quality. Preview stabilization is preferred
        // because it stabilizes the preview and the recording alike, making the framing that is
        // shown the framing that is recorded, while video stabilization only stabilizes the file.
        //
        // If the quality feature is dropped the quality follows Recorder's default quality
        // selector (FHD, HD, SD in that order).
        val preferredFeatures = arrayListOf<GroupableFeature>()

        if (usesFeatureGroup) {
            videoQualityFeature?.let { preferredFeatures.add(it) }
            preferredFeatures.add(GroupableFeature.PREVIEW_STABILIZATION)
            preferredFeatures.add(GroupableFeatures.VIDEO_STABILIZATION)
        }

        // Not every camera can run video, photo and preview at once. Ask before binding rather
        // than binding and retrying without the photo use case when it throws: an
        // IllegalArgumentException from bindToLifecycle() carries no indication of which
        // constraint was violated, so the retry could not tell "this camera can't do video plus
        // photo" apart from any other misconfiguration, and would answer both by silently
        // dropping in-video snapshots. A genuine bug then looked like a missing feature. This
        // asks the specific question, and leaves unexpected exceptions to surface as failures.
        val snapshotUseCase = imageCapture
        if (isVideoMode && snapshotUseCase != null) {
            val cameraInfo = try {
                cameraProvider?.getCameraInfo(cameraSelector)
            } catch (exception: IllegalArgumentException) {
                Log.e(TAG, "Failed to query camera info", exception)
                mActivity.showMessage(mActivity.getString(R.string.bind_failure))
                return
            }

            fun isSupported(useCases: List<UseCase>, features: Set<GroupableFeature>) =
                cameraInfo?.isSessionConfigSupported(
                    SessionConfig(useCases = useCases, requiredFeatureGroup = features)
                ) == true

            // When a quality feature is about to be requested, the probe has to require that
            // quality too: a camera can be able to run the three plain streams yet not at the
            // chosen quality, and a probe without it would keep the snapshot use case and leave
            // the conflict to the feature-group resolver -- which resolves it by dropping the
            // *quality*, with a notice blaming the camera for a quality it does support. The
            // quality wins the conflict because it is an explicit choice from the settings while
            // in-video snapshots are an implicit capability (the same reasoning as the preferred
            // feature ordering above), and giving up the snapshots is already the established
            // answer when they can't be bound at all. The second isSupported(useCasesList,
            // emptySet()) arm keeps the snapshots when the quality is unreachable even without
            // them: dropping them would buy nothing, and the resolver's "unsupported quality"
            // notice is genuinely true then.
            val dropReason = when {
                videoQualityFeature == null -> when {
                    isSupported(useCasesList, emptySet()) -> null
                    else -> "Video, photo and preview can't be bound together on this camera"
                }
                isSupported(useCasesList, setOf(videoQualityFeature)) -> null
                isSupported(useCasesList - snapshotUseCase, setOf(videoQualityFeature)) ->
                    "This camera can't record at the selected video quality with in-video " +
                            "snapshots enabled"
                isSupported(useCasesList, emptySet()) -> null
                else -> "Video, photo and preview can't be bound together on this camera"
            }

            if (dropReason != null) {
                Log.i(TAG, "$dropReason; disabling snapshots while recording")
                useCasesList.remove(snapshotUseCase)
                imageCapture = null
            }
        }

        val spatialLuma=if(currentMode==CameraMode.VIDEO)
            (if(BuildConfig.DEBUG)diagnosticSpatialLuma else null)?.div(100f) ?:
                (mActivity.resources.getInteger(R.integer.config_camera_video_preview_luma_denoise).coerceIn(0,100)/100f) else 0f
        if(BuildConfig.DEBUG)app.grapheneos.camera.capturer.PortraitPreviewDiagnostics.spatialLumaApplied=(spatialLuma*100).toInt()
        val retention=mActivity.resources.getInteger(R.integer.config_camera_preview_temporal_retention)
        if(BuildConfig.DEBUG)Log.i("TemporalPreview","setup retention="+retention+" wide="+physicalWideBound+" unavailable="+temporalPreviewUnavailable+" mode="+currentMode.name)
        if(retention in 1..80 && !temporalPreviewUnavailable && appliedExtension==null && slowMotion==null &&
            // The filter does not depend on the still format, but CameraX refuses a preview
            // effect beside a RAW ImageCapture. A split JPEG + RAW keeps it outside its RAW phase.
            !cameraXRawBurst && (activePhotoOutputFormat==PhotoOutputFormat.JPEG || (splitsRawJpeg && !splitRawPhase)) &&
            lensFacing==CameraSelector.LENS_FACING_BACK &&
            (currentMode==CameraMode.VIDEO || currentMode in setOf(CameraMode.CAMERA,CameraMode.NIGHT) &&
                // A logical camera zooms past 1x without a rebind: keep the effect whenever the
                // wide lens is reachable and let the zoom decide whether it filters.
                (physicalWideBound || photoLensScale<.999f ||
                    (if (prefersPhysicalMain) requestedPhotoZoom<.999f else wideDisplayScale()<.999f)))) {
            val generation=previewRecoveryGeneration
            temporalPreview=app.grapheneos.camera.ui.TemporalPreviewEffect(retention/100f,spatialLuma) {temporalPreviewFailed(generation)}
        }
        try {
            val baseSession = when {
                slowMotion != null -> slowMotionSessionConfig(slowMotion)
                isVideoMode -> videoSessionConfig(useCasesList, preferredFeatures, videoQualityFeature)
                else -> photoSessionConfig(useCasesList,preferredFeatures,
                    appliedExtension==null && currentMode!=CameraMode.NIGHT && !isQRMode)
            }
            val sessionConfig=temporalPreview?.let {effect -> SessionConfig(useCases=baseSession.useCases,
                viewPort=baseSession.viewPort,effects=listOf(effect),frameRateRange=baseSession.frameRateRange,
                requiredFeatureGroup=baseSession.requiredFeatureGroup,preferredFeatureGroup=baseSession.preferredFeatureGroup)
            } ?: baseSession

            if (sessionConfig.preferredFeatureGroup.isNotEmpty()) {
                val requested = preferredFeatures.toList()
                val boundLensFacing = lensFacing
                sessionConfig.setFeatureSelectionListener(
                    ContextCompat.getMainExecutor(mActivity)
                ) { selected ->
                    onFeaturesSelected(boundLensFacing, requested, videoQualityFeature, selected)
                }
            }

            camera = cameraProvider!!.bindToLifecycle(
                mActivity, cameraSelector,
                sessionConfig
            )
        } catch (exception: RuntimeException) {
            if(temporalPreview!=null) {
                if(BuildConfig.DEBUG)Log.w("TemporalPreview","Optional preview bind failed",exception)
                closePreviewProcessing();temporalPreviewUnavailable=true
                camera=null;startCamera(forced=true,preserveCapture=preserveCapture);return
            }
            // A vendor extension can still fail to initialize at bind time even though the
            // pre-flight probe in isExtensionUsable() passed. When one was applied, record the
            // failure so the mode stops being offered, rather than letting the exception kill the
            // process or -- equally bad -- retrying the same doomed bind on every resume.
            val key = appliedExtension
            if (key == null) {
                // No extension in play: an IllegalArgumentException is a plain unsupported
                // configuration (reported and swallowed); anything else is a real bug that must
                // stay visible.
                if (exception is IllegalArgumentException) {
                    Log.e(TAG, "Failed to bind use cases", exception)
                    mActivity.showMessage(mActivity.getString(R.string.bind_failure))
                    return
                }
                throw exception
            }

            // With an extension applied, only the vendor's known-permanent signatures mean "this
            // mode is unusable here": UnsupportedOperationException (a vendor extender that
            // advertised the mode and then threw from its own init -- Pixels: "Framework size list
            // map not supported in pixel path") and IllegalArgumentException (an extension bind
            // always uses the same fixed pair of use cases, so an invalid configuration is as
            // permanent as any other vendor failure). Anything else is a real bug and is rethrown
            // rather than hidden behind a silent mode switch.
            if (exception !is UnsupportedOperationException && exception !is IllegalArgumentException) {
                throw exception
            }

            Log.e(TAG, "Extension mode $extMode failed to bind; disabling it", exception)
            extensionUsability[key] = false
            mActivity.showMessage(mActivity.getString(R.string.extension_mode_unavailable))

            // The bind never completed: currentMode still names the mode that was just disabled
            // and nothing is rendering into the preview. Refreshing the tabs alone would only
            // *visually* select another tab, leaving a frozen preview behind a lying tab bar.
            // Switch for real -- switchMode() rebinds, moves the highlight and refreshes the tabs.
            // Recursion stops because the default mode uses no extension.
            switchMode(DEFAULT_CAMERA_MODE)
            return
        }

        loadTabs()
        mActivity.updateComputationalMode()
        mActivity.settingsDialog.reloadFrameRates()

        // Every bind hands out a fresh LiveData in extension modes, and the old observer would
        // otherwise stay attached: after a handful of mode switches a single zoom step redrew the
        // thumb once per bind that had ever happened.
        zoomStateSource?.removeObserver(zoomStateObserver)
        zoomStateSource = camera?.cameraInfo?.zoomState?.also {
            it.observe(mActivity, zoomStateObserver)
        }
        cameraStateSource?.removeObserver(cameraStateObserver)
        cameraStateSource = camera?.cameraInfo?.cameraState?.also {
            it.observe(mActivity, cameraStateObserver)
        }
        // The observer above has already run if the activity is started; this covers the case where
        // it has not, so the bar does not claim 1.0x while the camera is zoomed.
        zoomState = zoomStateSource?.value?.let { photoZoomState(it) }
        if (prefersPhysicalMain) camera?.cameraControl?.setZoomRatio(app.grapheneos.camera.capturer.PhotoZoomMapping.toLens(requestedPhotoZoom, photoLensScale))
        if (usesPhysicalModeLens) zoomStateSource?.value?.let {
            camera?.cameraControl?.setZoomRatio(nightZoom.coerceIn(it.minZoomRatio, it.maxZoomRatio))
        }

        if(currentMode==CameraMode.VIDEO)zoomStateSource?.value?.let { state ->
            val requested=(requestedVideoZoom[lensFacing] ?: 1f).coerceIn(state.minZoomRatio,state.maxZoomRatio)
            camera?.cameraControl?.setZoomRatio(requested)
        }
        mActivity.zoomBar.updateThumb(false)

        camera?.cameraInfo?.exposureState?.let { mActivity.exposureBar.setExposureConfig(it) }

        mActivity.preparePortraitPreview()
        mActivity.settingsDialog.torchToggle.isChecked = false

        // Focus camera on touch/tap
        mActivity.previewView.setOnTouchListener(mActivity)
        camera?.cameraInfo?.let {
            mActivity.previewView.applyPreviewRatio(aspectRatio, it)
            mActivity.fitPreviewFrame()
        }

        if (isInPhotoMode) {
            mActivity.sensorNotifier?.forceUpdateGyro()
        } else {
            mActivity.gCircleFrame.visibility = View.GONE
        }
    }

    fun snapPreview() {

        if (selfIlluminate) {

            val animation: Animation = AlphaAnimation(0f, 0.8f)
            animation.duration = PREVIEW_SL_OVERLAY_DUR
            animation.interpolator = LinearInterpolator()
            animation.fillAfter = true

            mActivity.mainOverlay.setImageResource(android.R.color.white)

            animation.setAnimationListener(
                object : Animation.AnimationListener {
                    override fun onAnimationStart(p0: Animation?) {
                        mActivity.mainOverlay.visibility = View.VISIBLE
                    }

                    override fun onAnimationEnd(p0: Animation?) {}

                    override fun onAnimationRepeat(p0: Animation?) {}

                }
            )

            mActivity.mainOverlay.startAnimation(animation)

        } else {

            val animation: Animation = AlphaAnimation(1f, 0f)
            animation.duration = PREVIEW_SNAP_DURATION
            animation.interpolator = LinearInterpolator()
            animation.repeatMode = Animation.REVERSE

            mActivity.mainOverlay.setImageResource(android.R.color.black)

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

    // probeOnMiss is false because tab refreshes must never pay for a vendor probe on the main
    // thread (see loadTabs). An unprobed mode is left out of the tabs for now, like a failed
    // probe, and comes back on the refresh that follows its probe.
    private fun availableModes(): Set<CameraMode> {
        return CameraMode.entries.filter {
            when (it) {
                CameraMode.CAMERA, CameraMode.VIDEO, CameraMode.PORTRAIT -> true
                CameraMode.SLOW_MOTION -> lensFacing in slowMotionDefaultQualities
                CameraMode.NIGHT -> arrayOf(CameraSelector.LENS_FACING_BACK, CameraSelector.LENS_FACING_FRONT).any { facing ->
                    RawLensCatalog.lenses(cameraProvider?.availableCameraInfos ?: emptyList(), facing).any { supportsNight(it.info) }
                } ||
                    vendorModeAvailable(CameraMode.NIGHT, CameraSelector.LENS_FACING_BACK) ||
                    vendorModeAvailable(CameraMode.NIGHT, CameraSelector.LENS_FACING_FRONT)
                CameraMode.QR_SCAN -> mActivity !is SecureMainActivity
                else -> {
                    check(it.extensionMode != ExtensionMode.NONE)
                    isExtensionUsable(
                        FRONT_CAMERA_SELECTOR, CameraSelector.LENS_FACING_FRONT,
                        it.extensionMode, probeOnMiss = false
                    ) || isExtensionUsable(
                        REAR_CAMERA_SELECTOR, CameraSelector.LENS_FACING_BACK,
                        it.extensionMode, probeOnMiss = false
                    )
                }
            }
        }.toSet()
    }

    // The (selector, cache key) pairs availableModes() needs a verdict for that only a vendor
    // probe can answer. Deliberately a pure cache check: even asking whether a mode is
    // advertised costs a vendor proxy round trip (see probeExtension), so the probe round has
    // to answer that too.
    private fun unprobedExtensions(): List<Pair<CameraSelector, Pair<Int, Int>>> {
        if (extensionsManager == null || cameraProvider == null) return emptyList()

        val result = arrayListOf<Pair<CameraSelector, Pair<Int, Int>>>()
        for (mode in CameraMode.entries) {
            if (mode.extensionMode == ExtensionMode.NONE) continue
            for ((selector, lensFacing) in arrayOf(
                FRONT_CAMERA_SELECTOR to CameraSelector.LENS_FACING_FRONT,
                REAR_CAMERA_SELECTOR to CameraSelector.LENS_FACING_BACK,
            )) {
                val key = lensFacing to mode.extensionMode
                if (extensionUsability[key] == null) {
                    result.add(selector to key)
                }
            }
        }
        return result
    }

    private var extensionProbesInFlight = false
    private var slowMotionProbed = false
    private var slowMotionDefaultQualities: Map<Int,Quality> = emptyMap()

    private fun loadTabs() {
        if (!mActivity.shouldShowCameraModeTabs()) {
            return
        }

        // Refreshing the tabs must not run extension probes on the calling (main) thread: the
        // first refresh after process start needs one vendor-extender init round trip over
        // binder per advertised mode per lens, which measures at over half a second of blocked
        // main thread -- more than a hundred dropped frames -- during startup on a Pixel 7 Pro.
        // The probes run on their own short-lived thread instead (not cameraExecutor, which the
        // QR analyzer may be draining) and the tabs are built once every verdict is in, so the
        // tab bar still appears exactly once, fully formed, at the same time it used to; until
        // then swipes and taps resolve to no tab and the app simply stays in the current mode.
        // Later refreshes find the cache warm and rebuild synchronously, exactly as before.
        val pending = unprobedExtensions()
        if (pending.isEmpty() && slowMotionProbed) {
            buildTabs()
            return
        }

        if (extensionProbesInFlight) return
        val provider = cameraProvider ?: return
        val em = extensionsManager ?: return
        extensionProbesInFlight = true

        thread {
            val verdicts = HashMap<Pair<Int, Int>, Boolean?>()
            for ((selector, key) in pending) {
                verdicts[key] = probeExtension(provider, em, selector, key.second)
            }

            val slowDefaults=linkedMapOf<Int,Quality>()
            for((selector,facing) in listOf(FRONT_CAMERA_SELECTOR to CameraSelector.LENS_FACING_FRONT,REAR_CAMERA_SELECTOR to CameraSelector.LENS_FACING_BACK)) {
                try {
                    val info=provider.getCameraInfo(selector)
                    eligibleSlowMotionQualities(info).firstOrNull()?.let {slowDefaults[facing]=it}
                } catch (_: IllegalArgumentException) { }
            }
            ContextCompat.getMainExecutor(mActivity).execute {
                extensionProbesInFlight = false
                slowMotionDefaultQualities=slowDefaults;slowMotionProbed=true
                if (mActivity.isDestroyed || mActivity.isFinishing) return@execute

                if (probedCameraProvider !== provider) {
                    // The camera stack was reinitialized while probing: these verdicts describe
                    // vendor state that no longer exists (the same reasoning as the cache clear
                    // in initializeCamera). Any refresh that ran for the new provider found this
                    // round still in flight and skipped scheduling, so start over for it.
                    loadTabs()
                    return@execute
                }

                for ((key, verdict) in verdicts) {
                    // Only fill in keys that are still unprobed. A bind failure that ran while this
                    // round was in flight may have blacklisted the mode (extensionUsability[key] =
                    // false); that verdict is fresher than this probe's and must not be overwritten
                    // -- otherwise a mode that just failed to bind would be offered again.
                    if (verdict != null && extensionUsability[key] == null) {
                        extensionUsability[key] = verdict
                    }
                }
                buildTabs()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildTabs() {
        val tabLayout = mActivity.tabLayout
        val availableModes = availableModes()

        if (availableModes == tabLayout.getAllModes()) {
            return
        }

        Log.i(TAG, "Refreshing tabs...")

        tabLayout.removeAllTabs()

        availableModes.forEach { mode ->
            tabLayout.newTab().let { tab ->
                tab.setText(mode.uiName)

                tab.view.setOnTouchListener { _, e ->
                    if (e.action == MotionEvent.ACTION_UP) {
                        mActivity.finalizeMode(tab)
                    }
                    false
                }
                tab.tag = mode

                // Highlight the mode the camera is really in, not the default one: the tabs are
                // also rebuilt long after startup, once the extension probes report back.
                tabLayout.addTab(tab, mode == currentMode)
            }
        }
    }

    fun switchMode(mode: CameraMode) {
        if (currentMode == mode) {
            return
        }

        val previousZoom = displayZoom
        val carryLens = if (mode in listOf(CameraMode.NIGHT, CameraMode.PORTRAIT) &&
            currentMode in listOf(CameraMode.CAMERA, CameraMode.NIGHT, CameraMode.PORTRAIT) && !vendorModeAvailable(mode)) {
            if (usesPhysicalModeLens) selectedRawLens()
            else rawLenses().firstOrNull { it.label == if (previousZoom < 1f)
                R.string.lens_ultra_wide else R.string.lens_main }
        } else null
        val carryZoom = if (carryLens != null) previousZoom / lensDisplayScale(carryLens) else 1f
        currentMode = mode
        if (mode == CameraMode.CAMERA) requestedPhotoZoom = previousZoom
        carryLens?.let { rawLensId = it.id; nightZoom = carryZoom.coerceAtLeast(1f) }

        // The strip highlights whatever the user last touched, but a mode can also change without
        // a touch: keep the highlight on the mode the camera is actually in.
        if (mActivity.shouldShowCameraModeTabs()) {
            mActivity.tabLayout.getTabForMode(mode)?.let { tab ->
                mActivity.tabLayout.selectTab(tab)
                mActivity.tabLayout.centerTab(tab)
            }
        }

        mActivity.cancelFocusTimer()

        isQRMode = mode == CameraMode.QR_SCAN

        isVideoMode = mode == CameraMode.VIDEO || mode == CameraMode.SLOW_MOTION

        if (isQRMode) {
            mActivity.qrOverlay.visibility = View.VISIBLE
            mActivity.thirdOption.visibility = View.INVISIBLE

            if (scanAllCodes) {
                mActivity.setFlipCameraIcon(
                    R.drawable.cancel, R.string.stop_scanning_all_formats
                )
                mActivity.qrScanToggles.visibility = View.GONE
            } else {
                mActivity.setFlipCameraIcon(
                    R.drawable.auto, R.string.scan_all_formats
                )
                mActivity.qrScanToggles.visibility = View.VISIBLE
            }

            mActivity.cancelButtonView.visibility = View.INVISIBLE

            mActivity.captureButton.setBackgroundResource(android.R.color.transparent)
            // Entering QR mode always leaves the torch off
            mActivity.setCaptureButtonIcon(R.drawable.torch_off_button, R.string.turn_torch_on)

            mActivity.micOffIcon.visibility = View.GONE
        } else {
            mActivity.qrOverlay.visibility = View.INVISIBLE
            mActivity.thirdOption.visibility = View.VISIBLE
            mActivity.setFlipCameraIcon(R.drawable.flip_camera, R.string.flip_camera)
            mActivity.cancelButtonView.visibility = View.VISIBLE

            mActivity.qrScanToggles.visibility = View.GONE

            mActivity.captureButton.setBackgroundResource(R.drawable.cbutton_bg)

            if (isVideoMode) {
                mActivity.setCaptureButtonIcon(R.drawable.recording, R.string.start_recording)
            } else {
                mActivity.setCaptureButtonIcon(R.drawable.camera_shutter, R.string.capture)
                mActivity.micOffIcon.visibility = View.GONE
            }
        }

        mActivity.updateSelfTimerBadge()

        startCamera(true)
    }

    fun showMoreOptionsForQR() {
        val builder = MaterialAlertDialogBuilder(mActivity)
        builder.setTitle(mActivity.resources.getString(R.string.more_options))

        val optionNames = arrayListOf<String>()
        val optionValues = arrayListOf<Boolean>()

        for (format in BarcodeFormat.entries) {

            if (format in commonFormats) continue

            optionNames.add(format.name)

            val formatSRep = "${SettingValues.Key.SCAN}_$format"
            optionValues.add(
                commonPref.getBoolean(
                    formatSRep,
                    false
                )
            )
        }

        builder.setMultiChoiceItems(
            optionNames.toArray(arrayOf<String>()),
            optionValues.toBooleanArray()
        ) { _, index, isChecked ->
            optionValues[index] = isChecked
        }

        // Add OK and Cancel buttons
        builder.setPositiveButton(getString(R.string.ok)) { _, _ ->

            val allCommonFormatsDisabled = commonFormats.none {
                allowedFormats.contains(it)
            }

            // If all formats displayed outside the dialog are disabled (main QR scanner
            // UI)
            if (allCommonFormatsDisabled) {
                val noOptionWasChecked = optionValues.none { it }

                // If no option is selected within the check box too (implying no barcode format
                // is selected at all) - don't make apply the selction made by the user
                if (noOptionWasChecked) {
                    mActivity.showMessage(
                        getString(R.string.no_barcode_selected)
                    )
                    return@setPositiveButton
                }
            }

            commonPref.edit {
                for ((index, element) in optionNames.withIndex()) {

                    val optionName = element
                    val optionValue = optionValues[index]

                    val formatSRep = "${SettingValues.Key.SCAN}_$optionName"

                    val format = BarcodeFormat.valueOf(optionName)

                    if (optionValue) {
                        if (format !in allowedFormats)
                            allowedFormats.add(format)
                    } else {
                        allowedFormats.remove(format)
                    }

                    putBoolean(formatSRep, optionValue)
                }
            }

            qrAnalyzer?.refreshHints()
        }

        builder.setNegativeButton(R.string.cancel, null)

        // Create and show the alert dialog
        val dialog = builder.create()

        dialog.setOnShowListener {
            val button: Button = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            button.setOnClickListener {

            }
        }

        dialog.showIgnoringShortEdgeMode()
    }

    fun onStorageLocationNotFound() {
        // Reverting back to DEFAULT_MEDIA_STORE_CAPTURE_PATH
        storageLocation = SettingValues.Default.STORAGE_LOCATION

        val builder = MaterialAlertDialogBuilder(mActivity)
            .setTitle(R.string.folder_not_found)
            .setMessage(R.string.reverting_to_default_folder)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.more_settings) { _, _ ->
                MoreSettings.start(mActivity)
            }
        val alertDialog = builder.create()
        alertDialog.setCancelable(false)
        alertDialog.showIgnoringShortEdgeMode()
    }
}
