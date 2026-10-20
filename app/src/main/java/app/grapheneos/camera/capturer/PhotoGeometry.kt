package app.grapheneos.camera.capturer

/** Preview is mirrored for the front camera; processing reference pixels are sensor-oriented. */
internal object PhotoGeometry {
    fun savedSubjectX(x: Float, front: Boolean, saveAsPreviewed: Boolean): Float {
        require(x.isFinite() && x in 0f..1f)
        return if (front && !saveAsPreviewed) 1f - x else x
    }

    fun unmirrorReference(pixels: IntArray, width: Int, height: Int, front: Boolean) {
        require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong())
        if (!front) return
        for (y in 0 until height) for (x in 0 until width / 2) {
            val left = y * width + x; val right = y * width + width - 1 - x
            val value = pixels[left]; pixels[left] = pixels[right]; pixels[right] = value
        }
    }
}
