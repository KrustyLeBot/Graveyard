package app.graveyard.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import app.graveyard.R

/**
 * Keeps the process and the CPU awake while a text is read aloud, so generation speed can also be
 * judged with the screen off. It plays nothing itself: [Speaker] does.
 */
class PlaybackService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Lecture à voix haute", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_graveyard)
            .setContentTitle("Lecture à voix haute en cours")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, notification)
        }
        // Stopping goes through here too: Android requires startForeground after every
        // startForegroundService, even when the reading ended before the service came up.
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "graveyard:speech")
                .apply { acquire(4 * 60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "speech"
        private const val ACTION_STOP = "app.graveyard.STOP_SPEECH"
        private var started = false

        fun start(context: Context) {
            started = runCatching { context.startForegroundService(Intent(context, PlaybackService::class.java)) }.isSuccess
        }

        fun stop(context: Context) {
            if (!started) return
            started = false
            val stop = Intent(context, PlaybackService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(stop) }.onFailure { context.stopService(Intent(context, PlaybackService::class.java)) }
        }
    }
}
