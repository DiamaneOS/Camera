package app.grapheneos.camera

import java.util.Locale
import kotlin.math.abs

/** EXIF OffsetTime for an offset from UTC, as "+HH:MM" or "-HH:MM". */
fun exifOffsetTime(offsetMillis: Int): String {
    val totalMins = offsetMillis / (1000 * 60)
    val sign = if (totalMins < 0) '-' else '+'
    val mins = abs(totalMins)
    return String.format(Locale.US, "%c%02d:%02d", sign, mins / 60, mins % 60)
}
