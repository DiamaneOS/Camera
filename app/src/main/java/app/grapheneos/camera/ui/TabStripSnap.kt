package app.grapheneos.camera.ui

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object TabStripSnap {
    private const val GLIDE_SECONDS = 0.1f
    private const val SETTLE_MS = 250L
    private const val SHORTEST_SETTLE_MS = 120L
    private const val LONGEST_SETTLE_MS = 400L

    /**
     * The index of the tab under the middle of the strip at scroll position [x], or -1 without
     * tabs. [points] holds two scroll positions per tab: the one that puts its start in the
     * middle and the one that puts its end there. Past the outer tabs it is the nearest one.
     */
    fun index(points: List<Int>, x: Int): Int {
        var nearest = -1
        var distance = Int.MAX_VALUE
        for (i in 0 until points.size / 2) {
            val start = points[2 * i]
            val end = points[2 * i + 1]
            if (x in start..end) return i
            val away = if (x < start) start - x else x - end
            if (away < distance) {
                distance = away
                nearest = i
            }
        }
        return nearest
    }

    /**
     * Where a strip let go at scroll position [x] comes to rest: a short glide on at [velocity],
     * the px per second it was scrolling at, so a flick carries past the tab it was lifted on.
     */
    fun landing(x: Int, velocity: Float): Int = x + (velocity * GLIDE_SECONDS).roundToInt()

    /**
     * How long the strip takes to come to rest [distance] px on, in ms. A glide that slows down
     * evenly starts at twice its average speed, so one that takes twice the time [distance]
     * needs at [velocity] carries on at the speed the finger left with. A finger at rest or one
     * that moved away from the tab gets a plain short glide.
     */
    fun settleMillis(distance: Int, velocity: Float): Long {
        if (distance == 0) return 0
        if (velocity == 0f || (velocity > 0) != (distance > 0)) return SETTLE_MS
        return (2000f * abs(distance) / abs(velocity)).roundToLong()
            .coerceIn(SHORTEST_SETTLE_MS, LONGEST_SETTLE_MS)
    }
}
