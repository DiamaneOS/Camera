package app.grapheneos.camera.capturer

import kotlin.math.sqrt
import kotlin.math.roundToInt

/** A short, conservative angular-motion check. Missing or irregular samples never enable long frames. */
internal class NightMotionWindow {
    private var first = -1L
    private var last = -1L
    private var count = 0
    private var energy = 0.0
    private var peak = 0.0
    private var invalid = false

    @Synchronized fun record(timestampNs: Long, squaredSpeed: Double) {
        if (timestampNs < 0 || timestampNs <= last || !squaredSpeed.isFinite() || squaredSpeed < 0) {
            invalid = true
            return
        }
        if (first < 0) first = timestampNs
        last = timestampNs
        count++
        energy += squaredSpeed
        peak = maxOf(peak, squaredSpeed)
    }

    @Synchronized fun isSteady(): Boolean = !invalid && count >= 10 && last - first >= 250_000_000L &&
        sqrt(energy / count) < .008 && sqrt(peak) < .02

    /** SensorEvent timestamps use elapsed realtime; missing recent gyro events fail closed. */
    @Synchronized fun isSteadyAt(nowNs: Long): Boolean = nowNs >= last && last >= 0 &&
        nowNs - last <= 100_000_000L && isSteady()
}

internal object NightExposurePolicy {
    data class Setting(val exposureNs: Long, val iso: Int)

    fun effectiveIso(reportedIso: Int, postRawBoost: Int, sensorMaxIso: Int): Int {
        require(reportedIso > 0 && postRawBoost > 0 && sensorMaxIso > 0)
        // RAW sensitivity cannot exceed the physical sensor's advertised range. Some
        // preview paths instead report gain-inclusive ISO alongside the same digital boost.
        // Bound the physical component before combining it with the separate boost.
        return (minOf(reportedIso, sensorMaxIso).toDouble() * postRawBoost / 100).roundToInt().coerceAtLeast(1)
    }

    fun exposureCeiling(meteredNs:Long, sensorMinNs:Long, sensorMaxNs:Long,
                        night:Boolean, steady:Boolean, meteredIso:Int=1, sensorMaxIso:Int=Int.MAX_VALUE):Long {
        require(meteredNs>0 && sensorMinNs>0 && sensorMaxNs>=sensorMinNs && meteredIso>0 && sensorMaxIso>0)
        if(night)return sensorMaxNs
        // A smooth preview's short frame period must not also become the still
        // photo's limit when steadiness permits a longer, less noisy exposure.
        // Preview digital gain may exceed the manual sensor ISO range. Keeping
        // its short exposure while clipping ISO loses signal before processing.
        // Compensate within the existing short-photo ceiling; ordinary metering
        // below that ISO limit keeps its previous motion-conscious exposure.
        val compensated=meteredNs.toDouble()*maxOf(1.0,meteredIso.toDouble()/sensorMaxIso)
        // Enhanced opts in separately and still requires both fresh scene and
        // gyro admission after reopening and highlight probing. Cap that static
        // case at 1/8 s; ordinary and uncertain Photo frames retain 1/15 s.
        val limit=if(steady)125_000_000L else minOf(compensated,66_666_667.0).toLong()
        return limit.coerceIn(sensorMinNs,sensorMaxNs)
    }

    fun shortPhotoFallback(protected: Setting, fallback: Setting, minExposure: Long, minIso: Int): Setting =
        // Preserve the highlight probe's signal budget while redistributing it
        // over the short exposure. Clamping both ISO and time independently
        // would needlessly darken a rejected long-exposure candidate.
        choose(protected.exposureNs, protected.iso, minExposure,
            minOf(protected.exposureNs, fallback.exposureNs), minIso, fallback.iso, false,
            fallback.exposureNs, fallback.exposureNs)

    /** The 240 MiB input bound across all frames, with RAW16 rows padded to 64 bytes. */
    const val MAX_INPUT_BYTES = 240L * 1024 * 1024
    fun framesThatFit(width: Int, height: Int): Int {
        require(width > 0 && height > 0)
        val rowStride = (width * 2L + 63) / 64 * 64
        return (MAX_INPUT_BYTES / (rowStride * height)).toInt()
    }

    fun frameCount(oldExposure: Long, effectiveIso: Int, minimumIso: Int, maximumFrames: Int): Int {
        require(oldExposure>0 && effectiveIso>0 && minimumIso>0 && maximumFrames in 2..8)
        val budget=oldExposure.toDouble()*effectiveIso/minimumIso
        return minOf(maximumFrames,when {budget<24_000_000 -> 2;budget<160_000_000 -> 4;else -> 8})
    }

    fun choose(oldExposure: Long, oldIso: Int, minExposure: Long, maxExposure: Long,
               minIso: Int, maxIso: Int, steady: Boolean,
               handheldLimitNs: Long = 66_666_667L, steadyLimitNs: Long = 250_000_000L): Setting {
        require(oldExposure > 0 && oldIso > 0 && minExposure > 0 && maxExposure >= minExposure && minIso > 0 && maxIso >= minIso)
        val budget = oldExposure.toDouble() * oldIso
        val limit = if (steady) steadyLimitNs else handheldLimitNs
        require(handheldLimitNs>0 && steadyLimitNs>=handheldLimitNs)
        // Avoid overexposing a bright scene once the sensor has reached its minimum ISO.
        val exposure = minOf(limit, maxExposure, (budget / minIso).toLong()).coerceIn(minExposure, maxExposure)
        return Setting(exposure, (budget / exposure).roundToInt().coerceIn(minIso, maxIso))
    }
}
