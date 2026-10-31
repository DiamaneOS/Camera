package androidx.camera.video.internal.encoder

import android.annotation.SuppressLint
import android.media.MediaFormat
import android.util.Size
import androidx.camera.core.impl.Timebase

/**
 * CameraX's video encoder config with the codec level set. CameraX never sets one, so the encoder
 * picks it from the frame rate CameraX gives it. VideoEncoderConfig has a package-private
 * constructor, which is why this class lives in CameraX's package. Re-check it on every
 * camera-video upgrade.
 */
@SuppressLint("RestrictedApi")
internal class LevelVideoEncoderConfig(
    private val base: VideoEncoderConfig,
    private val level: Int,
    // Set when the base config has no profile, so the profile and the level reach the codec as a pair.
    private val profile: Int? = null,
    // The real frame rate for the codec. The getters below keep CameraX's 1:1 capture to encode ratio, so
    // the timestamps stay as they are.
    private val frameRate: Int? = null,
) : VideoEncoderConfig() {
    override fun toBuilder(): VideoEncoderConfig.Builder = base.toBuilder()
    override fun getMimeType(): String = base.getMimeType()
    override fun getProfile(): Int = base.getProfile()
    override fun getInputTimebase(): Timebase = base.getInputTimebase()
    override fun getResolution(): Size = base.getResolution()
    override fun getColorFormat(): Int = base.getColorFormat()
    override fun getDataSpace(): VideoEncoderDataSpace = base.getDataSpace()
    override fun getCaptureFrameRate(): Int = base.getCaptureFrameRate()
    override fun getEncodeFrameRate(): Int = base.getEncodeFrameRate()
    override fun getIFrameInterval(): Int = base.getIFrameInterval()
    override fun getBitrate(): Int = base.getBitrate()

    override fun toMediaFormat(): MediaFormat = base.toMediaFormat().apply {
        profile?.let { setInteger(MediaFormat.KEY_PROFILE, it) }
        frameRate?.let { setInteger(MediaFormat.KEY_FRAME_RATE, it) }
        setInteger(MediaFormat.KEY_LEVEL, level)
    }
}
