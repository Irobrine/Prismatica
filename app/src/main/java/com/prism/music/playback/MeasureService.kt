package com.prism.music.playback

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.prism.music.AppContainer
import com.prism.music.R
import com.prism.music.container
import com.prism.music.data.Notifications
import com.prism.music.tr
import com.prism.music.ui.screens.settings.SettingsPageId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps Settings → Sound & quality's "Measure songs now" going with Prism out of sight (1.1.3).
 *
 * Android looks at a process with no foreground service and closes one over its background CPU line (#373), and Prism's
 * own budget rests song scans to nothing out of sight with nothing playing ([BackgroundBudget]): a walk the listener
 * started would wait for the screen to come back. This service — the kind downloads run under — keeps Prism up and out
 * of those lines while the walk runs (a core's worth, as music playing: [BackgroundBudget.where]), says how far it is
 * in a silent notification with a Stop, and stops it from there too. When the walk ends, finished or stopped, the
 * service goes with it.
 */
class MeasureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    /** Keeps the processor awake while the walk runs: a service alone doesn't, and a locked phone would sleep on it. */
    private var wake: android.os.PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val c: AppContainer = applicationContext.container
        createChannel()
        wake = getSystemService(android.os.PowerManager::class.java)
            ?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "prism:measure")
            ?.apply { setReferenceCounted(false); runCatching { acquire() } }
        // Within the seconds Android allows: said from the first moment, its count once the walk has one.
        startForeground(ID, notification(c.measureSongs.progress.value))
        watcher = scope.launch {
            combine(c.measureSongs.running, c.measureSongs.progress) { running, progress -> running to progress }
                .collect { (running, progress) ->
                    // Finished, or stopped from the row or from here: there's nothing left to keep Prism up for.
                    if (!running) {
                        stopSelf()
                        return@collect
                    }
                    NotificationManagerCompat.from(this@MeasureService).notify(ID, notification(progress))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The notification's Stop: the walk stops, and the watcher above puts the service away with it.
        if (intent?.action == ACTION_STOP) applicationContext.container.measureSongs.stop()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        wake?.let { runCatching { if (it.isHeld) it.release() } }
        wake = null
        super.onDestroy()
    }

    private fun notification(progress: Pair<Int, Int>?): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(tr(R.string.measure_songs_now))
        .setContentText(progress?.let { tr(R.string.measuring_n_of_n, it.first, it.second) })
        .setProgress(progress?.second ?: 0, progress?.first ?: 0, progress == null)
        .setOngoing(true)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        // A tap lands on the option that started the walk: Sound & quality's "Measure songs now" row.
        .setContentIntent(Notifications.openIntent(this, measureLink(), 0))
        .addAction(
            0, tr(R.string.stop),
            PendingIntent.getService(
                this, 1, Intent(this, MeasureService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    /** The group holding "Measure songs now" is the Sound group, the same one the player's Equalizer button opens. */
    private fun measureLink() = Notifications.settingsLink(SettingsPageId.SOUND_QUALITY.key, tr(R.string.sound))

    /** The channel the walk's notification lives in: silent, so measuring says nothing aloud. */
    private fun createChannel() {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(tr(R.string.measure_songs_now)).build(),
        )
    }

    companion object {
        const val ACTION_STOP = "com.prism.music.measure.STOP"
        private const val CHANNEL = "measure"
        private const val ID = 1002

        /**
         * Starts the service that keeps the walk going — the walk itself is [com.prism.music.data.MeasureSongs]', started
         * already ([MeasureSongs.start]). Harmless to call with one running: the service watches the same walk.
         */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, MeasureService::class.java)) }
        }
    }
}
