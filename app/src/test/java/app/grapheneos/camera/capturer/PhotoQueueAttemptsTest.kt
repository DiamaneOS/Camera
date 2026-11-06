package app.grapheneos.camera.capturer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoQueueAttemptsTest {
    @Test fun aJobStopsAfterThreeAutomaticAttempts() {
        assertTrue(PhotoQueueAttempts.mayStart(0))
        assertTrue(PhotoQueueAttempts.mayStart(PhotoQueueAttempts.MAX - 1))
        assertFalse(PhotoQueueAttempts.mayStart(PhotoQueueAttempts.MAX))
        assertFalse(PhotoQueueAttempts.mayStart(-1))
    }
}
