package app.grapheneos.camera.capturer

import org.junit.Assert.assertEquals
import org.junit.Test

class PortraitStoreTest {
    private val day = 24L * 60 * 60 * 1000

    @Test fun keepsRecentOriginalsWithinTheCountCap() {
        val now = 1000 * day
        val entries = (0 until 10).map { "p$it" to now - it * day }
        assertEquals(emptyList<String>(), PortraitStore.expired(entries, now))
    }

    @Test fun dropsTheOldestBeyondTheCountCap() {
        val now = 1000 * day
        val entries = (0 until PortraitStore.MAX_ORIGINALS + 3).map { "p$it" to now - it * 1000L }
        assertEquals(listOf("p${PortraitStore.MAX_ORIGINALS}", "p${PortraitStore.MAX_ORIGINALS + 1}",
            "p${PortraitStore.MAX_ORIGINALS + 2}"), PortraitStore.expired(entries.shuffled(), now))
    }

    @Test fun dropsOriginalsOlderThanTheAgeCap() {
        val now = 1000 * day
        assertEquals(listOf("old"), PortraitStore.expired(listOf("new" to now - day, "old" to now - 400 * day), now))
    }
}
