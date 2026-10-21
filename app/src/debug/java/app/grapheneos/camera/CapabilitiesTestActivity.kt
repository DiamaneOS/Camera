package app.grapheneos.camera

import android.app.Activity
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CameraManager
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/** Explicit development probe: public capability flags only, no camera/device identifiers. */
class CapabilitiesTestActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        thread {
            val rows=JSONArray();val manager=getSystemService(CameraManager::class.java)
            for(id in manager.cameraIdList) {
                val c=manager.getCameraCharacteristics(id);val map=c.get(C.SCALER_STREAM_CONFIGURATION_MAP)
                val caps=c.get(C.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val row=JSONObject().put("facing",c.get(C.LENS_FACING))
                    .put("capabilities",JSONArray(caps.toList()))
                    .put("focals",JSONArray(c.get(C.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty()))
                    .put("noiseModes",JSONArray(c.get(C.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toList().orEmpty()))
                    .put("sceneModes",JSONArray(c.get(C.CONTROL_AVAILABLE_SCENE_MODES)?.toList().orEmpty()))
                    .put("hotPixelModes",JSONArray(c.get(C.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)?.toList().orEmpty()))
                    .put("hotPixelMapModes",JSONArray(c.get(C.STATISTICS_INFO_AVAILABLE_HOT_PIXEL_MAP_MODES)?.toList().orEmpty()))
                    .put("depthExclusive",c.get(C.DEPTH_DEPTH_IS_EXCLUSIVE))
                val depthWords=Regex("depth|tof|laser|distance",RegexOption.IGNORE_CASE)
                row.put("depthRelatedResultKeyNames",JSONArray(c.availableCaptureResultKeys.orEmpty()
                    .map {it.name}.filter {depthWords.containsMatchIn(it)}))
                row.put("depthRelatedRequestKeyNames",JSONArray(c.availableCaptureRequestKeys.orEmpty()
                    .map {it.name}.filter {depthWords.containsMatchIn(it)}))
                row.put("depthRelatedCharacteristicKeyNames",JSONArray(c.keys
                    .map {it.name}.filter {depthWords.containsMatchIn(it)}))
                fun photoSizes(format:Int,high:Boolean=false):JSONArray {
                    val sizes=try {if(high)map?.getHighResolutionOutputSizes(format) else map?.getOutputSizes(format)} catch(_:IllegalArgumentException) {null}
                    return JSONArray(sizes?.map {"${it.width}x${it.height}"}.orEmpty())
                }
                row.put("jpegSizes",photoSizes(ImageFormat.JPEG)).put("highResolutionJpegSizes",photoSizes(ImageFormat.JPEG,true))
                row.put("rawSizes",photoSizes(ImageFormat.RAW_SENSOR))
                val maximum=c.get(C.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                row.put("maximumResolutionJpegSizes",JSONArray(try {maximum?.getOutputSizes(ImageFormat.JPEG)?.map {"${it.width}x${it.height}"}.orEmpty()} catch(_:IllegalArgumentException) {emptyList<String>()}))
                try {
                    row.put("extensions",JSONArray(manager.getCameraExtensionCharacteristics(id).supportedExtensions))
                } catch(error:Exception) {row.put("extensionQueryError",error.javaClass.simpleName)}
                for((format,name) in listOf(ImageFormat.DEPTH16 to "depth16",ImageFormat.DEPTH_POINT_CLOUD to "depthPoints")) {
                    val sizes=try {map?.getOutputSizes(format)?.map {"${it.width}x${it.height}"}.orEmpty()} catch (_: IllegalArgumentException) {emptyList()}
                    row.put(name,JSONArray(sizes))
                }
                rows.put(row)
            }
            File(File(filesDir,"processing-test").apply { mkdirs() },"camera-capabilities.json").writeText(rows.toString(2))
            val last=app.grapheneos.camera.capturer.PortraitPreviewDiagnostics
            val metadata=JSONObject().put("ageMs",android.os.SystemClock.elapsedRealtime()-last.capturedAtMs)
                .put("exposureNs",last.exposureNs).put("frameDurationNs",last.frameDurationNs)
                .put("noiseReductionMode",last.noiseReductionMode).put("edgeMode",last.edgeMode).put("iso",last.iso)
                .put("boost",last.boost).put("aeMode",last.aeMode)
                .put("spatialLuma",last.spatialLumaApplied)
                .put("fpsLower",last.fpsLower).put("fpsUpper",last.fpsUpper)
                .put("stillAgeMs",android.os.SystemClock.elapsedRealtime()-last.stillCapturedAtMs).put("stillNoiseReductionMode",last.stillNoiseReductionMode)
                .put("stillExposureNs",last.stillExposureNs).put("stillIso",last.stillIso)
                .put("stillBoost",last.stillBoost).put("stillAeMode",last.stillAeMode)
            File(File(filesDir,"processing-test").apply { mkdirs() },"preview-public-metadata.json").writeText(metadata.toString(2))
            runOnUiThread {finish()}
        }
    }
}
