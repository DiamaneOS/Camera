package app.grapheneos.camera

import org.junit.Assert.*
import org.junit.Test

class ThumbnailSelectionTest {
    // Equal by capture name like CapturedItem, while the uri can differ between queries.
    private class Item(val date: String, val uri: String = date) {
        override fun equals(other: Any?) = other is Item && other.date == date
        override fun hashCode() = date.hashCode()
    }

    private fun choose(current: Item?, vararg existing: Item) =
        ThumbnailSelection.choose(current, existing.toList()) { it.date }

    @Test fun existingNewestCaptureIsKept() {
        val shown = Item("20261022_210638_416", "media/images/634")
        assertEquals(ThumbnailChoice.Keep, choose(shown, Item("20261021_100000"), Item("20261022_210638_416", "media/file/634")))
    }

    @Test fun deletedCaptureHandsOverToTheNewestLeft() {
        val older = Item("20261021_100000")
        val newer = Item("20261022_090000_001")
        assertEquals(ThumbnailChoice.Show(newer), choose(Item("20261022_210638_416"), older, newer))
    }

    @Test fun lastCaptureDeletedLeavesTheEmptyState() {
        assertEquals(ThumbnailChoice.Empty, choose(Item("20261022_210638_416")))
    }

    @Test fun emptyStaysEmptyWithoutChange() {
        assertEquals(ThumbnailChoice.Keep, choose(null))
    }

    @Test fun noShownCaptureTakesTheNewest() {
        val newest = Item("20261022_090000")
        assertEquals(ThumbnailChoice.Show(newest), choose(null, Item("20261021_100000"), newest))
    }

    @Test fun newerCaptureFromAnotherWindowReplacesAnOlderOne() {
        val shown = Item("20261021_100000")
        val newer = Item("20261022_090000_500")
        assertEquals(ThumbnailChoice.Show(newer), choose(shown, shown, newer))
    }

    @Test fun videoNameWithoutMillisecondsSortsBeforeAPhotoOfTheSameSecond() {
        val video = Item("20261022_090000")
        val photo = Item("20261022_090000_250")
        assertEquals(ThumbnailChoice.Show(photo), choose(null, video, photo))
        assertEquals(ThumbnailChoice.Keep, choose(photo, video, photo))
    }

    @Test fun pruningDropsGoneItemsAndKeepsOnesAddedDuringTheCheck() {
        val (a, b, c) = listOf(Item("a"), Item("b"), Item("c"))
        val added = Item("d")
        val list = mutableListOf(a, b, c, added)
        ThumbnailSelection.pruneGone(list, listOf(Item("b", "other-uri")))
        assertEquals(listOf(a, c, added), list)
        ThumbnailSelection.pruneGone(list, emptyList())
        assertEquals(listOf(a, c, added), list)
    }
}
