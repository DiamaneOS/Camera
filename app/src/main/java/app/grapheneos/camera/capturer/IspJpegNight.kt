package app.grapheneos.camera.capturer

import android.content.Context
import app.grapheneos.camera.BuildConfig
import app.grapheneos.camera.R

/** Whether merged camera JPEGs (processing mode 4) may be acquired and processed. */
internal object IspJpegNight {
    fun enabled(context: Context): Boolean =
        context.resources.getBoolean(R.bool.config_camera_night_isp_jpeg) ||
            (BuildConfig.DEBUG && BuildConfig.CAMERA_JPEG_BURST_EVALUATION)
}
