package app.grapheneos.camera.capturer

/** At most one processing task at a time, whichever service instance accepted it. */
internal class ProcessingAdmission {
    private var running = false

    @Synchronized fun tryEnter(): Boolean {
        if (running) return false
        running = true
        return true
    }

    @Synchronized fun leave() {
        check(running) { "Processing admission was not held" }
        running = false
    }
}
