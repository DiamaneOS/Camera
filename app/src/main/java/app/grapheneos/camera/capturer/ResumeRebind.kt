package app.grapheneos.camera.capturer

/**
 * The camera is rebound on resume, since onStop closes the preview effects. A dialog over the app
 * pauses it without a stop, and a rebind would end a running recording, so there is none then.
 * After a stop, whose recording may still be finishing, the rebind waits for it to end.
 */
class ResumeRebind {
    /** A stop needs a rebind that waits for the recording to end. */
    var pending = false
        private set

    fun onStop() {
        pending = true
    }

    /** True when the camera should be rebound now. */
    fun onResume(recording: Boolean): Boolean {
        if (recording) return false
        pending = false
        return true
    }

    /** True when the rebind that waited should run now. */
    fun afterRecording(busy: Boolean): Boolean {
        if (!pending || busy) return false
        pending = false
        return true
    }
}
