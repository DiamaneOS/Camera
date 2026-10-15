package app.grapheneos.camera.capturer

/** Capture processing is independent of JPEG compression and sensor resolution. */
internal object PhotoCapturePolicy {
    enum class Mode { QUALITY, ZERO_SHUTTER_LAG, LATENCY }

    fun captureMode(
        preferQuality: Boolean,
        requestZsl: Boolean,
        extensionEnabled: Boolean,
        videoSnapshot: Boolean,
        flashEnabled: Boolean,
    ): Mode = when {
        // A video snapshot must not stall the recording for a still-photo convergence sequence.
        videoSnapshot -> Mode.LATENCY
        // Vendor extensions own their multi-frame capture sequence. ZSL cannot run with them.
        extensionEnabled -> Mode.QUALITY
        requestZsl && !flashEnabled -> Mode.ZERO_SHUTTER_LAG
        preferQuality -> Mode.QUALITY
        else -> Mode.LATENCY
    }

    fun preferQuality(current: Boolean?, legacy: Boolean?, default: Boolean): Boolean =
        current ?: legacy ?: default
}
