package app.grapheneos.camera.capturer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeRebindTest {
    @Test fun aResumeRebinds() {
        assertTrue(ResumeRebind().onResume(recording=false))
    }
    @Test fun aDialogOverARecordingNeitherRebindsNorWaits() {
        val r=ResumeRebind()
        assertFalse(r.onResume(recording=true))
        assertFalse(r.afterRecording(busy=false))
    }
    @Test fun aReturnWhileTheStoppedRecordingFinishesRebindsOnceAfterIt() {
        val r=ResumeRebind()
        r.onStop()
        assertFalse(r.onResume(recording=true))
        assertFalse(r.afterRecording(busy=true))
        assertTrue(r.afterRecording(busy=false))
        assertFalse(r.afterRecording(busy=false))
    }
    @Test fun theWaitOutlastsADialog() {
        val r=ResumeRebind()
        r.onStop()
        r.onResume(recording=true)
        r.onResume(recording=true)
        assertTrue(r.afterRecording(busy=false))
    }
    @Test fun aResumeRebindEndsTheWait() {
        val r=ResumeRebind()
        r.onStop()
        assertTrue(r.onResume(recording=false))
        assertFalse(r.afterRecording(busy=false))
    }
}
