package app.grapheneos.camera

import org.junit.Assert.*
import org.junit.Test

class PhotoScanSnapshotTest {
    private class Item(val id: String, val uri: String) {
        override fun equals(other: Any?) = other is Item && other.id == id
        override fun hashCode() = id.hashCode()
    }

    @Test fun completionBetweenProviderAndQueueReadsKeepsThePhoto() {
        val pending = Item("capture", "private")
        val scan = PhotoScanSnapshot(listOf(pending))
        val finished = Item("capture", "published")
        // The provider read preceded publication; the final pending list follows completion.
        val result = scan.complete(emptyList(), emptyList()) { finished }
        assertEquals(1, result.size)
        assertEquals("published", result.single().uri)
    }

    @Test fun cancellationDuringTheProviderReadDoesNotResurrectThePendingItem() {
        val scan = PhotoScanSnapshot(listOf(Item("capture", "private")))
        assertTrue(scan.complete(emptyList(), emptyList()) { null }.isEmpty())
    }

    @Test fun providerAndQueueRepresentationsOfOneCaptureProduceOneEntry() {
        val scan = PhotoScanSnapshot(listOf(Item("capture", "private")))
        val provider = Item("capture", "provider-query")
        val result = scan.complete(listOf(provider), emptyList()) { Item("capture", "publication") }
        assertEquals(1, result.size)
        assertSame(provider, result.single())
    }

    @Test fun newCaptureDuringTheProviderReadIsIncluded() {
        val scan = PhotoScanSnapshot<Item>(emptyList())
        val pending = Item("new", "private")
        assertSame(pending, scan.complete(emptyList(), listOf(pending)) { it }.single())
    }
    @Test fun cancellationAfterTheLastPendingSnapshotIsAlsoExcluded() {
        val pending = Item("capture", "private")
        val scan = PhotoScanSnapshot(listOf(pending))
        assertTrue(scan.complete(emptyList(), listOf(pending)) { null }.isEmpty())
    }

    @Test fun newCaptureCompletingAfterTheLastPendingReadResolvesToItsFinalUri() {
        val scan = PhotoScanSnapshot<Item>(emptyList())
        val result = scan.complete(emptyList(), listOf(Item("new", "private"))) {
            Item("new", "published")
        }
        assertEquals("published", result.single().uri)
    }

}
