package app.grapheneos.camera.capturer

import kotlin.math.log2

/** Low-light suggestion policy. Capture support and scene motion are supplied by the caller. */
internal class NightSceneDetector(private val config: Config = Config()) {
    data class Config(
        val enterEv100: Double = 3.0,
        val exitEv100: Double = 4.0,
        val enterDelayMs: Long = 1200,
        val exitDelayMs: Long = 800,
        val staleAfterMs: Long = 1500,
    ) {
        init {
            require(enterEv100.isFinite() && exitEv100.isFinite() && exitEv100 > enterEv100)
            require(enterDelayMs >= 0 && exitDelayMs >= 0 && staleAfterMs > 0)
        }
    }

    private var suggested = false
    private var dismissed = false
    private var dismissalBrightSince: Long? = null
    private var pendingSince: Long? = null
    private var lastObservation: Long? = null

    @Synchronized fun dismiss() { dismissed = true; dismissalBrightSince = null }

    @Synchronized fun reset(clearDismissal: Boolean = true) {
        if (clearDismissal) dismissed = false
        suggested = false
        pendingSince = null
        lastObservation = null
        dismissalBrightSince = null
    }

    @Synchronized fun observe(
        nowMs: Long,
        exposureNs: Long,
        iso: Int,
        aperture: Double,
        eligible: Boolean,
        flashEnabled: Boolean,
        subjectMotionSuitable: Boolean,
    ): Boolean {
        val last = lastObservation
        if (last != null && (nowMs < last || nowMs - last > config.staleAfterMs)) reset(clearDismissal = false)
        lastObservation = nowMs
        if (!eligible || flashEnabled || !subjectMotionSuitable || exposureNs <= 0 || iso <= 0 ||
            !aperture.isFinite() || aperture <= 0) {
            reset(clearDismissal = false)
            return false
        }
        val ev = log2(aperture * aperture / (exposureNs / 1e9) * 100.0 / iso)
        if (!ev.isFinite()) {
            reset(clearDismissal = false)
            return false
        }
        if (dismissed) {
            if (ev >= config.exitEv100) {
                val start = dismissalBrightSince ?: nowMs.also { dismissalBrightSince = it }
                if (nowMs - start >= config.exitDelayMs) dismissed = false
            } else dismissalBrightSince = null
        }
        val shouldChange = if (suggested) ev >= config.exitEv100 else ev <= config.enterEv100
        if (!shouldChange) {
            pendingSince = null
            return suggested && !dismissed
        }
        val start = pendingSince ?: nowMs.also { pendingSince = it }
        val delay = if (suggested) config.exitDelayMs else config.enterDelayMs
        if (nowMs - start >= delay) {
            suggested = !suggested
            if (!suggested) dismissed = false
            pendingSince = null
        }
        return suggested && !dismissed
    }
}
