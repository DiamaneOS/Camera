package app.grapheneos.camera.capturer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class JournalWriteTest {
    @Test fun fullDiskIsReportedNotThrown() {
        var reported: Throwable? = null
        val written = JournalWrite.quietly({ throw IOException("write failed: ENOSPC (No space left on device)") }) { reported = it }
        assertFalse(written)
        assertEquals(IOException::class.java, reported?.javaClass)
    }

    @Test fun anyThrowableIsContained() {
        var count = 0
        assertFalse(JournalWrite.quietly({ throw OutOfMemoryError() }) { count++ })
        assertEquals(1, count)
    }

    @Test fun successfulWriteReportsNothing() {
        var count = 0
        assertTrue(JournalWrite.quietly({ }) { count++ })
        assertEquals(0, count)
    }
}
