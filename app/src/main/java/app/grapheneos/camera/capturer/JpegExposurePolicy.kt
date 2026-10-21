package app.grapheneos.camera.capturer

import kotlin.math.roundToInt

/** Single-frame compensation for preview digital gain beyond the manual sensor range. */
internal object JpegExposurePolicy {
    data class Setting(val exposureNs: Long, val iso: Int, val boost: Int)

    /** Private Night candidate: exchange gain for light within the handheld bound. */
    fun chooseNight(oldExposureNs: Long, reportedIso: Int, boost: Int, minExposureNs: Long,
                    maxExposureNs: Long, minIso: Int, maxIso: Int, minBoost: Int, maxBoost: Int,
                    handheldLimitNs: Long): Setting? {
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
        return Setting(exposure, iso, digital)
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
