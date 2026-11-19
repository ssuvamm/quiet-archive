package com.monochrome.annas

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.monochrome.annas.data.AudiobookExportController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Keeps audiobook creation alive in the background and exposes a STOP action. */
class AudiobookExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Preparing audiobook", ""))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AudiobookExportController.requestStop()
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch {
            AudiobookExportController.state.collectLatest { state ->
                if (!state.active) {
                    stopSelf()
                } else {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager?.notify(NOTIFICATION_ID, buildNotification(state.title, state.progress))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audiobook creation",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, progress: String): android.app.Notification {
        val stopIntent = Intent(this, AudiobookExportService::class.java).apply { action = ACTION_STOP }
        val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        val stop = android.app.PendingIntent.getService(this, 2, stopIntent, flags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Making audiobook")
            .setContentText(if (progress.isBlank()) title else "$title · $progress")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_delete, "STOP", stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "audiobook_export"
        private const val NOTIFICATION_ID = 81
        const val ACTION_STOP = "com.monochrome.annas.STOP_AUDIOBOOK"

        fun start(context: Context) {
            val intent = Intent(context, AudiobookExportService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
