package app.grapheneos.camera.capturer

/** A dedicated slow-motion mode may resolve only to an encoder-eligible profile. */
internal object SlowMotionQualityPolicy {
    fun <T> eligible(advertised: List<T>, hasRates: (T) -> Boolean): List<T> =
        advertised.distinct().filter(hasRates)

    fun <T> resolve(requested: T?, eligible: List<T>): T? =
        requested?.takeIf { it in eligible } ?: eligible.firstOrNull()
}
