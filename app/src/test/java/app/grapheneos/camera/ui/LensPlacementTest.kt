package app.grapheneos.camera.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensPlacementTest {
    // Fairphone 6 at density 2.75: capture row from 2010, the arrow's 32 + 48 dp is 220 px, gap 22 px.
    private val controlsTop = 2010
    private val arrowSpace = 220
    private val gap = 22
    private fun margin(previewBottom: Int, arrowTop: Int?) =
        LensPlacement.bottomMargin(previewBottom, arrowTop, controlsTop, arrowSpace, gap)

    @Test
    fun aFilledPreviewWithoutTheArrowKeepsTheRowAboveTheCaptureRow() {
        // 16:9 filling the width ends at the mode tabs, behind the capture row.
        val previewBottom = 2312
        val rowBottom = previewBottom - margin(previewBottom, null)
        assertTrue(rowBottom <= controlsTop - arrowSpace - gap)
    }

    @Test
    fun theRowStaysPutWhenTheArrowAppears() {
        for (previewBottom in listOf(2312, 1980)) {
            assertEquals(margin(previewBottom, null), margin(previewBottom, controlsTop - arrowSpace))
        }
    }

    @Test
    fun aShownArrowCountsWhereItIs() = assertEquals(2312 - 1700 + gap, margin(2312, 1700))

    @Test
    fun aPreviewEndingAboveTheArrowNeedsOnlyTheGap() {
        assertEquals(gap, margin(1700, null))
        assertEquals(gap, margin(1700, 1790))
    }
}
