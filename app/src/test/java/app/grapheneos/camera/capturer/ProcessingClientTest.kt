package app.grapheneos.camera.capturer

import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessingClientTest {
    @Test fun busyWorkerBackoffDoublesAndIsBounded() {
        assertEquals(listOf(250L, 500L, 1000L, 2000L, 4000L, 4000L, 4000L), (0..6).map(ProcessingClient::busyBackoffMs))
    }
}
