package app.grapheneos.camera.capturer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicOffNoteTest {
    @Test fun aBlockDuringARecordingWithSoundShowsTheNote() {
        assertTrue(MicOffNote().onAccess(recordingWithSound=true, microphoneAllowed=false, cameraAllowed=true))
    }
    @Test fun theNoteShowsOncePerBlock() {
        val n=MicOffNote()
        assertTrue(n.onAccess(true, false, true))
        assertFalse(n.onAccess(true, false, true))
    }
    @Test fun aSecondBlockShowsItAgain() {
        val n=MicOffNote()
        assertTrue(n.onAccess(true, false, true))
        assertFalse(n.onAccess(true, true, true))
        assertTrue(n.onAccess(true, false, true))
    }
    @Test fun noNoteWithoutARecordingWithSound() {
        assertFalse(MicOffNote().onAccess(recordingWithSound=false, microphoneAllowed=false, cameraAllowed=true))
    }
    @Test fun noNoteWhileTheMicrophoneIsAllowed() {
        assertFalse(MicOffNote().onAccess(true, microphoneAllowed=true, cameraAllowed=true))
    }
    @Test fun aCameraBlockOwnsTheMessage() {
        assertFalse(MicOffNote().onAccess(true, microphoneAllowed=false, cameraAllowed=false))
    }
    @Test fun unmutingUnderABlockShowsTheNote() {
        val n=MicOffNote()
        assertFalse(n.onAccess(recordingWithSound=false, microphoneAllowed=false, cameraAllowed=true))
        assertTrue(n.onAccess(recordingWithSound=true, microphoneAllowed=false, cameraAllowed=true))
    }
    @Test fun theNextRecordingUnderTheSameBlockShowsItAgain() {
        val n=MicOffNote()
        assertTrue(n.onAccess(true, false, true))
        assertFalse(n.onAccess(recordingWithSound=false, microphoneAllowed=false, cameraAllowed=true))
        assertTrue(n.onAccess(true, false, true))
    }
    @Test fun aLiftedCameraBlockShowsTheNoteIfTheMicrophoneStaysBlocked() {
        val n=MicOffNote()
        assertFalse(n.onAccess(true, false, false))
        assertTrue(n.onAccess(true, false, true))
    }
    @Test fun resetForgetsTheNote() {
        val n=MicOffNote()
        assertTrue(n.onAccess(true, false, true))
        n.reset()
        assertTrue(n.onAccess(true, false, true))
    }
}
