package app.grapheneos.camera.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPlacementTest {
    // Fairphone 6: 1116x2484, controls area ends at 1812, status bar and margin 134.
    private fun fills(ratio: String?, width: Int = 1116, height: Int = 2484) =
        PreviewPlacement.fillsWidth(width, height, 134, 1812, ratio)

    @Test
    fun sixteenByNineFillsTheWidth() = assertTrue(fills("9:16"))

    @Test
    fun fourByThreeStaysAboveTheControls() = assertFalse(fills("3:4"))

    @Test
    fun aShortWindowFitsAboveTheControls() = assertFalse(fills("9:16", height = 2000))

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
