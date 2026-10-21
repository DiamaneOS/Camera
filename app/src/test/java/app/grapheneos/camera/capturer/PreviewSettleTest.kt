package app.grapheneos.camera.capturer

import android.hardware.camera2.CaptureResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSettleTest {
    private val auto = CaptureResult.CONTROL_AE_MODE_ON
    private val awbAuto = CaptureResult.CONTROL_AWB_MODE_AUTO

    @Test fun aFreshCameraIsUnsettledUntilExposureAndWhiteBalanceConverge() {
        assertFalse(PreviewSettle.settled(auto, CaptureResult.CONTROL_AE_STATE_SEARCHING, awbAuto, CaptureResult.CONTROL_AWB_STATE_INACTIVE))
        assertFalse(PreviewSettle.settled(auto, CaptureResult.CONTROL_AE_STATE_CONVERGED, awbAuto, CaptureResult.CONTROL_AWB_STATE_SEARCHING))
        assertFalse(PreviewSettle.settled(auto, CaptureResult.CONTROL_AE_STATE_PRECAPTURE, awbAuto, CaptureResult.CONTROL_AWB_STATE_CONVERGED))
        assertTrue(PreviewSettle.settled(auto, CaptureResult.CONTROL_AE_STATE_CONVERGED, awbAuto, CaptureResult.CONTROL_AWB_STATE_CONVERGED))
        assertTrue(PreviewSettle.settled(auto, CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED, awbAuto, CaptureResult.CONTROL_AWB_STATE_LOCKED))
    }

    @Test fun manualControlsAndUnreportedStatesNeverHold() {
        assertTrue(PreviewSettle.settled(CaptureResult.CONTROL_AE_MODE_OFF, CaptureResult.CONTROL_AE_STATE_INACTIVE,
            CaptureResult.CONTROL_AWB_MODE_OFF, CaptureResult.CONTROL_AWB_STATE_INACTIVE))
        assertTrue(PreviewSettle.settled(null, null, null, null))
    }

    @Test fun theOldFrameStaysUntilTheNewCameraSettles() {
        val hold = PreviewSettle.Hold()
        val generation = hold.arm()
        assertTrue(hold.streaming(100))
        assertTrue(hold.settle(generation))
        assertFalse(hold.streaming(200))
    }

    @Test fun aCameraThatSettledBeforeItsFirstShownFrameIsShownAtOnce() {
        val hold = PreviewSettle.Hold()
        val generation = hold.arm()
        assertFalse(hold.settle(generation))
        assertFalse(hold.streaming(100))
    }

    @Test fun theHoldEndsAfterItsLimit() {
        val hold = PreviewSettle.Hold()
        val generation = hold.arm()
        assertTrue(hold.streaming(1_000))
        assertFalse(hold.expired(generation, 1_000 + PreviewSettle.MAX_HOLD_MS - 1))
        assertTrue(hold.expired(generation, 1_000 + PreviewSettle.MAX_HOLD_MS))
        assertFalse(hold.streaming(1_000 + PreviewSettle.MAX_HOLD_MS + 5))
    }

    @Test fun anOlderRebindCannotEndTheCurrentHold() {
        val hold = PreviewSettle.Hold()
        val old = hold.arm()
        hold.streaming(0)
        val current = hold.arm()
        assertFalse(hold.settle(old))
        assertFalse(hold.expired(old, 10_000))
        assertTrue(hold.streaming(10_000))
        assertTrue(hold.settle(current))
    }

    @Test fun aRebindWithoutResultsIsShownAtOnce() {
        val hold = PreviewSettle.Hold()
        hold.arm(); hold.disarm()
        assertFalse(hold.streaming(0))
        assertFalse(hold.awaitingResult)
    }
}
