package app.grapheneos.camera.capturer

import java.util.Collections
import java.util.IdentityHashMap

/** Closure is an event, not an inference from an open/close timeout. */
internal class DirectCameraLease<T> {
    private var acquired=false
    private var previousClosed=false
    private var opening=false
    private var closing=false
    private var resourcesClosed=false
    private val devices=Collections.newSetFromMap(IdentityHashMap<T,Boolean>())

    @Synchronized fun acquire(action:()->Unit):Boolean {
        if(closing)return false
        check(!acquired);action();acquired=true;return true
    }
    @Synchronized fun beginOpen() {check(acquired && !closing && !opening);opening=true}
    @Synchronized fun arrived(device:T):Boolean {opening=false;devices.add(device);return closing}
    @Synchronized fun closed(device:T) {devices.remove(device);opening=false}
    @Synchronized fun openFailed() {opening=false}
    @Synchronized fun requestClose():List<T> {closing=true;return devices.toList()}
    @Synchronized fun resourcesClosed() {resourcesClosed=true}
    @Synchronized fun previousClosed() {previousClosed=true}
    @Synchronized fun wasAcquired()=acquired
    @Synchronized fun ready()=closing && resourcesClosed && !opening && devices.isEmpty() && (!acquired || previousClosed)
}
