package app.grapheneos.camera.capturer

import org.junit.Assert.*
import org.junit.Test

class PhotoZoomMappingTest {
    @Test fun wideZoomPreservesTheDisplayedFraming() {
        for (zoom in listOf(.56f, .7f, .84f, .99f)) {
            val local = PhotoZoomMapping.toLens(zoom, .56f)
            assertTrue(local >= 1f)
            assertEquals(zoom, PhotoZoomMapping.toDisplay(local, .56f), .00001f)
        }
        assertEquals(1f, PhotoZoomMapping.toLens(.56f, .56f), .00001f)
    }
    @Test fun mainZoomIsUnchanged() {
        assertEquals(2f, PhotoZoomMapping.toLens(2f, 1f), .00001f)
        assertEquals(2f, PhotoZoomMapping.toDisplay(2f, 1f), .00001f)
    }
    @Test fun invalidCalibrationIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { PhotoZoomMapping.toLens(1f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { PhotoZoomMapping.toDisplay(1f, Float.NaN) }
    }

    @Test fun selectingMainDoesNotRemoveTheSupportedWideRange() {
        val result = PhotoZoomMapping.combinedRange(PhotoZoomMapping.Range(1f, 8f), 1f,
            listOf(PhotoZoomMapping.Range(.56f, 4.48f)))
        assertEquals(.56f, result.minimum, .00001f)
        assertEquals(8f, result.maximum, .00001f)
    }

    @Test fun wideAndMainBindingsExposeTheSameCombinedRange() {
        val result = PhotoZoomMapping.combinedRange(PhotoZoomMapping.Range(1f, 8f), .56f,
            listOf(PhotoZoomMapping.Range(1f, 8f)))
        assertEquals(.56f, result.minimum, .00001f)
        assertEquals(8f, result.maximum, .00001f)
    }

    @Test fun singleLensDoesNotInventAWiderCamera() {
        val result = PhotoZoomMapping.combinedRange(PhotoZoomMapping.Range(1f, 4f), 1f, emptyList())
        assertEquals(1f, result.minimum, 0f)
        assertEquals(4f, result.maximum, 0f)
    }

    @Test fun highSpeedDoesNotAdvertiseUnverifiedPhysicalLensSwitching() {
        val range = PhotoZoomMapping.highSpeedRange(PhotoZoomMapping.Range(.56f, 8f))
        assertEquals(1f, range.minimum, 0f)
        assertEquals(8f, range.maximum, 0f)
        assertEquals(2f, PhotoZoomMapping.highSpeedRange(PhotoZoomMapping.Range(2f, 4f)).minimum, 0f)
    }

    @Test fun explicitLensChoicesAreImmediateAndDraggingHasHysteresis() {
        assertTrue(PhotoZoomMapping.useMainLens(1f, false, false))
        assertFalse(PhotoZoomMapping.useMainLens(.9f, true, false))
        assertTrue(PhotoZoomMapping.useMainLens(.96f, true, true))
        assertFalse(PhotoZoomMapping.useMainLens(.9f, true, true))
        assertFalse(PhotoZoomMapping.useMainLens(1.02f, false, true))
        assertTrue(PhotoZoomMapping.useMainLens(1.05f, false, true))
    }
}
