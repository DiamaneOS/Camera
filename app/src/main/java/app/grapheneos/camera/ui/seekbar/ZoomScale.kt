package app.grapheneos.camera.ui.seekbar

import kotlin.math.ln
import kotlin.math.pow

/**
 * Maps the zoom bar's position to a zoom ratio so that equal distances give equal zoom factors,
 * from the widest lens to the longest zoom, as pinch zoom does.
 */
object ZoomScale {
    fun ratioAt(fraction: Float, minRatio: Float, maxRatio: Float): Float {
        if (maxRatio <= minRatio) return minRatio
        return minRatio * (maxRatio / minRatio).pow(fraction.coerceIn(0f, 1f))
    }

    fun fractionOf(ratio: Float, minRatio: Float, maxRatio: Float): Float {
        if (maxRatio <= minRatio) return 0f
        return (ln(ratio / minRatio) / ln(maxRatio / minRatio)).coerceIn(0f, 1f)
    }
}
