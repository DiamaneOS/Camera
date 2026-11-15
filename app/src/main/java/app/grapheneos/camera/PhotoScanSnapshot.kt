package app.grapheneos.camera

/** Keep captures that finish between provider enumeration and the queue's final snapshot. */
internal class PhotoScanSnapshot<T : Any>(pendingBeforeScan: List<T>) {
    private val before = pendingBeforeScan.toList()

    fun complete(media: List<T>, pendingNow: List<T>, resolve: (T) -> T?): List<T> =
        (media + (before + pendingNow).mapNotNull(resolve)).distinct()
}
