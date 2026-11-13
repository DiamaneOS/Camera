package app.grapheneos.camera.capturer

import android.hardware.camera2.*
import android.os.SystemClock

/** Metadata belongs to one bind generation and the camera device that produced it. */
class CaptureResultMonitor(private val observe: (TotalCaptureResult) -> Unit) {
    private val lock=Object()
    private val results=linkedMapOf<Long,TotalCaptureResult>()
    private var generation=0L
    private var cameraId: String?=null
    private var receivedAt=0L
    @Volatile var latest: TotalCaptureResult?=null
        private set
    fun forSession(): CameraCaptureSession.CaptureCallback = synchronized(lock) {
        val expected=generation
        object: CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession,request: CaptureRequest,result: TotalCaptureResult) {
                val timestamp=result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                synchronized(lock) {
                    if(expected!=generation) return
                    latest=result;cameraId=session.device.id;receivedAt=SystemClock.elapsedRealtime()
                    results[timestamp]=result
                    while(results.size>64) results.remove(results.keys.first())
                    lock.notifyAll()
                }
                observe(result)
            }
        }
    }
    fun latestFor(id: String): TotalCaptureResult? = synchronized(lock) {
        latest.takeIf {cameraId==id && SystemClock.elapsedRealtime()-receivedAt<1000}
    }
    fun hasRecentFrame(): Boolean = synchronized(lock) {
        latest!=null && SystemClock.elapsedRealtime()-receivedAt<1500
    }
    internal fun captureCursor(): Pair<Long,Long> = synchronized(lock) {
        generation to (latest?.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L)
    }
    internal fun stillFrames(cursor: Pair<Long,Long>): List<TotalCaptureResult> = synchronized(lock) {
        if (cursor.first != generation) emptyList() else results.filter { (time,result) ->
            time > cursor.second && result.request?.get(CaptureRequest.CONTROL_CAPTURE_INTENT) == CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE
        }.values.toList()
    }
    fun await(timestamp: Long): TotalCaptureResult = synchronized(lock) {
        val deadline=SystemClock.elapsedRealtime()+2000
        while(results[timestamp]==null) {
            val remaining=deadline-SystemClock.elapsedRealtime();check(remaining>0) {"RAW still calibration missing"};lock.wait(remaining)
        }
        results.remove(timestamp)!!
    }
    fun clear() {synchronized(lock) {generation++;latest=null;cameraId=null;receivedAt=0;results.clear();lock.notifyAll()}}
}
