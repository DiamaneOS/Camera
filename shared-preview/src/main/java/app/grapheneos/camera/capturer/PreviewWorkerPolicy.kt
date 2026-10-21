package app.grapheneos.camera.capturer

/** Some Android distributions inject OTHER_SENSORS even into permission-free manifests.
 * Only that known declaration is tolerated, and it must be denied before sharing frames. */
object PreviewWorkerPolicy {
    const val SENSOR_PERMISSION="android.permission.OTHER_SENSORS"
    fun declarationsAllowed(requested:Array<out String>?)=requested.orEmpty().all {it==SENSOR_PERMISSION}
    fun framesAllowed(requested:Array<out String>?,sensorsGranted:Boolean)=declarationsAllowed(requested) && !sensorsGranted
}
