package app.grapheneos.camera.capturer

import android.media.MediaCodecInfo.CodecProfileLevel

/**
 * 60 fps for a camera that records it in an ordinary session without advertising it. The camera
 * qualifies when it advertises nothing at 60 and runs a high-speed session at the same quality,
 * which shows that a sensor mode this fast exists.
 */
internal object ForcedFrameRate {
    val RATE = VideoFrameRate(60, 60)

    fun offered(maxAdvertised: Int, highSpeed: List<VideoFrameRate>): VideoFrameRate? =
        RATE.takeIf { maxAdvertised < RATE.upper && highSpeed.isNotEmpty() }

    /** What a frame rate choice does to the stored forced rate. */
    enum class Stored { SET, CLEAR, KEEP }

    /**
     * The forced rate belongs to video mode. A forced choice stores it, another video mode choice
     * clears it, and a choice in another mode leaves it as it is.
     */
    fun storedAfterChoice(videoMode: Boolean, forced: Boolean): Stored = when {
        forced -> Stored.SET
        videoMode -> Stored.CLEAR
        else -> Stored.KEEP
    }

    // H.264 Annex A, table A-1: the level, its macroblocks per second, its frame size in macroblocks and
    // its bit rate in kbit/s for Baseline, Main and Extended. High allows 1.25 times that.
    private class AvcLevel(val id: Int, val maxMbps: Int, val maxFs: Int, val maxKbps: Int)

    private val AVC_LEVELS = listOf(
        AvcLevel(CodecProfileLevel.AVCLevel1, 1485, 99, 64),
        AvcLevel(CodecProfileLevel.AVCLevel11, 3000, 396, 192),
        AvcLevel(CodecProfileLevel.AVCLevel12, 6000, 396, 384),
        AvcLevel(CodecProfileLevel.AVCLevel13, 11880, 396, 768),
        AvcLevel(CodecProfileLevel.AVCLevel2, 11880, 396, 2000),
        AvcLevel(CodecProfileLevel.AVCLevel21, 19800, 792, 4000),
        AvcLevel(CodecProfileLevel.AVCLevel22, 20250, 1620, 4000),
        AvcLevel(CodecProfileLevel.AVCLevel3, 40500, 1620, 10000),
        AvcLevel(CodecProfileLevel.AVCLevel31, 108000, 3600, 14000),
        AvcLevel(CodecProfileLevel.AVCLevel32, 216000, 5120, 20000),
        AvcLevel(CodecProfileLevel.AVCLevel4, 245760, 8192, 20000),
        AvcLevel(CodecProfileLevel.AVCLevel41, 245760, 8192, 50000),
        AvcLevel(CodecProfileLevel.AVCLevel42, 522240, 8704, 50000),
        AvcLevel(CodecProfileLevel.AVCLevel5, 589824, 22080, 135000),
        AvcLevel(CodecProfileLevel.AVCLevel51, 983040, 36864, 240000),
        AvcLevel(CodecProfileLevel.AVCLevel52, 2073600, 36864, 240000),
    )

    // CameraX's EncoderConfig.CODEC_PROFILE_NONE: the config names no profile and the encoder picks one.
    const val PROFILE_UNSPECIFIED = -1

    /**
     * The lowest H.264 level that holds this stream at the forced rate. CameraX still tells the
     * encoder 30 fps, so the encoder picks a level for 30 fps (4.1 at 1080p), and 1080p at 60 fps
     * needs 4.2. CameraX gives no profile on the FP6, so an unspecified profile gets the stricter
     * Baseline and Main bit rate allowance, which holds for whatever the encoder picks. Null for a
     * profile other than those and High, and above level 5.2.
     */
    fun avcLevel(width: Int, height: Int, bitrate: Int, profile: Int): Int? {
        val bpsPerKbps = when (profile) {
            PROFILE_UNSPECIFIED, CodecProfileLevel.AVCProfileBaseline, CodecProfileLevel.AVCProfileMain -> 1000L
            CodecProfileLevel.AVCProfileHigh -> 1250L
            else -> return null
        }
        if (width <= 0 || height <= 0 || bitrate <= 0) return null
        val widthMbs = (width + 15) / 16
        val heightMbs = (height + 15) / 16
        val frameMbs = widthMbs * heightMbs
        val mbps = frameMbs.toLong() * RATE.upper
        return AVC_LEVELS.firstOrNull {
            // The frame must also fit the level's width and height limit, sqrt(maxFs * 8) macroblocks.
            mbps <= it.maxMbps && frameMbs <= it.maxFs &&
                widthMbs * widthMbs <= it.maxFs * 8 && heightMbs * heightMbs <= it.maxFs * 8 &&
                bitrate <= it.maxKbps * bpsPerKbps
        }?.id
    }
}
