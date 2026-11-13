package app.grapheneos.camera.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TabStripSnapTest {
    // The Fairphone 6 strip, 1116 px wide: PORTRAIT, NIGHT, CAMERA, VIDEO and SLO-MO with their
    // widths. A tab's start is in the middle at its left edge minus half the strip.
    private val widths = listOf(205, 222, 263, 216, 258)
    private val points = buildList {
        var left = 558 - widths[0] / 2
        for (width in widths) {
            add(left - 558)
            add(left + width - 558)
            left += width
        }
    }
    private fun centre(tab: Int) = (points[2 * tab] + points[2 * tab + 1]) / 2

    @Test
    fun aCentredTabIsTheTabInTheMiddle() {
        for (tab in widths.indices) assertEquals(tab, TabStripSnap.index(points, centre(tab)))
    }

    @Test
    fun everyPositionOfATabBelongsToIt() {
        // The left half used to belong to no tab, so a drag that ended there fell back.
        assertEquals(3, TabStripSnap.index(points, points[6] + 1))
        assertEquals(3, TabStripSnap.index(points, points[7] - 1))
        assertEquals(2, TabStripSnap.index(points, points[4] + 1))
    }

    @Test
    fun pastTheOuterTabsItIsTheNearestOne() {
        assertEquals(0, TabStripSnap.index(points, points.first() - 500))
        assertEquals(widths.lastIndex, TabStripSnap.index(points, points.last() + 500))
    }

    @Test
    fun aStripLaidOutRightToLeftWorksTheSame() {
        val mirrored = points.chunked(2).reversed().flatten()
        assertEquals(widths.lastIndex - 3, TabStripSnap.index(mirrored, centre(3)))
    }

    @Test
    fun noTabsGiveNoIndex() {
        assertEquals(-1, TabStripSnap.index(emptyList(), 0))
    }

    @Test
    fun aDragLetGoAtRestStaysWhereItIs() {
        assertEquals(centre(2) + 40, TabStripSnap.landing(centre(2) + 40, 0f))
        assertEquals(2, TabStripSnap.index(points, TabStripSnap.landing(centre(2) + 40, 0f)))
    }

    @Test
    fun aFlickCarriesOnInItsDirection() {
        // 60 px from CAMERA's centre is still CAMERA. Let go at 2000 px a second it reaches VIDEO.
        assertEquals(2, TabStripSnap.index(points, centre(2) + 60))
        assertEquals(3, TabStripSnap.index(points, TabStripSnap.landing(centre(2) + 60, 2000f)))
        assertEquals(1, TabStripSnap.index(points, TabStripSnap.landing(centre(2) - 60, -2000f)))
    }

    @Test
    fun theGlideStartsAtTheSpeedTheFingerLeftWith() {
        // Slowing down evenly over 180 px from 2000 px a second takes 180 ms, in either direction.
        assertEquals(180L, TabStripSnap.settleMillis(180, 2000f))
        assertEquals(180L, TabStripSnap.settleMillis(-180, -2000f))
    }

    @Test
    fun theGlideIsNeitherAbruptNorDrawnOut() {
        assertEquals(120L, TabStripSnap.settleMillis(40, 5000f))
        assertEquals(400L, TabStripSnap.settleMillis(120, 100f))
    }

    @Test
    fun aStripAtRestOrMovingAwayGlidesBackPlainly() {
        assertEquals(250L, TabStripSnap.settleMillis(60, 0f))
        assertEquals(250L, TabStripSnap.settleMillis(-60, 300f))
        assertEquals(0L, TabStripSnap.settleMillis(0, 2000f))
    }
}
