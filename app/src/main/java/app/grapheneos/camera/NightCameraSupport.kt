package app.grapheneos.camera

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo

@SuppressLint("UnsafeOptInUsageError")
internal fun supportsNight(info: CameraInfo): Boolean {
    val c = Camera2CameraInfo.from(info)
    val caps = c.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
    return CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps &&
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps &&
        c.getCameraCharacteristic(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) != null &&
        c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) in 0..3 &&
        c.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)?.any {
                app.grapheneos.camera.capturer.ProcessingGeometry.supported(it.width,it.height,raw=true)
            } == true
}
