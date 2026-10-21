package app.grapheneos.camera.capturer

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.*
import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CaptureRequest as Q
import android.hardware.camera2.CaptureResult as R
import android.os.Bundle
import android.os.SharedMemory
import android.os.SystemClock
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Fixed-exposure RAW burst on a physical sensor. No vendor keys or HAL changes. */
@SuppressLint("UnsafeOptInUsageError")
internal class NightCapture(private val owner: ImageCapturer, private val cancelled: AtomicBoolean,
    private val previewProvider: () -> RawPreviewGuide, private val progress: (Int, Int) -> Unit, private val frameCount: Int = 8,
    private val allowLongExposure: Boolean = true, private val cropAspect: Float = 4f / 3) {
    private val context = owner.mActivity.applicationContext
    private val config = owner.camConfig
    private val memories = arrayListOf<SharedMemory>()

    // Explicit development-only fixed-path diagnostic. No identifiers or vendor metadata.
    private fun keepTestFrame(width: Int, height: Int, stride: Int, buffer: java.nio.ByteBuffer,
                              camera: android.hardware.camera2.CameraCharacteristics,
                              initial: android.hardware.camera2.CaptureResult,
                              result: android.hardware.camera2.CaptureResult) {
        try {
            require(buffer.remaining() in 1..32 * 1024 * 1024)
            val metadata = org.json.JSONObject().put("width", width).put("height", height).put("stride", stride)
                .put("cfa", camera.get(C.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT))
                .put("focusCalibration", camera.get(C.LENS_INFO_FOCUS_DISTANCE_CALIBRATION))
                .put("calibration", org.json.JSONArray(RawCameraMetadata.calibration(camera, result).toList()))
                .put("colorPreview", org.json.JSONArray(RawCameraMetadata.color(camera, initial).toList()))
                .put("colorRaw", org.json.JSONArray(RawCameraMetadata.color(camera, result).toList()))
            metadata.put("lensShadingApplied", camera.get(C.SENSOR_INFO_LENS_SHADING_APPLIED))
                .put("lensShadingMapMode", result.get(R.STATISTICS_LENS_SHADING_MAP_MODE))
            result.get(R.STATISTICS_LENS_SHADING_CORRECTION_MAP)?.let { shading ->
                require(shading.rowCount in 1..64 && shading.columnCount in 1..64)
                val values = FloatArray(shading.rowCount * shading.columnCount * 4)
                var at = 0
                for (y in 0 until shading.rowCount) for (x in 0 until shading.columnCount) for (channel in 0..3) {
                    values[at++] = shading.getGainFactor(channel, x, y).also { require(it.isFinite() && it in 1f..32f) }
                }
                metadata.put("lensShadingColumns", shading.columnCount).put("lensShadingRows", shading.rowCount)
                    .put("lensShadingGainsRggb", org.json.JSONArray(values.toList()))
            }
            for ((name, rect) in listOf("activeArray" to camera.get(C.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
                "preCorrectionActiveArray" to camera.get(C.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE))) {
                rect?.let { metadata.put(name, org.json.JSONArray(listOf(it.left, it.top, it.right, it.bottom))) }
            }
            for ((prefix, reading) in listOf("preview" to initial, "raw" to result)) {
                metadata.put(prefix + "ExposureNs", reading.get(R.SENSOR_EXPOSURE_TIME))
                    .put(prefix + "Iso", reading.get(R.SENSOR_SENSITIVITY))
                    .put(prefix + "Boost", reading.get(R.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100)
                reading.get(R.SENSOR_NEUTRAL_COLOR_POINT)?.let { metadata.put(prefix + "Neutral", org.json.JSONArray(it.map { value -> value.toDouble() })) }
                reading.get(R.COLOR_CORRECTION_GAINS)?.let { gains ->
                    metadata.put(prefix + "Gains", org.json.JSONArray(listOf(gains.red, gains.greenEven, gains.greenOdd, gains.blue)))
                }
                reading.get(R.COLOR_CORRECTION_TRANSFORM)?.let { matrix ->
                    metadata.put(prefix + "Transform", org.json.JSONArray((0 until 9).map { matrix.getElement(it % 3, it / 3).toDouble() }))
                }
            }
            java.io.File(context.noBackupFilesDir, "night-test-raw.bin").outputStream().channel.use { output ->
                while (buffer.hasRemaining()) check(output.write(buffer) > 0)
            }
            java.io.File(context.noBackupFilesDir, "night-test-raw.json").writeText(metadata.toString(2))
        } catch (_: Exception) { }
    }

    private fun settledPreview(cameraId: String): android.hardware.camera2.CaptureResult {
        val deadline = SystemClock.elapsedRealtime() + 4_000
        while (true) {
            check(!cancelled.get()) { "Night capture cancelled" }
            config.captureResults.latestFor(cameraId)?.let { reading ->
                val af = reading.get(R.CONTROL_AF_STATE)
                val ae = reading.get(R.CONTROL_AE_STATE)
                if (af != R.CONTROL_AF_STATE_ACTIVE_SCAN && af != R.CONTROL_AF_STATE_PASSIVE_SCAN &&
                    (ae == R.CONTROL_AE_STATE_CONVERGED || ae == R.CONTROL_AE_STATE_FLASH_REQUIRED || ae == R.CONTROL_AE_STATE_LOCKED)) return reading
            }
            check(SystemClock.elapsedRealtime() < deadline) { "Focus or exposure did not settle; try tapping the subject" }
            Thread.sleep(40)
        }
    }

    fun capture(): AcquiredRawPhoto {
        var transferred = false
        var acquiredGuide: RawPreviewGuide? = null
        var shadingMemory: SharedMemory? = null
        val started = SystemClock.elapsedRealtime()
        var directTimings: Map<String, Long> = emptyMap()
        var steadyPhotoFinish = false
        val camera = config.camera ?: error("Camera unavailable")
        val capture = config.imageCapture ?: error("RAW capture unavailable")
        val info = Camera2CameraInfo.from(camera.cameraInfo)
        val manager = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
        val characteristics = manager.getCameraCharacteristics(info.cameraId)
        val initial = settledPreview(info.cameraId)
        // UNKNOWN timestamp sources need not share the elapsed-realtime clock. The monitor is
        // cleared on every bind; converged capture state is the portable freshness gate.
        val sensor = context.getSystemService(SensorManager::class.java)
        val gyro = sensor.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        var motion = 0.0
        val motionWindow = NightMotionWindow()
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            override fun onSensorChanged(event: SensorEvent) {
                synchronized(this) {
                    motion = event.values.sumOf { it.toDouble() * it }
                    motionWindow.record(event.timestamp, motion)
                }
            }
        }
        val monitoringMotion = gyro != null && sensor.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
        val control = Camera2CameraControl.from(camera.cameraControl)
        val original = control.getCaptureRequestOptions()
        val direct = context.resources.getBoolean(app.grapheneos.camera.R.bool.config_camera_direct_raw_burst)
        val captureZoom = config.acquisitionZoomRatio
        try {
            val guide = previewProvider()
            acquiredGuide = guide
            val targetLuminance = guide.luminance
            val previewToneTargets = guide.toneTargets
            val previewColorReference = guide.colors
            val referenceRotation = guide.rotation
            val oldExposure = initial.get(R.SENSOR_EXPOSURE_TIME) ?: error("No exposure reading")
            val oldIso = initial.get(R.SENSOR_SENSITIVITY) ?: error("No ISO reading")
            val exposureRange = characteristics.get(C.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: error("No manual exposure")
            val isoRange = characteristics.get(C.SENSOR_INFO_SENSITIVITY_RANGE) ?: error("No manual ISO")
            val steadyPhoto = context.resources.getBoolean(app.grapheneos.camera.R.bool.config_camera_photo_steady_exposure)
            if (monitoringMotion && (allowLongExposure || (steadyPhoto && direct))) {
                val deadline = SystemClock.elapsedRealtime() + 320
                while (SystemClock.elapsedRealtime() < deadline) {
                    check(!cancelled.get()) { "Night capture cancelled" }
                    Thread.sleep(20)
                }
            }
            // The non-direct path cannot inspect fresh scene motion after refocusing.
            val steady = (allowLongExposure || (steadyPhoto && direct)) && monitoringMotion &&
                (if (allowLongExposure) motionWindow.isSteady() else motionWindow.isSteadyAt(SystemClock.elapsedRealtimeNanos()))
            val meteredIso = NightExposurePolicy.effectiveIso(oldIso, initial.get(R.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100, isoRange.upper)
            val exposureCeiling = NightExposurePolicy.exposureCeiling(oldExposure, exposureRange.lower,
                exposureRange.upper, allowLongExposure, steady, meteredIso, isoRange.upper)
            fun exposureLimit(resource: Int,fallback: Long) = context.resources.getInteger(resource).takeIf {it in 1..1000}?.toLong()?.times(1_000_000) ?: fallback
            val handheldLimit=exposureLimit(app.grapheneos.camera.R.integer.config_camera_night_handheld_max_exposure_ms,66_666_667L)
            val steadyLimit=maxOf(handheldLimit,exposureLimit(app.grapheneos.camera.R.integer.config_camera_night_steady_max_exposure_ms,250_000_000L))
            val candidate = NightExposurePolicy.choose(oldExposure, meteredIso, exposureRange.lower, exposureCeiling,
                isoRange.lower, isoRange.upper, steady,handheldLimit,steadyLimit)
            val shortPhoto = if (!allowLongExposure && steady) NightExposurePolicy.choose(oldExposure, meteredIso,
                exposureRange.lower, NightExposurePolicy.exposureCeiling(oldExposure, exposureRange.lower,
                    exposureRange.upper, false, false, meteredIso, isoRange.upper), isoRange.lower, isoRange.upper, false, handheldLimit, steadyLimit) else null
            val photoFallback = shortPhoto?.takeIf { candidate.exposureNs.toDouble() >= it.exposureNs * 1.5 }
            val setting = if (shortPhoto != null && photoFallback == null) shortPhoto else candidate
            var exposure = setting.exposureNs
            var iso = setting.iso
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(Q.CONTROL_AE_MODE, Q.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(Q.SENSOR_EXPOSURE_TIME, exposure)
                .setCaptureRequestOption(Q.SENSOR_SENSITIVITY, iso)
                .setCaptureRequestOption(Q.CONTROL_AWB_LOCK, true)
                .setCaptureRequestOption(Q.FLASH_MODE, Q.FLASH_MODE_OFF)
                .setCaptureRequestOption(Q.NOISE_REDUCTION_MODE, Q.NOISE_REDUCTION_MODE_OFF)
                .setCaptureRequestOption(Q.EDGE_MODE, Q.EDGE_MODE_OFF)
            if (characteristics.get(C.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
                    ?.contains(Q.STATISTICS_LENS_SHADING_MAP_MODE_ON) == true)
                options.setCaptureRequestOption(Q.STATISTICS_LENS_SHADING_MAP_MODE, Q.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            initial.get(R.LENS_FOCUS_DISTANCE)?.let { focus ->
                options.setCaptureRequestOption(Q.CONTROL_AF_MODE, Q.CONTROL_AF_MODE_OFF)
                    .setCaptureRequestOption(Q.LENS_FOCUS_DISTANCE, focus)
            }
            if (!direct) control.addCaptureRequestOptions(options.build()).get(5, TimeUnit.SECONDS)
            require(frameCount in 2..8)
            // Large sensors cannot fit eight frames in the bounded input: take fewer instead of failing.
            val rawSize = characteristics.get(C.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
            val fit = rawSize?.let { NightExposurePolicy.framesThatFit(it.width, it.height) } ?: frameCount
            val count = minOf(NightExposurePolicy.frameCount(oldExposure,meteredIso,isoRange.lower,frameCount), fit)
            check(count >= 2) { "RAW frames too large for Night" }
            context.mainExecutor.execute {owner.showProcessingEstimate(count,setting.exposureNs)}
            val strides = IntArray(count)
            val calibrations = FloatArray(count * 13)
            val shadingMaps = arrayOfNulls<RawShadingData.Map>(count)
            val motions = DoubleArray(count)
            var width = 0; var height = 0
            var color: FloatArray? = null
            var colorSource = "calibration"
            val start = SystemClock.elapsedRealtime()
            if (direct) {
                val burst = DirectRawBurst(owner, info.cameraId, initial, exposure, iso, cancelled, cropAspect,
                    photoFallback) { motionWindow.isSteadyAt(SystemClock.elapsedRealtimeNanos()) }
                burst.capture(count) { index, image, result ->
                    exposure = burst.actualExposureNs
                    iso = burst.actualIso
                    check(image.format == android.graphics.ImageFormat.RAW_SENSOR && image.planes.size == 1)
                    if (index == 0) { width = image.width; height = image.height }
                    check(image.width == width && image.height == height && width.toLong() * height <= 16_000_000)
                    check(abs((result.get(R.SENSOR_EXPOSURE_TIME) ?: 0) - exposure) <= exposure / 100 &&
                        abs((result.get(R.SENSOR_SENSITIVITY) ?: 0) - iso) <= (iso / 50).coerceAtLeast(1)) { "Burst exposure changed" }
                    val plane = image.planes[0]
                    check(plane.pixelStride == 2)
                    memories.add(ProcessingClient.memory("night-raw", plane.buffer.duplicate()))
                    check(memories.sumOf { it.size.toLong() } <= 240L * 1024 * 1024)
                    strides[index] = plane.rowStride
                    RawCameraMetadata.calibration(characteristics, result).copyInto(calibrations, index * 13)
                    shadingMaps[index] = RawCameraMetadata.shading(characteristics, result, width, height)
                    // Both direct modes hold this preview's gains and transform. Prefer that
                    // matching public mapping; a longer exposure does not change the color space.
                    // Sensor calibration remains the fallback when this mapping is unavailable.
                    if (color == null) RawCameraMetadata.captureColor(initial)?.let {
                        color = it; colorSource = "capture"
                    }
                    if (color == null) color = RawCameraMetadata.color(characteristics,
                        if (initial.get(R.COLOR_CORRECTION_GAINS) != null && initial.get(R.COLOR_CORRECTION_TRANSFORM) != null) initial else result)
                    // Development builds: the intent extra, or a one-shot flag file armed over adb.
                    if (index == 0 && app.grapheneos.camera.BuildConfig.DEBUG && (owner.mActivity.intent.getBooleanExtra("testKeepRaw", false) ||
                            java.io.File(context.filesDir, "processing-test/keep-night-raw").delete())) {
                        keepTestFrame(width, height, plane.rowStride, plane.buffer.duplicate(), characteristics, initial, result)
                    }
                    motions[index] = synchronized(listener) { motion }
                    context.mainExecutor.execute { progress(index + 1, count) }
                }
                directTimings = burst.timings
                steadyPhotoFinish = burst.longerPhotoAccepted
            } else {
            for (index in 0 until count) {
                check(!cancelled.get() && SystemClock.elapsedRealtime() - start < 20_000) { "Night capture cancelled or timed out" }
                val queue = ArrayBlockingQueue<Any>(1)
                val accepting = AtomicBoolean(true)
                capture.takePicture(context.mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        if (cancelled.get() || !accepting.get() || !queue.offer(image)) image.close()
                    }
                    override fun onError(exception: ImageCaptureException) { queue.offer(exception) }
                })
                var captured: Any? = null
                val end = SystemClock.elapsedRealtime() + 5_000
                try {
                    while (captured == null) {
                        check(!cancelled.get() && SystemClock.elapsedRealtime() < end) { "RAW frame timed out" }
                        captured = queue.poll(100, TimeUnit.MILLISECONDS)
                    }
                } finally {
                    accepting.set(false)
                    (queue.poll() as? ImageProxy)?.close()
                }
                if (captured is Exception) throw captured
                (captured as ImageProxy).use { image ->
                    check(image.format == android.graphics.ImageFormat.RAW_SENSOR && image.planes.size == 1)
                    if (index == 0) { width = image.width; height = image.height }
                    check(image.width == width && image.height == height && width.toLong() * height <= 16_000_000)
                    val result = config.captureResults.await(image.imageInfo.timestamp)
                    check(abs((result.get(R.SENSOR_EXPOSURE_TIME) ?: 0) - exposure) <= exposure / 100 &&
                        abs((result.get(R.SENSOR_SENSITIVITY) ?: 0) - iso) <= (iso / 50).coerceAtLeast(1)) { "Burst exposure changed" }
                    val plane = image.planes[0]
                    check(plane.pixelStride == 2)
                    val memory = ProcessingClient.memory("night-raw", plane.buffer.duplicate())
                    memories.add(memory)
                    check(memories.sumOf { it.size.toLong() } <= 240L * 1024 * 1024)
                    strides[index] = plane.rowStride
                    RawCameraMetadata.calibration(characteristics, result).copyInto(calibrations, index * 13)
                    shadingMaps[index] = RawCameraMetadata.shading(characteristics, result, width, height)
                    if (color == null) color = RawCameraMetadata.color(characteristics, result)
                    motions[index] = synchronized(listener) { motion }
                }
                context.mainExecutor.execute { progress(index + 1, count) }
            }
            }
            RawShadingData.encode(shadingMaps, width, height,
                characteristics.get(C.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: -1)?.let {
                shadingMemory = ProcessingClient.memory("raw-shading", it)
            }
            val payload = Bundle().apply {
                putFloat("zoom", captureZoom)
                putFloat("aspect", cropAspect)
                previewToneTargets?.let { putFloatArray("toneTargets", it) }
                previewColorReference?.let {
                    putIntArray("colorReference", it)
                    putInt("referenceRotation", referenceRotation)
                }
                putFloat("targetLuminance", targetLuminance)
                putInt("mode", 1); putParcelableArrayList("frames", memories)
                shadingMemory?.let { putParcelable("rawShading", it) }
                putInt("edgeAwareDemosaic", if(context.resources.getBoolean(app.grapheneos.camera.R.bool.config_camera_edge_aware_demosaic)) 1 else 0)
                putInt("frequencyDenoise", if(allowLongExposure && context.resources.getBoolean(app.grapheneos.camera.R.bool.config_camera_night_frequency_denoise)) 1 else 0)
                putInt("steadyPhotoFinish", if(steadyPhotoFinish) 1 else 0)
                putIntArray("strides", strides); putFloatArray("calibration", calibrations)
                putInt("width", width); putInt("height", height)
                putInt("cfa", characteristics.get(C.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: -1)
                if (config.lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_BACK &&
                    config.rawLenses().any {it.id==info.cameraId && it.label==app.grapheneos.camera.R.string.lens_ultra_wide}) {
                    RawCorrectionGrid.select(context.getString(app.grapheneos.camera.R.string.config_camera_ultra_wide_raw_correction_grid),
                        width,height,characteristics.get(C.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: -1)?.let {putIntArray("periodicGrid",it)}
                }
                putInt("reference", motions.indices.minByOrNull { motions[it] } ?: count / 2)
                val compensation = (oldExposure.toDouble() * meteredIso / (exposure.toDouble() * iso)).toFloat()
                putFloat("exposure", compensation.coerceIn(.25f, 16f)); putFloatArray("color", color); putInt("quality", config.photoQuality)
            }
            context.mainExecutor.execute { progress(count, count) }
            if (!direct) try { control.setCaptureRequestOptions(original).get(3, TimeUnit.SECONDS) } catch (_: Exception) { }
            sensor.unregisterListener(listener)
            val acquiredMs = SystemClock.elapsedRealtime() - started
            val profile = org.json.JSONObject().put("direct", direct).put("acquiredMs", acquiredMs)
                .put("width", width).put("height", height).put("frames", count).put("aspect", cropAspect)
                .put("colorSource", colorSource).put("edgeAwareDemosaic", payload.getInt("edgeAwareDemosaic")==1).put("frequencyDenoise", payload.getInt("frequencyDenoise")==1).put("iso", iso).put("meteredIso", meteredIso).put("sensorMaxIso", isoRange.upper)
                .put("exposureNs", exposure).put("steady", steady).put("steadyPhotoFinish", steadyPhotoFinish).put("targetLuminance", targetLuminance)
            directTimings.forEach { (key, value) -> profile.put(key, value) }
            transferred = true
            return AcquiredRawPhoto(payload, guide, profile)
        } finally {
            if (!transferred) { memories.forEach(SharedMemory::close); shadingMemory?.close(); acquiredGuide?.thumbnail?.recycle() }
            sensor.unregisterListener(listener)
            if (!direct) try { control.setCaptureRequestOptions(original).get(3, TimeUnit.SECONDS) } catch (_: Exception) { }
        }
    }
}
