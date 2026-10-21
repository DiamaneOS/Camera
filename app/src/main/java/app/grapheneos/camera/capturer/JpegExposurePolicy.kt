package app.grapheneos.camera.capturer

import kotlin.math.roundToInt

/** Single-frame compensation for preview digital gain beyond the manual sensor range. */
internal object JpegExposurePolicy {
    /** restoreGain: sensor gain withheld for highlight headroom, restored by the merge. */
    data class Setting(val exposureNs: Long, val iso: Int, val boost: Int, val restoreGain: Float = 1f)

    /** Share of viewfinder pixels whose brightest channel is at or above [level], where the
     *  lens's stills clip. Saturated lights such as sodium lamps clip in one channel. */
    fun nearWhiteFraction(maximumHistogram: LongArray, level: Int): Float {
        require(maximumHistogram.size == 256 && level in 200..255)
        val total = maximumHistogram.sum()
        return if (total > 0) (level..255).sumOf { maximumHistogram[it] }.toFloat() / total else 0f
    }

    /** Analog gain held back in Night when the viewfinder shows clipped lights, so they stay
     *  below the 8-bit JPEG's white. Less analog gain weakens the HAL's own noise reduction,
     *  so scenes without such lights keep the full gain. */
    fun nightHeadroom(previewClippedFraction: Float): Double =
        if (previewClippedFraction.isFinite() && previewClippedFraction >= 1e-4f) 2.0 else 1.0

    /** Private Night candidate: exchange gain for light within the handheld bound. Up to
     *  headroom of analog gain is held back at the same exposure time: the same light reaches
     *  the sensor and the merge restores it. Digital gain stays, the HAL renders it differently. */
    fun chooseNight(oldExposureNs: Long, reportedIso: Int, boost: Int, minExposureNs: Long,
                    maxExposureNs: Long, minIso: Int, maxIso: Int, minBoost: Int, maxBoost: Int,
                    handheldLimitNs: Long, headroom: Double = 1.0): Setting? {
        require(oldExposureNs > 0 && reportedIso > 0 && boost > 0 && minExposureNs > 0 &&
            maxExposureNs >= minExposureNs && minIso > 0 && maxIso >= minIso &&
            minBoost > 0 && maxBoost >= minBoost && handheldLimitNs > 0)
        if (minExposureNs > handheldLimitNs) return null
        val effective = NightExposurePolicy.effectiveIso(reportedIso, boost, maxIso)
        val budget = oldExposureNs.toDouble() * effective
        val exposure = minOf(handheldLimitNs, maxExposureNs,
            (budget * 100 / (minIso.toDouble() * minBoost)).toLong()).coerceAtLeast(minExposureNs)
        if (exposure.toDouble() < oldExposureNs * 1.25) return null
        val iso = (budget * 100 / (exposure.toDouble() * minBoost)).roundToInt().coerceIn(minIso, maxIso)
        val digital = (100 * budget / (exposure.toDouble() * iso)).roundToInt().coerceIn(minBoost, maxBoost)
        val achieved = exposure.toDouble() * iso * digital / 100
        if (achieved / budget !in .9..1.1) return null
        require(headroom in 1.0..4.0)
        val held = (iso / headroom).roundToInt().coerceIn(minIso, iso)
        return Setting(exposure, held, digital, iso.toFloat() / held)
    }
    fun choose(oldExposureNs: Long, reportedIso: Int, boost: Int, minExposureNs: Long,
               maxExposureNs: Long, minIso: Int, maxIso: Int, minBoost: Int, maxBoost: Int): Setting? {
        require(oldExposureNs > 0 && reportedIso > 0 && boost > 0 && minExposureNs > 0 &&
            maxExposureNs >= minExposureNs && minIso > 0 && maxIso >= minIso && minBoost > 0 && maxBoost >= minBoost)
        val effective = NightExposurePolicy.effectiveIso(reportedIso, boost, maxIso)
        if (effective <= maxIso || minExposureNs > 66_666_667L) return null
        val budget = oldExposureNs.toDouble() * effective
        val exposure = minOf(66_666_667L, maxExposureNs, (budget / maxIso).toLong()).coerceAtLeast(minExposureNs)
        // Small changes do not justify another sensor-control transition.
        if (exposure.toDouble() < oldExposureNs * 1.25) return null
        val iso = (budget / exposure).roundToInt().coerceIn(minIso, maxIso)
        val digital = (100 * budget / (exposure.toDouble() * iso)).roundToInt().coerceIn(minBoost, maxBoost)
        val achieved = exposure.toDouble() * iso * digital / 100
        if (achieved / budget !in .9..1.1) return null
        return Setting(exposure, iso, digital)
    }
}
