package app.grapheneos.camera.capturer

import android.hardware.camera2.CameraCharacteristics as C
import android.annotation.SuppressLint
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import app.grapheneos.camera.R

/** Select a real RAW sensor, rather than relying on a logical camera's preview routing. */
@SuppressLint("UnsafeOptInUsageError")
data class RawLens(val id: String, val label: Int, val info: CameraInfo) {
    val selector: CameraSelector get() = CameraSelector.Builder().addCameraFilter { infos ->
        infos.filter { Camera2CameraInfo.from(it).cameraId == id }
    }.build()
}

@SuppressLint("UnsafeOptInUsageError")
object RawLensCatalog {
    fun lenses(infos: List<CameraInfo>, facing: Int): List<RawLens> {
        data class Sensor(val id: String, val info: CameraInfo, val focal: Double, val pixels: Long)
        val sensors = infos.mapNotNull { info ->
            val c = Camera2CameraInfo.from(info)
            val caps = c.getCameraCharacteristic(C.REQUEST_AVAILABLE_CAPABILITIES) ?: return@mapNotNull null
            if (c.getCameraCharacteristic(C.LENS_FACING) != facing ||
                C.REQUEST_AVAILABLE_CAPABILITIES_RAW !in caps ||
                C.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps) return@mapNotNull null
            val focal = c.getCameraCharacteristic(C.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return@mapNotNull null
            val size = c.getCameraCharacteristic(C.SENSOR_INFO_PHYSICAL_SIZE) ?: return@mapNotNull null
            val pixels = c.getCameraCharacteristic(C.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return@mapNotNull null
            Sensor(c.cameraId, info, focal / size.width.toDouble(), pixels.width.toLong() * pixels.height)
        }.distinctBy { Pair((it.focal * 1000).toInt(), it.pixels) }
        if (sensors.isEmpty()) return emptyList()
        // Resolution is not a lens role: an ultra-wide can have more output pixels than
        // the binned main sensor. Match the default camera's calibrated field of view.
        val default = infos.firstOrNull { Camera2CameraInfo.from(it).getCameraCharacteristic(C.LENS_FACING) == facing }
        val defaultInfo = default?.let { Camera2CameraInfo.from(it) }
        val focal = defaultInfo?.getCameraCharacteristic(C.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val size = defaultInfo?.getCameraCharacteristic(C.SENSOR_INFO_PHYSICAL_SIZE)
        val reference = if (focal != null && size != null) focal / size.width.toDouble() else sensors.first().focal
        val main = sensors.minBy { kotlin.math.abs(it.focal - reference) }
        return sensors.sortedBy { it.focal }.map { sensor ->
            RawLens(sensor.id, when {
                sensor == main -> R.string.lens_main
                sensor.focal < main.focal * .85 -> R.string.lens_ultra_wide
                sensor.focal > main.focal * 1.15 -> R.string.lens_telephoto
                else -> R.string.lens_other
            }, sensor.info)
        }.sortedBy { if (it.label == R.string.lens_main) 0 else 1 }
    }
}
