package app.grapheneos.camera

import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraFilter
import androidx.camera.core.CameraInfo

private const val TAG = "CamConfig"

// Narrows the cameras down to the one that can zoom out the furthest below 1x, which is
// how a camera combining several lenses offers the wider ones. The cameras are listed in
// the order of their IDs and the first of several equal ones is kept. Where no camera
// can zoom out the list is returned as it is, so that the default camera stays in use.
internal val ZOOM_OUT_CAMERA_FILTER = CameraFilter { cameraInfos ->
    val widest = cameraInfos.minByOrNull { minZoomRatio(it) }
    if (widest != null && minZoomRatio(widest) < 1f) {
        arrayListOf(widest)
    } else {
        ArrayList(cameraInfos)
    }
}

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
internal fun minZoomRatio(cameraInfo: CameraInfo): Float {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 1f

    return try {
        Camera2CameraInfo.from(cameraInfo).getCameraCharacteristic(
            CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE
        )?.lower ?: 1f
    } catch (exception: IllegalArgumentException) {
        Log.w(TAG, "Camera info carries no camera2 characteristics", exception)
        1f
    }
}
