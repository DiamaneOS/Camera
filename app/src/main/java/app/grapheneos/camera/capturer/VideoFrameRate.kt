package app.grapheneos.camera.capturer

/** An exact camera-advertised range, not a range inferred from its maximum FPS. */
data class VideoFrameRate(val lower: Int, val upper: Int) {
    init {
        require(lower > 0 && upper >= lower)
    }

    fun preferenceValue(): String = "$lower:$upper"

    /** A fixed high-speed rate, recorded in a constrained high-speed session as slow motion. */
    val isSlowMotion: Boolean get() = lower == upper && upper >= SLOW_MOTION_MIN

    companion object {
        const val SLOW_MOTION_MIN = 120

        fun fromPreference(value: String?): VideoFrameRate? {
            val parts = value?.split(':') ?: return null
            if (parts.size != 2) return null
            val lower = parts[0].toIntOrNull() ?: return null
            val upper = parts[1].toIntOrNull() ?: return null
            return if (lower > 0 && upper >= lower) VideoFrameRate(lower, upper) else null
        }

        fun supportedOptions(profiles: List<Set<VideoFrameRate>>): List<VideoFrameRate> =
            profiles.flatten().distinct().sortedWith(compareBy({ it.upper }, { -it.lower }))

        fun selectProfile(profiles: List<Set<VideoFrameRate>>, requested: VideoFrameRate?): Int? =
            profiles.indices.firstOrNull { requested == null || requested in profiles[it] }
    }
}
