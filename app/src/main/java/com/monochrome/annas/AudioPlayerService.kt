package com.monochrome.annas

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.monochrome.annas.data.AudioController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Foreground service for audiobook playback with lock-screen transport controls. */
class AudioPlayerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var updateJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        updateJob = scope.launch {
            while (true) {
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification())
                delay(1000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> AudioController.toggle()
            ACTION_BACK -> AudioController.skip(-30_000)
            ACTION_FORWARD -> AudioController.skip(30_000)
            ACTION_STOP -> {
                AudioController.stop()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        updateJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Audiobook playback", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun action(icon: Int, label: String, act: String): NotificationCompat.Action {
        val intent = Intent(this, AudioPlayerService::class.java).apply { this.action = act }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return NotificationCompat.Action(
            icon,
            label,
            PendingIntent.getService(this, act.hashCode(), intent, flags),
        )
    }

    private fun notification(): android.app.Notification {
        AudioController.poll()
        val playing = AudioController.playing.value
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(AudioController.title.ifBlank { "Audiobook" })
            .setContentText(if (playing) "Playing · ${time(AudioController.position.value)}" else "Paused · ${time(AudioController.position.value)}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(action(android.R.drawable.ic_media_rew, "-30s", ACTION_BACK))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
                actionPendingIntent(ACTION_TOGGLE),
            )
            .addAction(action(android.R.drawable.ic_media_ff, "+30s", ACTION_FORWARD))
            .setStyle(MediaStyle().setMediaSession(AudioController.sessionToken))
        return builder.build()
    }

    private fun actionPendingIntent(act: String): PendingIntent {
        val intent = Intent(this, AudioPlayerService::class.java).apply { action = act }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, act.hashCode(), intent, flags)
    }

    private fun time(ms: Int): String {
        val total = ms / 1000
        return String.format("%d:%02d", total / 60, total % 60)
    }

    companion object {
        private const val CHANNEL_ID = "audiobook_playback"
        private const val NOTIFICATION_ID = 82
        const val ACTION_TOGGLE = "com.monochrome.annas.AUDIO_TOGGLE"
        const val ACTION_BACK = "com.monochrome.annas.AUDIO_BACK"
        const val ACTION_FORWARD = "com.monochrome.annas.AUDIO_FORWARD"
        const val ACTION_STOP = "com.monochrome.annas.AUDIO_STOP"

        fun start(context: Context) {
            val intent = Intent(context, AudioPlayerService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AudioPlayerService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
