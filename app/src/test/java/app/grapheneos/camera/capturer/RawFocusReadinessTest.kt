package app.grapheneos.camera.capturer

import org.junit.Assert.*
import org.junit.Test

class RawFocusReadinessTest {
    @Test fun twoDistinctStationaryResultsSettle() {
        val p = RawFocusReadiness(); assertFalse(p.observe(1, true)); assertTrue(p.observe(2, true))
    }
    @Test fun duplicateAndOldResultsDoNotAdvance() {
        val p = RawFocusReadiness(); assertFalse(p.observe(1, true)); assertFalse(p.observe(1, true))
        assertFalse(p.observe(0, true)); assertTrue(p.observe(2, true))
    }
    @Test fun lensMovementResetsConsecutiveCount() {
        val p = RawFocusReadiness(); assertFalse(p.observe(1, true)); assertFalse(p.observe(2, false))
        assertFalse(p.observe(3, true)); assertTrue(p.observe(4, true))
    }
}
