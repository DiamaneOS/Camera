package app.grapheneos.camera.capturer

import org.junit.Assert.*
import org.junit.Test

class ProcessingGeometryTest {
    @Test fun oversizedAndExtremeAspectChoicesCannotReachTheWorker() {
        assertTrue(ProcessingGeometry.supported(4096,3072))
        assertFalse(ProcessingGeometry.supported(8192,6144))
        assertFalse(ProcessingGeometry.supported(16384,640))
        assertFalse(ProcessingGeometry.supported(640,7))
        val advertised=listOf(8192 to 6144,4096 to 3072,2048 to 1536)
        val bounded=advertised.filter { ProcessingGeometry.supported(it.first,it.second) }
        assertEquals(listOf(4096 to 3072,2048 to 1536),bounded)
        // The automatic maximum and a stale 50MP preference share the same set.
        assertEquals(4096 to 3072,bounded.maxBy { it.first.toLong()*it.second })
        assertFalse((8192 to 6144) in bounded)
    }
    @Test fun serialRawMustMeetTheNativeCfaAlignmentContract() {
        assertTrue(ProcessingGeometry.supported(4208,3120,raw=true))
        assertTrue(ProcessingGeometry.supported(4095,3072))
        assertFalse(ProcessingGeometry.supported(4095,3072,raw=true))
        assertFalse(ProcessingGeometry.supported(4096,3070,raw=true))
    }
}
