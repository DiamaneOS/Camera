package app.grapheneos.camera.capturer

/** Convert between a physical wide sensor's local zoom and the app's main-relative zoom. */
internal object PhotoZoomMapping {
    data class Range(val minimum: Float, val maximum: Float) {
        init { require(minimum.isFinite() && maximum.isFinite() && minimum > 0 && maximum >= minimum) }
    }

    fun toLens(displayZoom: Float, lensScale: Float): Float {
        require(displayZoom.isFinite() && displayZoom > 0 && lensScale.isFinite() && lensScale > 0)
        return displayZoom / lensScale
    }
    fun toDisplay(lensZoom: Float, lensScale: Float): Float {
        require(lensZoom.isFinite() && lensZoom > 0 && lensScale.isFinite() && lensScale > 0)
        return lensZoom * lensScale
    }

    fun combinedRange(native: Range, scale: Float, companions: List<Range>): Range {
        val local = Range(toDisplay(native.minimum, scale), toDisplay(native.maximum, scale))
        return Range(companions.minOfOrNull { it.minimum }?.coerceAtMost(local.minimum) ?: local.minimum,
            companions.maxOfOrNull { it.maximum }?.coerceAtLeast(local.maximum) ?: local.maximum)
    }

    // A constrained high-speed session does not establish that a logical camera
    // can route to its wider physical lens. Only expose the bound lens and crops.
    fun highSpeedRange(native: Range) = Range(maxOf(1f, native.minimum), maxOf(1f, native.maximum))

    fun useMainLens(displayZoom: Float, currentlyMain: Boolean, gesture: Boolean): Boolean =
        if (gesture) (if (currentlyMain) displayZoom >= .92f else displayZoom >= 1.04f)
        else displayZoom >= 1f
}
