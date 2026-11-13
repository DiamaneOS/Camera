package app.grapheneos.camera.capturer

/**
 * Lens buttons from the zoom range the bound camera reports for the current mode. That range
 * already leaves out lenses the mode cannot use (high-speed sessions, Night without a wide lens,
 * a front camera without one), so a button is only offered where its zoom can be reached.
 */
internal object LensPresets {
    /** Below this the wide button stands for a wider lens, not a rounding of 1x. */
    private const val WIDE_LIMIT = .95f

    fun choose(minimum: Float, maximum: Float): List<Float> {
        if (!minimum.isFinite() || !maximum.isFinite() || minimum <= 0f || maximum < minimum) return emptyList()
        val presets = buildList {
            if (minimum < WIDE_LIMIT) add(minimum)
            if (minimum <= 1f && maximum >= 1f) add(1f)
            if (maximum >= 2f) add(2f)
        }
        // A bar with nothing but 1x has nothing to choose.
        return if (presets.size > 1) presets else emptyList()
    }
}
