package app.grapheneos.camera.capturer

import org.junit.Assert.*
import org.junit.Test

class PortraitPreviewPolicyTest {
    @Test fun freshnessStartsAtCaptureRatherThanReply() {
        val p = PortraitPreviewPolicy(); val sequence = p.begin(100, 3)
        assertTrue(p.reply(sequence, 3, 650)); assertTrue(p.fresh(899)); assertFalse(p.fresh(901))
    }
    @Test fun lateReplyCannotReleaseNewRequest() {
        val p = PortraitPreviewPolicy(); val old = p.begin(0, 1); p.expire(3001)
        val current = p.begin(3002, 1)
        assertFalse(p.reply(old, 1, 3010)); assertTrue(p.busy)
        assertTrue(p.reply(current, 1, 3100)); assertFalse(p.busy)
    }
    @Test fun subjectChangeWaitsForOldRequestBeforeSubmittingAnother() {
        val p = PortraitPreviewPolicy(); val sequence = p.begin(0, 1); p.invalidateMask()
        assertTrue(p.busy); assertFalse(p.reply(sequence, 2, 100)); assertFalse(p.busy); assertFalse(p.fresh(100))
    }
    @Test fun expiredAndPausedRepliesCannotBecomeVisible() {
        val p = PortraitPreviewPolicy(); val old = p.begin(100, 0)
        assertFalse(p.reply(old, 0, 901)); assertFalse(p.fresh(901))
        val paused = p.begin(1000, 0); p.reset(); val resumed = p.begin(1100, 0)
        assertFalse(p.reply(paused, 0, 1150)); assertTrue(p.busy); assertTrue(p.reply(resumed, 0, 1200))
    }
}
