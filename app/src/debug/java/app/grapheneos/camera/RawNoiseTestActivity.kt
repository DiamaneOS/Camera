package app.grapheneos.camera

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.*
import android.widget.TextView
import app.grapheneos.camera.capturer.NightMotionWindow
import app.grapheneos.camera.capturer.RawCameraMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs

/** Explicit DUMP-protected, bounded RAW temporal-noise measurement. No camera IDs,
 * full images, public media, or backup-eligible diagnostic files are written.
 */
@SuppressLint("MissingPermission")
class RawNoiseTestActivity : Activity() {
    private val cancelled = AtomicBoolean()
    override fun onStop() { cancelled.set(true); super.onStop() }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val label = TextView(this).apply { text = "Measuring RAW noise in the fixed scene…" }
        setContentView(label)
        thread(name = "raw-noise-diagnostic") {
            val name = intent.getStringExtra("testLabel") ?: "flat-hq"
            if (!name.matches(Regex("[a-z0-9-]{1,32}"))) return@thread
            val resultFile = File(noBackupFilesDir, "raw-noise-$name.json")
            if (resultFile.exists()) return@thread
            val report = JSONObject()
            val started = SystemClock.elapsedRealtime()
            val worker = HandlerThread("raw-noise-camera").apply { start() }
            val handler = Handler(worker.looper)
            val closed = AtomicBoolean()
            val cameraClosed = CountDownLatch(1)
            val images = ArrayBlockingQueue<Any>(4)
            val results = ConcurrentHashMap<Long, CaptureResult>()
            var device: CameraDevice? = null
            var session: CameraCaptureSession? = null
            var reader: ImageReader? = null
            var preview: ImageReader? = null
            var openRequested = false
            val sensors = getSystemService(SensorManager::class.java)
            val motion = NightMotionWindow()
            val listener = object : SensorEventListener {
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                override fun onSensorChanged(event: SensorEvent) {
                    motion.record(event.timestamp, event.values.sumOf { it.toDouble() * it })
                }
            }
            val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            val monitoring = gyro != null && sensors.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
            try {
                fun active() { check(!cancelled.get() && SystemClock.elapsedRealtime() - started < 30_000) { "Diagnostic cancelled or timed out" } }
                val manager = getSystemService(CameraManager::class.java)
                val choices = manager.cameraIdList.map { id -> id to manager.getCameraCharacteristics(id) }.filter { (_, c) ->
                    val caps = (c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf())
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                        caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) &&
                        caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
                }
                val physical = choices.filter { (_, c) -> !(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf())
                    .contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) }
                val chosen = (physical.ifEmpty { choices }).minByOrNull { (_, c) ->
                    val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                    val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    if (focal != null && sensor != null && sensor.width > 0) focal / sensor.width else Float.MAX_VALUE
                } ?: error("No public manual RAW back camera")
                val characteristics = chosen.second
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: error("No stream map")
                val size = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.filter { it.width >= 128 && it.height >= 128 && it.width.toLong() * it.height <= 16_000_000 }
                    ?.maxByOrNull { it.width.toLong() * it.height } ?: error("No bounded RAW stream")
                val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: error("No exposure range")
                val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: error("No ISO range")
                val exposure = 30_000_000L.coerceIn(exposureRange.lower, exposureRange.upper)
                val iso = (characteristics.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)
                    ?: isoRange.upper).coerceIn(isoRange.lower, isoRange.upper)
                report.put("requestedIso", iso).put("requestedExposureNs", exposure)
                val target = 32
                val opened = ArrayBlockingQueue<Any>(1)
                manager.openCamera(chosen.first, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) { if (closed.get() || !opened.offer(camera)) camera.close() }
                    override fun onDisconnected(camera: CameraDevice) { camera.close(); opened.offer(IllegalStateException("Camera disconnected")); images.offer(IllegalStateException("Camera disconnected")) }
                    override fun onError(camera: CameraDevice, error: Int) { camera.close(); opened.offer(IllegalStateException("Camera refused diagnostic")); images.offer(IllegalStateException("Camera refused diagnostic")) }
                    override fun onClosed(camera: CameraDevice) { cameraClosed.countDown() }
                }, handler)
                openRequested = true
                val value = opened.poll(5, TimeUnit.SECONDS)
                device = value as? CameraDevice ?: throw (value as? Exception ?: IllegalStateException("Camera open timed out"))
                reader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 3).apply {
                    setOnImageAvailableListener({ source ->
                        try { source.acquireNextImage()?.let { image -> if (closed.get() || cancelled.get() || !images.offer(image)) image.close() } }
                        catch (_: IllegalStateException) { }
                    }, handler)
                }
                val previewSize = map.getOutputSizes(ImageFormat.YUV_420_888)?.filter { it.width <= 1280 }
                    ?.minByOrNull { abs(it.width.toDouble() / it.height - size.width.toDouble() / size.height) }
                    ?: error("No bounded warm-up stream")
                preview = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2).apply {
                    setOnImageAvailableListener({ source ->
                        try { source.acquireLatestImage()?.close() } catch (_: IllegalStateException) { }
                    }, handler)
                }
                val configured = ArrayBlockingQueue<Any>(1)
                device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(reader.surface), OutputConfiguration(preview.surface)),
                    { action -> handler.post(action) }, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { if (closed.get() || !configured.offer(s)) s.close() }
                        override fun onConfigureFailed(s: CameraCaptureSession) { configured.offer(IllegalStateException("RAW diagnostic session refused")) }
                    }))
                val configuredValue = configured.poll(5, TimeUnit.SECONDS)
                session = configuredValue as? CameraCaptureSession ?: throw (configuredValue as? Exception ?: IllegalStateException("Session timed out"))
                // Match the validated still-capture graph: warm an existing YUV
                // stream, then issue bounded RAW bursts rather than RAW repetition.
                val warmed = CountDownLatch(4)
                val warmRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(preview.surface)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    if (characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.contains(CaptureRequest.CONTROL_AF_MODE_OFF) == true) {
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                    }
                }.build()
                session.setRepeatingRequest(warmRequest, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) { warmed.countDown() }
                }, handler)
                check(warmed.await(3, TimeUnit.SECONDS)) { "Diagnostic warm-up did not start" }
                active(); session.stopRepeating()
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
                    set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exposure, map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, size)))
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    if (characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.contains(CaptureRequest.CONTROL_AF_MODE_OFF) == true) {
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                    }
                    if (characteristics.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)?.contains(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY) == true)
                        set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
                    set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                    set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                }.build()
                val rawCallback = object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                        result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { results[it] = result }
                        if (results.size > 16) results.keys.minOrNull()?.let { results.remove(it) }
                    }
                    override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) { images.offer(IllegalStateException("RAW diagnostic frame failed")) }
                }
                val left = 64; val top = 64
                val width = minOf(960, size.width - left) / 2 * 2; val height = minOf(960, size.height - top) / 2 * 2
                val mean = FloatArray(width * height); val squares = FloatArray(mean.size)
                val frameMeans = JSONArray()
                var count = 0; var rejected = 0; var calibration: FloatArray? = null
                var measuredIso: Int? = null; var measuredExposure: Long? = null
                var remaining = 0
                val power = getSystemService(PowerManager::class.java)
                while (count < target) {
                    report.put("frames", count).put("rejectedFrames", rejected)
                    active()
                    if (remaining == 0) {
                        // Analysis is deliberately slower than copying a RAW frame.
                        // Keep at most two requests in flight, below the three-buffer
                        // limit, while the ROI statistics are being updated.
                        remaining = minOf(2, target - count)
                        session.captureBurst(List(remaining) { request }, rawCallback, handler)
                    }
                    if (count % 8 == 0) check(power.currentThermalStatus < PowerManager.THERMAL_STATUS_MODERATE) { "Cooling required" }
                    val next = images.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    if (next is Exception) throw next
                    remaining--
                    (next as Image).use { image ->
                        var metadata = results.remove(image.timestamp)
                        val deadline = SystemClock.elapsedRealtime() + 1500
                        while (metadata == null) { active(); check(SystemClock.elapsedRealtime() < deadline) { "Frame metadata missing" }; Thread.sleep(5); metadata = results.remove(image.timestamp) }
                        val actualExposure = metadata.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: error("Missing exposure")
                        val actualIso = metadata.get(CaptureResult.SENSOR_SENSITIVITY) ?: error("Missing sensitivity")
                        report.put("lastActualIso", actualIso).put("lastActualExposureNs", actualExposure)
                        check(abs(actualExposure - exposure) <= exposure / 20 &&
                            abs(actualIso - iso) <= maxOf(1, iso / 10)) { "Manual RAW settings outside diagnostic tolerance" }
                        // Public requests may be quantized by the sensor. Report the
                        // measured values and compare subsequent frames with those.
                        if (measuredIso == null) { measuredIso = actualIso; measuredExposure = actualExposure }
                        if (abs(actualExposure - measuredExposure!!) > measuredExposure!! / 100 ||
                            abs(actualIso - measuredIso!!) > maxOf(1, measuredIso!! / 50)) { rejected++; return@use }
                        check(image.format == ImageFormat.RAW_SENSOR && image.width == size.width && image.height == size.height && image.planes.size == 1)
                        val plane = image.planes[0]
                        check(plane.pixelStride == 2 && plane.rowStride in image.width * 2..image.width * 2 + 4096 && plane.rowStride % 2 == 0)
                        check(plane.buffer.remaining().toLong() >= plane.rowStride.toLong() * (image.height - 1) + image.width * 2)
                        val data = plane.buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                        count++
                        val sums = DoubleArray(4)
                        for (y in 0 until height) for (x in 0 until width) {
                            val i = y * width + x
                            val sample = (data.getShort((top + y) * plane.rowStride + (left + x) * 2).toInt() and 65535).toFloat()
                            val delta = sample - mean[i]; mean[i] += delta / count; squares[i] += delta * (sample - mean[i])
                            sums[(y % 2) * 2 + x % 2] += sample
                        }
                        frameMeans.put(JSONArray(sums.map { it / (width * height / 4) }))
                        if (calibration == null) {
                            calibration = RawCameraMetadata.calibration(characteristics, metadata)
                            report.put("actualHotPixelMode", metadata.get(CaptureResult.HOT_PIXEL_MODE))
                                .put("actualAfMode", metadata.get(CaptureResult.CONTROL_AF_MODE))
                                .put("actualFocusDistance", metadata.get(CaptureResult.LENS_FOCUS_DISTANCE))
                                .put("actualNoiseReductionMode", metadata.get(CaptureResult.NOISE_REDUCTION_MODE))
                        }
                    }
                }
                session.stopRepeating()
                fun save(suffix: String, values: FloatArray) {
                    val file = File(noBackupFilesDir, "raw-noise-$name-$suffix.bin")
                    check(!file.exists()) { "Diagnostic output already exists" }
                    DataOutputStream(file.outputStream().buffered()).use { output -> values.forEach { output.writeFloat(it) } }
                }
                for (i in squares.indices) squares[i] /= count - 1
                save("mean", mean); save("variance", squares)
                report.put("status", "ok").put("frames", count).put("rejectedFrames", rejected)
                    .put("rawWidth", size.width).put("rawHeight", size.height).put("cfa", characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT))
                    .put("roiLeft", left).put("roiTop", top).put("roiWidth", width).put("roiHeight", height)
                    .put("exposureNs", measuredExposure).put("iso", measuredIso).put("calibration", JSONArray(calibration!!.toList()))
                    .put("frameMeanByCfaSite", frameMeans)
                    .put("gyroAvailable", monitoring).put("gyroSteady", monitoring && motion.isSteadyAt(SystemClock.elapsedRealtimeNanos()))
                    .put("binaryFormat", "big-endian float32, temporal mean and unbiased sample variance of raw sensor codes")
            } catch (error: Exception) {
                report.put("status", "error").put("error", error.javaClass.simpleName + ": " + error.message)
            } finally {
                closed.set(true); sensors.unregisterListener(listener)
                try { session?.abortCaptures(); session?.close() } catch (_: Exception) { }
                device?.close()
                val released = !openRequested || cameraClosed.await(3, TimeUnit.SECONDS)
                while (true) (images.poll() ?: break).let { (it as? Image)?.close() }
                reader?.close(); preview?.close(); worker.quitSafely(); worker.join(1000)
                report.put("cameraClosed", released).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                val temporary = File(noBackupFilesDir, "raw-noise-$name.tmp")
                temporary.writeText(report.toString(2)); check(temporary.renameTo(resultFile))
                runOnUiThread { label.text = if (report.optString("status") == "ok") "RAW noise measurement complete" else "RAW noise measurement stopped" }
            }
        }
    }
}
