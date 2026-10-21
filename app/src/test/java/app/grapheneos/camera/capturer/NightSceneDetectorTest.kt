package app.grapheneos.camera.capturer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightSceneDetectorTest {
    private fun NightSceneDetector.dark(t: Long, motion: Boolean = true, eligible: Boolean = true) =
        observe(t, 70_000_000, 4800, 1.88, eligible, false, motion)

    private fun NightSceneDetector.bright(t: Long) =
        observe(t, 20_000_000, 560, 1.88, true, false, true)

    @Test fun transientDarknessDoesNotSuggestNight() {
        val detector = NightSceneDetector()
        assertFalse(detector.dark(0))
        assertFalse(detector.dark(1000))
        assertFalse(detector.bright(1100))
        assertFalse(detector.dark(1200))
    }

    @Test fun sustainedDarknessAndBrightnessHaveSeparateDelays() {
        val detector = NightSceneDetector()
        assertFalse(detector.dark(0))
        assertTrue(detector.dark(1200))
        assertTrue(detector.bright(1300))
        assertTrue(detector.bright(2000))
        assertFalse(detector.bright(2100))
    }

    @Test fun unsuitableMotionOrUnavailableCaptureClearsSuggestion() {
        val detector = NightSceneDetector()
        detector.dark(0)
        assertTrue(detector.dark(1200))
        assertFalse(detector.dark(1300, motion = false))
        detector.dark(1400)
        assertTrue(detector.dark(2600))
        assertFalse(detector.dark(2700, eligible = false))
    }

    @Test fun resumeCannotReuseAnOldDarkReading() {
        val detector = NightSceneDetector()
        detector.dark(0)
        assertFalse(detector.dark(10_000))
        assertFalse(detector.dark(10_100))
        assertTrue(detector.dark(11_200))
    }

    @Test fun cameraChangeAndInvalidMetadataClearState() {
        val detector = NightSceneDetector()
        detector.dark(0)
        detector.dark(1200)
        detector.reset()
        assertFalse(detector.dark(1300))
        assertFalse(detector.observe(1400, 0, 100, 1.88, true, false, true))
        assertFalse(detector.observe(1500, 70_000_000, 4800, Double.NaN, true, false, true))
    }

    @Test fun flashDisablesTheSuggestion() {
        val detector = NightSceneDetector()
        detector.dark(0)
        detector.dark(1200)
        assertFalse(detector.observe(1300, 70_000_000, 4800, 1.88, true, true, true))
    }
    @Test fun dismissedSuggestionStaysHiddenAcrossRebindUntilTheSceneBrightens() {
        val detector = NightSceneDetector()
        detector.dark(0)
        assertTrue(detector.dark(1200))
        detector.dismiss()
        assertFalse(detector.dark(1300))
        detector.reset(clearDismissal = false)
        assertFalse(detector.dark(1400))
        assertFalse(detector.dark(2600))
        assertFalse(detector.bright(2700))
        assertFalse(detector.bright(3500))
        assertFalse(detector.dark(3600))
        assertTrue(detector.dark(4800))
    }
    @Test fun dismissalClearsWhenReturningToABrighterSceneAfterRebind() {
        val detector = NightSceneDetector()
        detector.dark(0)
        detector.dark(1200)
        detector.dismiss()
        detector.reset(clearDismissal = false)
        assertFalse(detector.bright(1300))
        assertFalse(detector.bright(2100))
        assertFalse(detector.dark(2200))
        assertTrue(detector.dark(3400))
    }
}
