package app.grapheneos.camera.capturer
import org.junit.Assert.*
import org.junit.Test

class PreviewRecoveryPolicyTest {
    @Test fun sustainedStallRecoversOnlyOnce() {
        val p=PreviewRecoveryPolicy()
        assertFalse(p.observe(0,true,false));assertFalse(p.observe(3999,true,false))
        assertTrue(p.observe(4000,true,false));assertFalse(p.observe(15000,true,false))
    }
    @Test fun acquisitionOrBackgroundDoesNotTriggerRecovery() {
        val p=PreviewRecoveryPolicy()
        assertFalse(p.observe(0,true,false));assertFalse(p.observe(5000,false,false))
        assertFalse(p.observe(6000,true,false));assertFalse(p.observe(9000,true,false))
        assertTrue(p.observe(10000,true,false))
    }
    @Test fun continuousHealthyFramesRearmAfterRecovery() {
        val p=PreviewRecoveryPolicy();p.observe(0,true,false);assertTrue(p.observe(4000,true,false))
        assertFalse(p.observe(5000,true,true));assertFalse(p.observe(15000,true,true))
        assertFalse(p.observe(16000,true,false));assertTrue(p.observe(20000,true,false))
    }
    @Test fun interruptedHealthDoesNotRearmAStuckSession() {
        val p=PreviewRecoveryPolicy();p.observe(0,true,false);p.observe(4000,true,false)
        p.observe(5000,true,true);p.observe(6000,true,false)
        assertFalse(p.observe(16000,true,false))
    }
}
