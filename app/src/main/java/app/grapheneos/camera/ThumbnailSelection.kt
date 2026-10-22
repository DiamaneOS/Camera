package app.grapheneos.camera

/** What the gallery button shows after a scan of this app's own captures. */
internal sealed interface ThumbnailChoice<out T> {
    /** The shown capture still exists and nothing newer does, so the button is left alone. */
    data object Keep : ThumbnailChoice<Nothing>
    data class Show<T>(val item: T) : ThumbnailChoice<T>
    /** No capture is left. */
    data object Empty : ThumbnailChoice<Nothing>
}

internal object ThumbnailSelection {
    /**
     * The newest of [existing] wins. [current] is what the button shows now and [existing] holds
     * only captures that still resolve, so a capture deleted or trashed elsewhere is absent.
     */
    fun <T : Any> choose(current: T?, existing: List<T>, dateOf: (T) -> String): ThumbnailChoice<T> {
        val newest = existing.maxByOrNull(dateOf)
            ?: return if (current == null) ThumbnailChoice.Keep else ThumbnailChoice.Empty
        if (current != null && current in existing && dateOf(newest) <= dateOf(current)) {
            return ThumbnailChoice.Keep
        }
        return ThumbnailChoice.Show(newest)
    }

    /** Drops the captures found gone and keeps any added to [list] while the check ran. */
    fun <T> pruneGone(list: MutableList<T>, gone: Collection<T>) {
        if (gone.isNotEmpty()) list.removeAll(gone.toSet())
    }
}
