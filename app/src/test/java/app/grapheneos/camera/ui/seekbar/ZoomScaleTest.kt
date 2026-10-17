package app.grapheneos.camera.ui.seekbar

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomScaleTest {
    @Test
    fun endsAreTheWidestAndLongestZoom() {
        assertEquals(0.6f, ZoomScale.ratioAt(0f, 0.6f, 10f), 1e-4f)
        assertEquals(10f, ZoomScale.ratioAt(1f, 0.6f, 10f), 1e-4f)
    }

    @Test
    fun equalDistancesGiveEqualFactors() {
        val a = ZoomScale.ratioAt(0.1f, 0.6f, 10f) / ZoomScale.ratioAt(0f, 0.6f, 10f)
        val b = ZoomScale.ratioAt(0.6f, 0.6f, 10f) / ZoomScale.ratioAt(0.5f, 0.6f, 10f)
        assertEquals(a, b, 1e-4f)
    }

    @Test
    fun theBottomTenthLeavesTheWidestLens() {
        // The old linear zoom stayed below 0.65x for the bottom eighth of the bar.
        assert(ZoomScale.ratioAt(0.1f, 0.6f, 10f) > 0.7f)
    }

    @Test
    fun positionRoundTrips() {
        for (f in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val r = ZoomScale.ratioAt(f, 0.6f, 10f)
            assertEquals(f, ZoomScale.fractionOf(r, 0.6f, 10f), 1e-4f)
        }
    }

    @Test
    fun aFixedRangeStaysAtItsRatio() {
        assertEquals(1f, ZoomScale.ratioAt(0.5f, 1f, 1f), 0f)
        assertEquals(0f, ZoomScale.fractionOf(1f, 1f, 1f), 0f)
    }
}
