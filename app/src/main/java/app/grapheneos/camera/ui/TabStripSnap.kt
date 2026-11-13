package app.grapheneos.camera.ui

import kotlin.math.roundToInt

object TabStripSnap {
    private const val GLIDE_SECONDS = 0.1f

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
}
