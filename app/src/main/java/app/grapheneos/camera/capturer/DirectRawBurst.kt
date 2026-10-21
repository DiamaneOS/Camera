package app.grapheneos.camera.capturer

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.util.Log
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.camera.core.CameraState
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Exclusive, bounded Camera2 burst. CameraX must close before this camera is opened. */
@SuppressLint("MissingPermission")
internal class DirectRawBurst(
    private val owner: ImageCapturer, private val id: String, private val initial: CaptureResult,
    exposure: Long, iso: Int, private val cancelled: AtomicBoolean, private val cropAspect: Float=4f/3,
    private val photoFallback: NightExposurePolicy.Setting? = null,
    private val steadyMotion: () -> Boolean = { false },
) {
    var actualExposureNs = exposure
        private set
    var actualIso = iso
        private set
    var longerPhotoAccepted = false
        private set
    val timings = linkedMapOf<String, Long>()
    private val context = owner.mActivity.applicationContext
    private val config = owner.camConfig
    private fun checkActive() { check(!cancelled.get()) { "Burst cancelled" } }

    private fun main(action: () -> Unit) {
        val result = FutureTask { action() }
        context.mainExecutor.execute(result)
        try {result.get(8, TimeUnit.SECONDS)}
        catch(error:Exception) {result.cancel(false);throw error}
    }

    fun capture(count: Int, receive: (Int, Image, CaptureResult) -> Unit) {
        val started = SystemClock.elapsedRealtime()
        require(count in 2..8)
        val oldCamera = config.camera ?: error("Camera unavailable")
        val zoom = config.acquisitionZoomRatio
        val manager = context.getSystemService(CameraManager::class.java)
        val cameraInfo = manager.getCameraCharacteristics(id)
        // Explicit development comparison only. Preserve the camera's normal
        // template choice in release and for original RAW/DNG capture paths.
        val testHotPixelMode = if (app.grapheneos.camera.BuildConfig.DEBUG)
            owner.mActivity.intent.getIntExtra("testRawHotPixelMode", -1) else -1
        val hotPixelOverride = testHotPixelMode.takeIf { wanted -> wanted in 0..2 &&
            cameraInfo.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)?.contains(wanted) == true }
        val hotPixelMapSupported = cameraInfo.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_HOT_PIXEL_MAP_MODES)?.contains(true) == true
        val testHotPixelMap = app.grapheneos.camera.BuildConfig.DEBUG && hotPixelMapSupported &&
            owner.mActivity.intent.getBooleanExtra("testRawHotPixelMap", false)
        val map = cameraInfo.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: error("No stream map")
        val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.filter { ProcessingGeometry.supported(it.width,it.height,raw=true) }
            ?.maxByOrNull { it.width.toLong() * it.height } ?: error("No bounded RAW size")
        val previews = map.getOutputSizes(ImageFormat.YUV_420_888).filter { it.width <= 1280 }
        val previewSize = previews.filter { abs(it.width.toDouble() / it.height - rawSize.width.toDouble() / rawSize.height) < .02 }
            .maxByOrNull { it.width * it.height } ?: previews.maxByOrNull { it.width * it.height } ?: error("No burst preview")
        val thread = HandlerThread("raw-burst-session").apply { start() }
        val handler = Handler(thread.looper)
        val closed = AtomicBoolean()
        val lease=DirectCameraLease<CameraDevice>()
        val recoveryHandler=Handler(android.os.Looper.getMainLooper())
        val closure = BurstClosure(lease, object : BurstClosure.Env {
            override fun now() = SystemClock.elapsedRealtime()
            override fun previousCameraClosed() = oldCamera.cameraInfo.cameraState.value?.type==CameraState.Type.CLOSED
            override fun ownsPreviousBinding() = config.ownsDirectBurst(lease) && config.camera===oldCamera
            override fun unbindPrevious() { config.cameraProvider?.unbindAll();config.camera=null }
            override fun runOffMain(action: () -> Unit) = closureExecutor.execute(action)
            override fun joinThread(): Boolean { thread.quitSafely();thread.join(1000);return !thread.isAlive }
            override fun post(delayMs: Long, action: () -> Unit) { recoveryHandler.postDelayed(action,delayMs) }
            override fun finish() {
                if(config.finishDirectBurst(lease) && owner.mActivity.isStarted &&
                    !owner.mActivity.isDestroyed && !owner.mActivity.isFinishing)
                    config.startCamera(true,preserveCapture=true)
            }
            override fun activityDestroyed() = owner.mActivity.isDestroyed
            override fun activityVisible() = owner.mActivity.isStarted && !owner.mActivity.isDestroyed
            override fun notifySlowRecovery() = owner.mActivity.showMessage(app.grapheneos.camera.R.string.camera_recovery_unavailable)
        })
        val recovered = closure.recovered
        var raw: ImageReader? = null
        val frames = RawFrameQueue<Image, CaptureResult>(object : RawFrameQueue.Reader<Image> {
            override fun acquire(): Image? = raw?.acquireNextImage()
            override fun close(image: Image) = image.close()
            override fun timestamp(image: Image) = image.timestamp
            override fun requestDrain(drain: () -> Unit) { handler.post(drain) }
        }, closed, cancelled, SystemClock::elapsedRealtime)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var preview: ImageReader? = null
        val scene = photoFallback?.let { RawSceneStability() }
        var completed = false
        try {
            main {
                checkActive()
                check(lease.acquire {config.beginDirectBurst(lease)}) {"Burst was already cancelled"}
                check(!closed.get())
                owner.mActivity.updateLastFrame()
                config.closePreviewProcessing()
                config.cameraProvider!!.unbindAll()
                config.camera = null
            }
            val releaseDeadline = SystemClock.elapsedRealtime() + 5000
            while (oldCamera.cameraInfo.cameraState.value?.type != CameraState.Type.CLOSED) {
                checkActive(); check(SystemClock.elapsedRealtime() < releaseDeadline) { "CameraX did not release its camera" }
                Thread.sleep(20)
            }
            timings["cameraXReleaseMs"] = SystemClock.elapsedRealtime() - started
            val opened = ArrayBlockingQueue<Any>(1)
            lease.beginOpen()
            try {manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if(lease.arrived(camera) || !opened.offer(camera))camera.close()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    lease.arrived(camera)
                    camera.close()
                    val error = IllegalStateException("Burst camera disconnected")
                    opened.offer(error); frames.fail(error)
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    lease.arrived(camera)
                    camera.close()
                    val failure = IllegalStateException("Burst camera error $error")
                    opened.offer(failure); frames.fail(failure)
                }
                override fun onClosed(camera: CameraDevice) {
                    lease.closed(camera)
                    if(closed.get())recoveryHandler.post(closure::recover)
                }
            }, handler)} catch(error:Exception) {lease.openFailed();throw error}
            val openedResult = opened.poll(5, TimeUnit.SECONDS)
            device = openedResult as? CameraDevice ?: throw (openedResult as? Exception ?: IllegalStateException("Burst camera open timed out"))
            timings["openMs"] = SystemClock.elapsedRealtime() - started
            checkActive()
            preview = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ reader ->
                    try { reader.acquireLatestImage()?.use { image ->
                        if (scene != null && !closed.get() && !cancelled.get()) {
                            val plane = image.planes[0]
                            scene.observeImage(plane.buffer, image.width, image.height,
                                plane.rowStride, plane.pixelStride, image.timestamp, SystemClock.elapsedRealtimeNanos())
                        }
                    } } catch (_: IllegalStateException) { }
                }, handler)
            }
            raw = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 3).apply {
                setOnImageAvailableListener({ frames.drain() }, handler)
            }
            val configured = ArrayBlockingQueue<Any>(1)
            val outputs = listOf(OutputConfiguration(preview.surface), OutputConfiguration(raw.surface))
            device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs,
                { command -> handler.post(command) }, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(value: CameraCaptureSession) {
                        if (closed.get() || !configured.offer(value)) value.close()
                    }
                    override fun onConfigureFailed(value: CameraCaptureSession) { configured.offer(IllegalStateException("Burst configuration refused")) }
                }))
            val configuredResult = configured.poll(5, TimeUnit.SECONDS)
            session = configuredResult as? CameraCaptureSession ?: throw (configuredResult as? Exception ?: IllegalStateException("Burst configuration timed out"))
            timings["configureMs"] = SystemClock.elapsedRealtime() - started
            val minDuration = map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, rawSize)
            val freshFocus = cameraInfo.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) == true &&
                (cameraInfo.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f
            fun request(template: Int, surface: android.view.Surface, triggerFocus: Boolean = false, metering: Boolean = false): CaptureRequest = device.createCaptureRequest(template).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AE_MODE, if (metering) CaptureRequest.CONTROL_AE_MODE_ON else CaptureRequest.CONTROL_AE_MODE_OFF)
                if (!metering) {
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, actualExposureNs)
                    set(CaptureRequest.SENSOR_SENSITIVITY, actualIso)
                    set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(actualExposureNs, minDuration))
                    hotPixelOverride?.let { set(CaptureRequest.HOT_PIXEL_MODE, it) }
                    if (testHotPixelMap) set(CaptureRequest.STATISTICS_HOT_PIXEL_MAP_MODE, true)
                    if (cameraInfo.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
                            ?.contains(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON) == true)
                        set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                }
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                if(cameraInfo.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                        ?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)==true)
                    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                // The metering preview should retain the camera's ordinary ISP
                // defaults for autofocus. Disable output processing only for
                // the manual RAW requests used by fusion and its probes.
                if (!metering) {
                    set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                    set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                }
                if (android.os.Build.VERSION.SDK_INT >= 30) set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                else cameraInfo.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { active ->
                    val width = (active.width() / zoom).toInt().coerceAtLeast(1)
                    val height = (active.height() / zoom).toInt().coerceAtLeast(1)
                    val left = active.centerX() - width / 2; val top = active.centerY() - height / 2
                    set(CaptureRequest.SCALER_CROP_REGION, android.graphics.Rect(left, top, left + width, top + height))
                }
                if (freshFocus) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, if (triggerFocus) CaptureRequest.CONTROL_AF_TRIGGER_START else CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                    if ((cameraInfo.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0) {
                        initial.get(CaptureResult.CONTROL_AF_REGIONS)?.let { set(CaptureRequest.CONTROL_AF_REGIONS, it) }
                    }
                } else initial.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, it)
                }
                val gains = initial.get(CaptureResult.COLOR_CORRECTION_GAINS)
                val transform = initial.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
                if (gains != null && transform != null) {
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                    set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                    set(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
                    set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform)
                } else set(CaptureRequest.CONTROL_AWB_LOCK, true)
            }.build()
            // Refocus after reopening: a reported manual focus value need not reproduce the
            // preview's optical focus on approximate-calibration lenses. AUTO locks for the burst.
            val warmed = CountDownLatch(1)
            val metered = CountDownLatch(1)
            val focusing = AtomicBoolean(!freshFocus)
            val manualScene = AtomicBoolean()
            var settledFocus: Float? = null
            val readiness = RawFocusReadiness()
            var meteringFrames = 0
            val warmDeadline = SystemClock.elapsedRealtime() + 5_000
            initial.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { timings["initialFocusMilliUnits"] = (it * 1000).toLong() }
            val warmCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                    val af = result.get(CaptureResult.CONTROL_AF_STATE)
                    val focusLocked = !freshFocus || af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                    val lensStationary = result.get(CaptureResult.LENS_STATE) != CaptureResult.LENS_STATE_MOVING
                    val automaticReady = focusing.get() && focusLocked && lensStationary &&
                        (ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED ||
                            ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED)
                    val manualReady = if (manualScene.get()) {
                        val focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                        val retainedFocus = !freshFocus || (focus != null && settledFocus != null &&
                            focus.isFinite() && abs(focus - settledFocus!!) <= .01f)
                        val actualTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                        val actualSensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY)
                        result.get(CaptureResult.CONTROL_AE_MODE) == CaptureResult.CONTROL_AE_MODE_OFF &&
                            actualTime != null && abs(actualTime - actualExposureNs) <= actualExposureNs / 100 &&
                            actualSensitivity != null && abs(actualSensitivity - actualIso) <= (actualIso / 50).coerceAtLeast(1) &&
                            result.get(CaptureResult.LENS_STATE) == CaptureResult.LENS_STATE_STATIONARY && retainedFocus &&
                            af != CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN && af != CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN
                    } else false
                    scene?.observeState(result.get(CaptureResult.SENSOR_TIMESTAMP) ?: -1,
                        if (manualScene.get()) manualReady else automaticReady, SystemClock.elapsedRealtimeNanos())
                    if (warmed.count == 0L) return
                    meteringFrames++
                    if ((meteringFrames >= 2 && ae != CaptureResult.CONTROL_AE_STATE_SEARCHING) || meteringFrames >= 4) metered.countDown()
                    if (!focusing.get()) return
                    if (!readiness.observe(result.frameNumber, focusLocked && lensStationary)) return
                    timings["warmAfState"] = (af ?: -1).toLong()
                    timings["warmNoiseReductionMode"] = (result.get(CaptureResult.NOISE_REDUCTION_MODE) ?: -1).toLong()
                    timings["warmEdgeMode"] = (result.get(CaptureResult.EDGE_MODE) ?: -1).toLong()
                    settledFocus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                    result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { timings["warmFocusMilliUnits"] = (it * 1000).toLong() }
                    timings["warmLensState"] = (result.get(CaptureResult.LENS_STATE) ?: -1).toLong()
                    warmed.countDown()
                }
            }
            session.setRepeatingRequest(request(CameraDevice.TEMPLATE_PREVIEW, preview.surface, metering = true), warmCallback, handler)
            while (!metered.await(50, TimeUnit.MILLISECONDS)) {
                checkActive()
                check(SystemClock.elapsedRealtime() < warmDeadline) { "Burst metering preview did not start" }
            }
            if (freshFocus) {
                focusing.set(true)
                session.capture(request(CameraDevice.TEMPLATE_PREVIEW, preview.surface, triggerFocus = true, metering = true), warmCallback, handler)
            }
            while (!warmed.await(50, TimeUnit.MILLISECONDS)) {
                checkActive()
                check(SystemClock.elapsedRealtime() < warmDeadline) { "Burst sensor focus did not settle" }
            }
            checkActive()
            if (scene != null) {
                val sceneDeadline = SystemClock.elapsedRealtime() + 400
                var reading = scene.reading(SystemClock.elapsedRealtimeNanos())
                while (!reading.steady && reading.reason != RawSceneStability.Reason.INVALID &&
                    SystemClock.elapsedRealtime() < sceneDeadline) {
                    checkActive(); Thread.sleep(25)
                    reading = scene.reading(SystemClock.elapsedRealtimeNanos())
                }
                if (app.grapheneos.camera.BuildConfig.DEBUG && owner.mActivity.intent.getBooleanExtra("testKeepSceneLuma", false)) {
                    val snapshot = scene.snapshotForTest(SystemClock.elapsedRealtimeNanos())
                    reading = snapshot.first
                    try {
                        java.io.DataOutputStream(java.io.File(context.noBackupFilesDir, "photo-test-scene.bin").outputStream().buffered()).use { output ->
                            snapshot.second.forEach { frame -> frame.forEach { output.writeFloat(it) } }
                        }
                        java.io.File(context.noBackupFilesDir, "photo-test-scene.json").writeText(org.json.JSONObject()
                            .put("width", RawSceneStability.WIDTH).put("height", RawSceneStability.HEIGHT)
                            .put("frames", snapshot.second.size).put("reason", reading.reason.name).toString())
                    } catch (_: java.io.IOException) { }
                }
                val gyroSteady = steadyMotion()
                timings["photoSceneReason"] = reading.reason.ordinal.toLong()
                timings["photoSceneSamples"] = reading.samples.toLong()
                timings["photoSceneNoiseMilli"] = (reading.noise * 1000).toLong()
                timings["photoSceneDriftMilli"] = (reading.drift * 1000).toLong()
                timings["photoSceneChangeMilli"] = (reading.localChange * 1000).toLong()
                timings["photoScenePatchNoiseMilli"] = (reading.patchNoise * 1000).toLong()
                timings["photoGyroSteady"] = if (gyroSteady) 1 else 0
                longerPhotoAccepted = reading.steady && gyroSteady
                if (!longerPhotoAccepted) {
                    actualExposureNs = photoFallback.exposureNs
                    actualIso = photoFallback.iso
                }
            }
            timings["warmMs"] = SystemClock.elapsedRealtime() - started
            session.stopRepeating()
            val rawCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { frames.result(it, result) }
                }
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                    frames.fail(IllegalStateException("RAW burst frame failed"))
                }
                override fun onCaptureBufferLost(s: CameraCaptureSession, r: CaptureRequest, target: android.view.Surface, frame: Long) {
                    if (target == raw?.surface) frames.fail(IllegalStateException("RAW burst buffer lost"))
                }
            }
            fun nextRaw(deadline: Long, consume: (Image, CaptureResult) -> Unit) = frames.next(deadline, ::checkActive, consume)
            // A bounded, unsaved RAW probe checks actual sensor headroom rather
            // than guessing from a tone-mapped viewfinder. Re-probe at most once
            // when clipped: the final fusion inputs still have one fixed exposure.
            val minimumExposure = cameraInfo.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.lower
                ?: error("No RAW exposure limits")
            val minimumIso = cameraInfo.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.lower
                ?: error("No RAW sensitivity limits")
            var probes = 0
            for (attempt in 0..1) {
                checkActive()
                session.capture(request(CameraDevice.TEMPLATE_STILL_CAPTURE, raw.surface), rawCallback, handler)
                var reading: RawHighlightPolicy.Reading? = null
                nextRaw(SystemClock.elapsedRealtime() + 3_000) { image, result ->
                    check(image.format == ImageFormat.RAW_SENSOR && image.planes.size == 1 && image.planes[0].pixelStride == 2)
                    check(abs((result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0) - actualExposureNs) <= actualExposureNs / 100 &&
                        abs((result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0) - actualIso) <= (actualIso / 50).coerceAtLeast(1))
                    reading = RawHighlightPolicy.measure(image.planes[0].buffer, image.width, image.height,
                        image.planes[0].rowStride, RawCameraMetadata.calibration(cameraInfo, result), zoom, cropAspect)
                }
                probes++
                val measured = reading ?: error("Missing RAW highlight reading")
                timings["highlightPermille"] = (measured.highlight * 1000).toLong()
                timings["clippedPermille"] = (measured.clippedFraction * 1000).toLong()
                if (measured.scale == 1f) break
                val protected = RawHighlightPolicy.choose(actualExposureNs, actualIso, minimumExposure, minimumIso, measured.scale)
                if (protected.exposureNs == actualExposureNs && protected.iso == actualIso) break
                actualExposureNs = protected.exposureNs
                actualIso = protected.iso
                if (measured.clippedFraction < .005f) break
            }
            timings["highlightProbes"] = probes.toLong()
            timings["highlightMeterMs"] = SystemClock.elapsedRealtime() - started - (timings["warmMs"] ?: 0)
            if (longerPhotoAccepted) {
                // A RAW probe can interrupt preview delivery beyond its freshness
                // budget. Obtain a complete recent scene window instead of
                // weakening the age limit or using the earlier admission.
                val refreshStarted = SystemClock.elapsedRealtime()
                scene!!.restartWindow()
                manualScene.set(true)
                // Check motion at the candidate's fixed exposure. No second AE
                // convergence or focus command is needed; admission requires
                // matching actual exposure/ISO and the settled optical position.
                session.setRepeatingRequest(request(CameraDevice.TEMPLATE_PREVIEW, preview.surface), warmCallback, handler)
                var finalScene = scene.reading(SystemClock.elapsedRealtimeNanos())
                try {
                    while (!finalScene.steady && finalScene.reason != RawSceneStability.Reason.INVALID &&
                        SystemClock.elapsedRealtime() - refreshStarted < 750) {
                        checkActive(); Thread.sleep(25)
                        finalScene = scene.reading(SystemClock.elapsedRealtimeNanos())
                    }
                } finally { session.stopRepeating() }
                val finalGyro = steadyMotion()
                timings["photoSceneRefreshMs"] = SystemClock.elapsedRealtime() - refreshStarted
                timings["photoSceneAfterProbe"] = finalScene.reason.ordinal.toLong()
                timings["photoGyroAfterProbe"] = if (finalGyro) 1 else 0
                if (!finalScene.steady || !finalGyro || actualExposureNs.toDouble() < photoFallback.exposureNs * 1.5) {
                    val short = NightExposurePolicy.shortPhotoFallback(
                        NightExposurePolicy.Setting(actualExposureNs, actualIso), photoFallback, minimumExposure, minimumIso)
                    actualExposureNs = short.exposureNs
                    actualIso = short.iso
                    longerPhotoAccepted = false
                }
            }
            longerPhotoAccepted = longerPhotoAccepted && actualExposureNs.toDouble() >= photoFallback!!.exposureNs * 1.5
            if (scene != null) timings["photoExtended"] = if (longerPhotoAccepted) 1 else 0
            val burst = List(count) { request(CameraDevice.TEMPLATE_STILL_CAPTURE, raw.surface) }
            session.captureBurst(burst, rawCallback, handler)
            val deadline = SystemClock.elapsedRealtime() + 15_000
            for (index in 0 until count) {
                nextRaw(deadline) { image, result ->
                    if (index == 0) {
                        timings["requestedRawHotPixelMode"] = (hotPixelOverride ?: -1).toLong()
                        timings["actualRawHotPixelMode"] = (result.get(CaptureResult.HOT_PIXEL_MODE) ?: -1).toLong()
                        timings["rawHotPixelMapSupported"] = if (hotPixelMapSupported) 1 else 0
                        timings["rawHotPixelMapRequested"] = if (testHotPixelMap) 1 else 0
                        timings["rawHotPixelMapCount"] = (result.get(CaptureResult.STATISTICS_HOT_PIXEL_MAP)?.size ?: -1).toLong()
                        if (app.grapheneos.camera.BuildConfig.DEBUG && testHotPixelMap) {
                            // Sensor-specific coordinates stay in explicitly requested,
                            // private no-backup diagnostics; never in JPEG/MediaStore/logs.
                            val points = result.get(CaptureResult.STATISTICS_HOT_PIXEL_MAP)
                            val arraySize = cameraInfo.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                            if (points != null && points.size <= 8192 && arraySize != null &&
                                points.all { it.x in 0 until arraySize.width && it.y in 0 until arraySize.height }) {
                                try {
                                    val positions = org.json.JSONArray()
                                    points.forEach { positions.put(org.json.JSONArray(listOf(it.x, it.y))) }
                                    java.io.File(context.noBackupFilesDir, "raw-test-hot-pixel-map.json").writeText(org.json.JSONObject()
                                        .put("pixelArrayWidth", arraySize.width).put("pixelArrayHeight", arraySize.height)
                                        .put("rawWidth", image.width).put("rawHeight", image.height)
                                        .put("positions", positions).toString())
                                    timings["rawHotPixelMapDumped"] = 1
                                } catch (_: java.io.IOException) { }
                            }
                        }
                        result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { timings["firstFocusMilliUnits"] = (it * 1000).toLong() }
                        timings["firstLensState"] = (result.get(CaptureResult.LENS_STATE) ?: -1).toLong()
                        timings["opticalStabilization"] = (result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE) ?: -1).toLong()
                    }
                    receive(index, image, result)
                    if (index == count - 1) timings["lastInputMs"] = SystemClock.elapsedRealtime() - started
                }
            }
            completed = true
        } finally {
            closed.set(true)
            closure.begin()
            try {session?.abortCaptures()} catch(_:Exception) { }
            try {session?.close()} catch(_:Exception) { }
            lease.requestClose().forEach {try {it.close()} catch(_:Exception) { }}
            frames.close()
            // Listeners run on the handler: close both readers there, so neither closes
            // under a listener that is still reading an image.
            val readersClosed = CountDownLatch(1)
            val closeReaders = Runnable {
                try {raw?.setOnImageAvailableListener(null, null);raw?.close()} catch(_:Exception) { }
                try {preview?.setOnImageAvailableListener(null, null);preview?.close()} catch(_:Exception) { }
                readersClosed.countDown()
            }
            if (!handler.post(closeReaders)) closeReaders.run()
            if (!readersClosed.await(2, TimeUnit.SECONDS)) Log.w("DirectRawBurst", "RAW readers closed late")
            lease.resourcesClosed();recoveryHandler.post(closure::recover)
            if (!recovered.await(3,TimeUnit.SECONDS)) {
                // Frames already delivered stay valid: a slow rebind must not discard a finished burst.
                if (completed) Log.w("DirectRawBurst", "Camera rebind is slow; closure recovery remains active")
                else throw IllegalStateException("Burst camera release timed out; closure recovery remains active")
            }
            timings["closedAndReboundMs"] = SystemClock.elapsedRealtime() - started
        }
    }

    companion object {private val closureExecutor=Executors.newSingleThreadExecutor()}
}
