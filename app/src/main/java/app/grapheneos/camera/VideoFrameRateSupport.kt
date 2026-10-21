package app.grapheneos.camera

import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraInfo
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.featuregroup.GroupableFeature
import androidx.camera.core.DynamicRange
import androidx.camera.video.HighSpeedVideoSessionConfig
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import app.grapheneos.camera.capturer.VideoFrameRate

private const val TAG = "CamConfig"

internal fun videoQualityPreference(quality: Quality): String = when (quality) {
    Quality.UHD -> "UHD"
    Quality.FHD -> "FHD"
    Quality.HD -> "HD"
    Quality.SD -> "SD"
    else -> "HIGHEST"
}

internal data class VideoSessionProfile(
    val useCases: List<UseCase>,
    val features: Set<GroupableFeature>,
    val frameRates: Set<VideoFrameRate>,
)

internal fun querySlowMotionFrameRates(cameraInfo: CameraInfo, quality: Quality): List<VideoFrameRate> {
    val capabilities = Recorder.getHighSpeedVideoCapabilities(cameraInfo) ?: return emptyList()
    if (quality !in capabilities.getSupportedQualities(DynamicRange.SDR)) return emptyList()
    val probe = HighSpeedVideoSessionConfig(
        VideoCapture.withOutput(
            Recorder.Builder().setQualitySelector(QualitySelector.from(quality)).build()
        ),
        Preview.Builder().build(),
    )
    // The camera can advertise rates the hardware encoder cannot take at this size.
    val size = QualitySelector.getResolution(cameraInfo, quality)
    return try {
        cameraInfo.getSupportedFrameRateRanges(probe)
            .filter { it.lower == it.upper && it.upper >= VideoFrameRate.SLOW_MOTION_MIN }
            .filter { size != null && hardwareEncoderSupports(size, it.upper) }
            .map { VideoFrameRate(it.lower, it.upper) }
            .distinct().sortedBy { it.upper }
    } catch (exception: IllegalArgumentException) {
        Log.w(TAG, "High-speed recording cannot be queried", exception)
        emptyList()
    } catch (exception: UnsupportedOperationException) {
        Log.w(TAG, "High-speed recording cannot be queried", exception)
        emptyList()
    }
}

internal fun hardwareEncoderSupports(size: Size, fps: Int): Boolean =
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
        info.isEncoder && info.isHardwareAccelerated &&
            MediaFormat.MIMETYPE_VIDEO_AVC in info.supportedTypes &&
            info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                ?.areSizeAndRateSupported(size.width, size.height, fps.toDouble()) == true
    }
