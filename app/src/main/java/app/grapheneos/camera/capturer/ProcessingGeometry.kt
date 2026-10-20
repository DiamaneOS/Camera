package app.grapheneos.camera.capturer

/** The acquisition contract shared by the bounded native and JPEG workers. */
internal object ProcessingGeometry {
    const val MAX_PIXELS = 16_000_000L
    fun supported(width: Int, height: Int, raw: Boolean = false): Boolean =
        width in 8..8192 && height in 8..8192 && width.toLong() * height <= MAX_PIXELS &&
            (!raw || (width % 4 == 0 && height % 4 == 0))
}
