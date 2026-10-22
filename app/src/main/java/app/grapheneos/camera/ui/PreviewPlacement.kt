package app.grapheneos.camera.ui

object PreviewPlacement {
    /**
     * Whether a preview with the given width:height ratio should fill the window width and end at
     * [tabsTop], above the mode tabs and under the capture row, instead of fitting into the
     * [controlsTop] tall area above the controls. That is the case when it is too tall for that
     * area at full width but still fits between [safeTop] and [tabsTop].
     */
    fun fillsWidth(width: Int, safeTop: Int, controlsTop: Int, tabsTop: Int, ratio: String?): Boolean {
        val parts = ratio?.split(':') ?: return false
        if (parts.size != 2 || width <= 0) return false
        val w = parts[0].toFloatOrNull() ?: return false
        val h = parts[1].toFloatOrNull() ?: return false
        if (w <= 0f || h <= 0f) return false
        val fullWidthHeight = width * h / w
        return fullWidthHeight > controlsTop && fullWidthHeight <= tabsTop - safeTop
    }
}
