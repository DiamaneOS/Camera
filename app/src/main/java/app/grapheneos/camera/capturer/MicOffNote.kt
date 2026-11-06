package app.grapheneos.camera.capturer

/**
 * Android's microphone block does not stop a recording, the sound after it is silent. This says
 * when to tell the user, once per block. Blocking the camera as well ends the recording with its
 * own message, so this stays quiet then.
 */
class MicOffNote {
    private var shown = false

    fun reset() {
        shown = false
    }

    /** True when the note should be shown now. */
    fun onAccess(recordingWithSound: Boolean, microphoneAllowed: Boolean, cameraAllowed: Boolean): Boolean {
        if (!recordingWithSound || microphoneAllowed) {
            shown = false
            return false
        }
        if (!cameraAllowed || shown) return false
        shown = true
        return true
    }
}
