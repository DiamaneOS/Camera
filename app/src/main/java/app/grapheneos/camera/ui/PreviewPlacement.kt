package app.grapheneos.camera.ui

object PreviewPlacement {
    /**
     * Whether a preview with the given width:height ratio should fill the window width from
     * [safeTop] down, under the controls, instead of fitting into the [controlsTop] tall area
     * above them. That is the case when it is too tall for that area at full width but still
     * fits below [safeTop].
     */
    fun fillsWidth(width: Int, height: Int, safeTop: Int, controlsTop: Int, ratio: String?): Boolean {
        val parts = ratio?.split(':') ?: return false
        if (parts.size != 2 || width <= 0) return false
        val w = parts[0].toFloatOrNull() ?: return false
        val h = parts[1].toFloatOrNull() ?: return false
        if (w <= 0f || h <= 0f) return false
        val fullWidthHeight = width * h / w
        return fullWidthHeight > controlsTop && fullWidthHeight <= height - safeTop
    }
}
