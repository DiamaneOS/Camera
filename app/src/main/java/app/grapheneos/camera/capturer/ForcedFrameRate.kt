package app.grapheneos.camera.capturer

/**
 * 60 fps for a camera that records it in an ordinary session without advertising it. The camera
 * qualifies when it advertises nothing at 60 and runs a high-speed session at the same quality,
 * which shows that a sensor mode this fast exists.
 */
internal object ForcedFrameRate {
    val RATE = VideoFrameRate(60, 60)

    fun offered(maxAdvertised: Int, highSpeed: List<VideoFrameRate>): VideoFrameRate? =
        RATE.takeIf { maxAdvertised < RATE.upper && highSpeed.isNotEmpty() }
}
