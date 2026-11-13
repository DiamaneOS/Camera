package app.grapheneos.camera.ui

object LensPlacement {
    /**
     * How far above the preview's bottom the lens row ends, in px. The row stays above the settings
     * arrow, which sits above the capture row. While the arrow isn't laid out, before the camera is
     * bound and while it's blocked, its place is [arrowSpace] above [controlsTop], so the row doesn't
     * move when the arrow appears. [gap] separates the row from the arrow. Positions are window px.
     */
    fun bottomMargin(previewBottom: Int, arrowTop: Int?, controlsTop: Int, arrowSpace: Int, gap: Int): Int {
        val limit = arrowTop ?: (controlsTop - arrowSpace)
        return (previewBottom - limit).coerceAtLeast(0) + gap
    }
}
