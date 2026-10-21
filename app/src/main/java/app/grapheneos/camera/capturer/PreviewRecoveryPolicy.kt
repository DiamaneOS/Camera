package app.grapheneos.camera.capturer

/** One recovery per stalled episode; healthy frames must persist before re-arming. */
class PreviewRecoveryPolicy {
    private var staleSince=-1L
    private var healthySince=-1L
    private var attempted=false
    fun reset() {staleSince=-1;healthySince=-1;attempted=false}
    fun observe(now: Long,eligible: Boolean,framesRecent: Boolean): Boolean {
        if(!eligible) {staleSince=-1;healthySince=-1;return false}
        if(framesRecent) {
            staleSince=-1
            if(healthySince<0 || now<healthySince) healthySince=now
            if(now-healthySince>=10_000) attempted=false
            return false
        }
        healthySince=-1
        if(staleSince<0 || now<staleSince) staleSince=now
        if(attempted || now-staleSince<4000) return false
        attempted=true;return true
    }
}
