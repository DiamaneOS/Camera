package app.grapheneos.camera.capturer
import app.grapheneos.camera.capturer.CameraBlockRecovery.State
import org.junit.Assert.*
import org.junit.Test

class CameraBlockRecoveryTest {
    private fun blocked() = CameraBlockRecovery().apply {
        onAccess(false)
        onCameraState(State.CLOSED, disabled=true, accessAllowed=false)
    }

    @Test fun rebindsOnceWhenAccessReturns() {
        val r=blocked()
        assertFalse(r.rebindNow(false))
        r.onAccess(true)
        assertTrue(r.rebindNow(false))
        r.onRebind()
        r.onAccess(true)
        assertFalse(r.rebindNow(false))
    }
    @Test fun closeReportedAfterAccessReturned() {
        val r=CameraBlockRecovery()
        r.onAccess(false)
        r.onAccess(true)
        r.onCameraState(State.CLOSED, disabled=true, accessAllowed=true)
        assertTrue(r.rebindNow(false))
    }
    @Test fun noRebindWhileBlocked() {
        val r=blocked()
        repeat(5) {
            r.onAccess(false)
            assertFalse(r.rebindNow(false))
        }
        r.onCameraState(State.CLOSED, disabled=false, accessAllowed=false)
        assertFalse(r.rebindNow(false))
    }
    @Test fun disabledWithoutABlockIsLeftAlone() {
        val r=CameraBlockRecovery()
        r.onCameraState(State.CLOSED, disabled=true, accessAllowed=true)
        r.onAccess(true)
        assertFalse(r.rebindNow(false))
    }
    @Test fun failedRebindWaitsForTheNextReturn() {
        val r=blocked()
        r.onAccess(true)
        r.onRebind()
        r.onCameraState(State.CLOSED, disabled=true, accessAllowed=true)
        r.onAccess(true)
        assertFalse(r.rebindNow(false))
        r.onAccess(false)
        r.onAccess(true)
        assertTrue(r.rebindNow(false))
    }
    @Test fun openCameraClearsTheBlock() {
        val r=blocked()
        r.onCameraState(State.OPEN, disabled=false, accessAllowed=true)
        r.onAccess(true)
        assertFalse(r.rebindNow(false))
    }
    @Test fun ownRebindDropsAnOldClose() {
        val r=blocked()
        r.onRebind()
        r.onAccess(true)
        assertFalse(r.rebindNow(false))
    }
    @Test fun mutedOpenWhileBlockedStillRecovers() {
        val r=CameraBlockRecovery()
        r.onAccess(false)
        r.onCameraState(State.OPEN, disabled=false, accessAllowed=false)
        r.onCameraState(State.CLOSED, disabled=true, accessAllowed=false)
        r.onAccess(true)
        assertTrue(r.rebindNow(false))
    }
    @Test fun openWhileBlockedRecoversAfterCameraXGivesUp() {
        val r=CameraBlockRecovery()
        r.onAccess(false)
        r.onCameraState(State.OPENING, disabled=true, accessAllowed=false)
        r.onCameraState(State.CLOSED, disabled=true, accessAllowed=false)
        r.onCameraState(State.CLOSED, disabled=false, accessAllowed=false)
        r.onAccess(true)
        assertTrue(r.rebindNow(false))
    }
    @Test fun mergedCloseWithoutTheErrorStillCounts() {
        val r=CameraBlockRecovery()
        r.onAccess(false)
        r.onCameraState(State.OPENING, disabled=true, accessAllowed=false)
        r.onCameraState(State.CLOSED, disabled=false, accessAllowed=true)
        r.onAccess(true)
        assertTrue(r.rebindNow(false))
    }
    @Test fun closeWithoutTheErrorWhileBlockedCounts() {
        val r=CameraBlockRecovery()
        r.onAccess(false)
        r.onCameraState(State.CLOSED, disabled=false, accessAllowed=false)
        r.onAccess(true)
        assertTrue(r.rebindNow(false))
    }
    @Test fun noRebindWhileCameraXStillOpens() {
        val r=blocked()
        r.onCameraState(State.OPENING, disabled=true, accessAllowed=false)
        r.onAccess(true)
        assertFalse(r.rebindNow(false))
        r.onCameraState(State.OPEN, disabled=false, accessAllowed=true)
        assertFalse(r.rebindNow(false))
    }
    @Test fun aCaptureOrRecordingDefersTheRebind() {
        val r=blocked()
        r.onAccess(true)
        assertFalse(r.rebindNow(true))
        assertTrue(r.rebindNow(false))
    }
    @Test fun aNewBlockCancelsADeferredRebind() {
        val r=blocked()
        r.onAccess(true)
        assertFalse(r.rebindNow(true))
        r.onAccess(false)
        assertFalse(r.rebindNow(false))
        r.onAccess(true)
        assertTrue(r.rebindNow(false))
    }
    @Test fun resetForgetsEverything() {
        val r=blocked()
        r.reset()
        r.onAccess(true)
        assertFalse(r.rebindNow(false))
    }
}
