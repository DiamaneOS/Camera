package app.grapheneos.camera.capturer

/**
 * When Android's camera access block (Quick Settings or a hardware switch) closes the camera,
 * CameraX reports ERROR_CAMERA_DISABLED and never reopens it by itself. This allows one rebind per
 * return of access: the camera must have closed under the block, and access must have been seen
 * blocked and then allowed again. A camera disabled for another reason, with access never blocked,
 * is left alone, so a failing rebind cannot repeat.
 */
class CameraBlockRecovery {
    enum class State { OPEN, OPENING, CLOSED }

    private var closedByBlock = false
    private var disabledSinceOpen = false
    private var accessWasBlocked = false

    /** Access is back and the rebind waits, for example for a recording to end. */
    var pending = false
        private set

    fun reset() {
        closedByBlock = false
        disabledSinceOpen = false
        accessWasBlocked = false
        pending = false
    }

    /** The app rebinds the camera, for this or its own reasons. */
    fun onRebind() {
        closedByBlock = false
        disabledSinceOpen = false
        pending = false
    }

    fun onAccess(allowed: Boolean) {
        if (!allowed) {
            accessWasBlocked = true
            pending = false
        } else if (closedByBlock && accessWasBlocked) {
            accessWasBlocked = false
            pending = true
        }
    }

    /**
     * The bound camera's state, whether it carries ERROR_CAMERA_DISABLED, and whether access is
     * allowed at that moment. LiveData can merge CameraX's last states, so a close after a disabled
     * open attempt, or while access is blocked, counts as closed by the block too.
     */
    fun onCameraState(state: State, disabled: Boolean, accessAllowed: Boolean) {
        when (state) {
            State.OPEN -> {
                onRebind()
                if (accessAllowed) accessWasBlocked = false
                return
            }
            // CameraX itself still tries to open it.
            State.OPENING -> {
                closedByBlock = false
                if (disabled) disabledSinceOpen = true
            }
            State.CLOSED -> if (disabled || disabledSinceOpen || !accessAllowed) closedByBlock = true
        }
        onAccess(accessAllowed)
    }

    /** True when the camera should be rebound now. */
    fun rebindNow(busy: Boolean) = pending && closedByBlock && !busy

    companion object {
        /** Access is blocked, or the camera reports ERROR_CAMERA_DISABLED while not open. */
        fun endsRecording(state: State, disabled: Boolean, accessAllowed: Boolean) =
            !accessAllowed || (disabled && state != State.OPEN)
    }
}
