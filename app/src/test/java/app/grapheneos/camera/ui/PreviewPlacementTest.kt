package app.grapheneos.camera.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPlacementTest {
    // Fairphone 6: 1116 px wide, status bar and margin 134, controls area ends at 1812, room ends at 2178.
    private fun fills(ratio: String?, width: Int = 1116, tabsTop: Int = 2178) =
        PreviewPlacement.fillsWidth(width, 134, 1812, tabsTop, ratio)

    @Test
    fun sixteenByNineFillsTheWidth() = assertTrue(fills("9:16"))

    @Test
    fun fourByThreeStaysAboveTheControls() = assertFalse(fills("3:4"))

    @Test
    fun aShortWindowFitsAboveTheControls() = assertFalse(fills("9:16", tabsTop = 2100))

    @Test
    fun theFullWidthHeightJustFits() {
        assertTrue(fills("9:16", tabsTop = 134 + 1984))
        assertFalse(fills("9:16", tabsTop = 134 + 1983))
    }

    @Test
    fun aWideRatioStaysAboveTheControls() = assertFalse(fills("16:9"))

    @Test
    fun anUnreadableRatioKeepsTheFit() {
        assertFalse(fills(null))
        assertFalse(fills("H,9:16"))
        assertFalse(fills("0:16"))
        assertFalse(fills("9:16", width = 0))
    }
}
