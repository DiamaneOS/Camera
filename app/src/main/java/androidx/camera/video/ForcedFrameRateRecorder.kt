package androidx.camera.video

import android.annotation.SuppressLint
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import android.util.Log
import androidx.camera.video.internal.encoder.Encoder
import androidx.camera.video.internal.encoder.EncoderConfig
import androidx.camera.video.internal.encoder.EncoderFactory
import androidx.camera.video.internal.encoder.EncoderImpl
import androidx.camera.video.internal.encoder.InvalidConfigException
import androidx.camera.video.internal.encoder.LevelVideoEncoderConfig
import androidx.camera.video.internal.encoder.VideoEncoderConfig
import androidx.camera.video.internal.utils.CodecUtil
import app.grapheneos.camera.capturer.ForcedFrameRate
import java.util.concurrent.Executor

private const val TAG = "ForcedFrameRate"

/**
 * Recorder.Builder.setVideoEncoderFactory() is package-private, so this is in CameraX's package.
 * Re-check it on every camera-video upgrade. If the method goes, the build fails here.
 */
@SuppressLint("RestrictedApi")
internal object ForcedFrameRateRecorder {
    /** Gives the recording an H.264 level that holds the forced 60 fps. Other recordings never come here. */
    fun useLevelForForcedRate(builder: Recorder.Builder) {
        builder.setVideoEncoderFactory(LevelEncoderFactory)
    }
}

@SuppressLint("RestrictedApi")
private object LevelEncoderFactory : EncoderFactory {
    override fun createEncoder(executor: Executor, encoderConfig: EncoderConfig, sessionType: Int): Encoder {
        val leveled = withLevel(encoderConfig)
        if (leveled === encoderConfig) return EncoderImpl(executor, encoderConfig, sessionType)
        // An encoder that refuses the level still records, at the level it picks itself.
        return try {
            EncoderImpl(executor, leveled, sessionType)
        } catch (exception: InvalidConfigException) {
            Log.w(TAG, "The encoder refused the level", exception)
            EncoderImpl(executor, encoderConfig, sessionType)
        }
    }
}

// Every way out says why, because a recording that silently keeps the encoder's level looks the same as one that works.
@SuppressLint("RestrictedApi")
private fun withLevel(config: EncoderConfig): EncoderConfig {
    if (config !is VideoEncoderConfig) {
        Log.i(TAG, "Not a video encoder config, no level: ${config.javaClass.simpleName}")
        return config
    }
    val size = config.resolution
    Log.i(TAG, "Encoder ${config.mimeType} ${size.width}x${size.height} profile ${config.profile} bit rate ${config.bitrate}")
    if (config.mimeType != MediaFormat.MIMETYPE_VIDEO_AVC) return config
    val level = ForcedFrameRate.avcLevel(size.width, size.height, config.bitrate, config.profile)
    if (level == null) {
        Log.i(TAG, "No level for profile ${config.profile} at ${size.width}x${size.height}")
        return config
    }
    // CameraX gives no profile on the FP6 and the encoder then picks High, so ask for High with the level.
    val unspecified = config.profile == ForcedFrameRate.PROFILE_UNSPECIFIED
    val profile = if (unspecified) CodecProfileLevel.AVCProfileHigh else config.profile
    // Only a level the encoder lists for this profile, so a codec that stops at a lower one is left alone.
    val listed = try {
        CodecUtil.findCodecAndGetCodecInfo(config.mimeType).getCapabilitiesForType(config.mimeType)
            .profileLevels.any { it.profile == profile && it.level >= level }
    } catch (exception: InvalidConfigException) {
        false
    } catch (exception: RuntimeException) {
        false
    }
    if (!listed) {
        Log.w(TAG, "The encoder does not list H.264 level ${level.toString(16)} for profile $profile")
        return config
    }
    Log.i(TAG, "H.264 level ${level.toString(16)} with profile $profile and frame rate ${ForcedFrameRate.RATE.upper} for ${size.width}x${size.height}")
    // The codec also hears the real frame rate, since it picks its level from it. The recorder's own
    // capture and encode rates stay 30, so timestamps and the slow motion check are unchanged.
    return LevelVideoEncoderConfig(config, level, profile.takeIf { unspecified }, ForcedFrameRate.RATE.upper)
}
