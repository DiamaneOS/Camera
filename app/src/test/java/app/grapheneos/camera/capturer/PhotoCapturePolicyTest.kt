package app.grapheneos.camera.capturer

import app.grapheneos.camera.capturer.PhotoCapturePolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoCapturePolicyTest {
    private fun mode(
        quality: Boolean = true, zsl: Boolean = false, extension: Boolean = false,
        video: Boolean = false, flash: Boolean = false,
    ) = PhotoCapturePolicy.captureMode(quality, zsl, extension, video, flash)

    @Test fun ordinaryPhotoCanChooseQualityOrLatencyProcessing() {
        assertEquals(Mode.QUALITY, mode())
        assertEquals(Mode.LATENCY, mode(quality = false))
    }

    @Test fun explicitZslHasPriorityOnlyForCompatibleCaptureWorkflows() {
        assertEquals(Mode.ZERO_SHUTTER_LAG, mode(zsl = true))
        assertEquals(Mode.QUALITY, mode(zsl = true, flash = true))
        assertEquals(Mode.QUALITY, mode(zsl = true, extension = true))
        assertEquals(Mode.LATENCY, mode(zsl = true, video = true))
    }

    @Test fun extensionSequenceDoesNotInheritLatencyOrZslPreferences() {
        assertEquals(Mode.QUALITY,
            mode(quality = false, zsl = true, extension = true))
    }

    @Test fun videoSnapshotsDoNotWaitForStillPhotoConvergence() {
        assertEquals(Mode.LATENCY, mode(video = true))
    }

    @Test fun explicitPreferencesSurviveTheOldMisleadingSettingName() {
        assertFalse(PhotoCapturePolicy.preferQuality(null, false, true))
        assertTrue(PhotoCapturePolicy.preferQuality(null, true, false))
        assertFalse(PhotoCapturePolicy.preferQuality(false, true, true))
        assertTrue(PhotoCapturePolicy.preferQuality(true, false, false))
    }

    @Test fun deviceDefaultAppliesOnlyWhenTheUserHasNotChosen() {
        assertTrue(PhotoCapturePolicy.preferQuality(null, null, true))
        assertFalse(PhotoCapturePolicy.preferQuality(null, null, false))
    }
}
