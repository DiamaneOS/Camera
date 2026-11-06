package app.grapheneos.camera.capturer

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.grapheneos.camera.R
import app.grapheneos.camera.ui.activities.InAppGallery
import java.util.UUID

/** Keeps already-captured processing visible if the camera activity leaves the foreground. */
class PhotoProcessingLifetime : Service() {
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var expectedStop = false
    private var lastStartId = 0
    override fun onCreate() {
        super.onCreate()
        activeService = this
        wakeLock = getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Camera:PhotoProcessing").apply {
                setReferenceCounted(false)
                acquire(120_000)
            }
    }

    override fun onBind(intent: Intent): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        expectedStop = false
        lastStartId = startId
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.photo_processing_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, InAppGallery::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.info)
            .setContentTitle(getString(R.string.photo_processing)).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 35) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        else if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        else startForeground(ID, notification, 0)
        wakeLock?.acquire(120_000)
        stopIfIdle()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) { cancelAll(); stopSelf() }
    override fun onTimeout(startId: Int, fgsType: Int) { cancelAll(); stopSelf() }
    override fun onDestroy() {
        if (!expectedStop) cancelAll()
        if (activeService === this) activeService = null
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun stopIfIdle() {
        synchronized(jobs) {
            if (jobs.isNotEmpty()) return
            // Only the latest start may stop the service: a newer start keeps it running.
            expectedStop = true
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (!stopSelfResult(lastStartId)) expectedStop = false
        }
    }

    companion object {
        private const val CHANNEL = "photo_processing"
        private const val ID = 12
        @Volatile private var activeService: PhotoProcessingLifetime? = null
        private val jobs = linkedMapOf<String, () -> Unit>()

        private fun cancelAll() {
            val callbacks = synchronized(jobs) { jobs.values.toList() }
            callbacks.forEach { it() }
        }

        fun begin(context: Context, cancel: () -> Unit): String {
            val id = UUID.randomUUID().toString()
            synchronized(jobs) { check(jobs.size < 8) { "Photo queue full" }; jobs[id] = cancel }
            try { context.startForegroundService(Intent(context, PhotoProcessingLifetime::class.java)) }
            catch (error: Exception) { synchronized(jobs) { jobs.remove(id) }; throw error }
            return id
        }

        /** Each job gets a fresh wake-lock window, so a long queue is not cut off after the first. */
        fun renew() { activeService?.wakeLock?.acquire(120_000) }

        fun end(context: Context, id: String) {
            synchronized(jobs) { jobs.remove(id) }
            context.mainExecutor.execute { activeService?.stopIfIdle() }
        }
    }
}
