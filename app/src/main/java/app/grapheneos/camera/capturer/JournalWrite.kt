package app.grapheneos.camera.capturer

/** A journal write that reports failure instead of throwing, for paths that already handle a failure. */
internal object JournalWrite {
    inline fun quietly(write: () -> Unit, onFailure: (Throwable) -> Unit): Boolean = try {
        write(); true
    } catch (error: Throwable) {
        onFailure(error); false
    }
}
