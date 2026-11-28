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
 * Keeps Settings → Sound analysis' "Analyse now" going with Prism out of sight, as [MeasureService] does for
 * "Measure songs now": Android closes a process with no foreground service over its background CPU line (#373), and
 * Prism's own budget rests song scans once nothing is playing. This service keeps Prism up while the walk runs, says how
 * far it is in a silent notification with a Stop, and stops it from there too; when the walk ends, finished or stopped,
 * the service goes with it.
 */
class AnalysisService : Service() {
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
            ?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "prism:analyse")
            ?.apply { setReferenceCounted(false); runCatching { acquire() } }
        // Within the seconds Android allows: said from the first moment, its count once the walk has one.
        startForeground(ID, notification(c.analyseSongs.progress.value))
        watcher = scope.launch {
            combine(c.analyseSongs.running, c.analyseSongs.progress) { running, progress -> running to progress }
                .collect { (running, progress) ->
                    // Finished, or stopped from the row or from here: there's nothing left to keep Prism up for.
                    if (!running) {
                        stopSelf()
                        return@collect
                    }
                    NotificationManagerCompat.from(this@AnalysisService).notify(ID, notification(progress))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The notification's Stop: the walk stops, and the watcher above puts the service away with it.
        if (intent?.action == ACTION_STOP) applicationContext.container.analyseSongs.stop()
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
        .setContentTitle(tr(R.string.analysis_now))
        .setContentText(progress?.let { tr(R.string.analysing_n_of_n, it.first, it.second) })
        .setProgress(progress?.second ?: 0, progress?.first ?: 0, progress == null)
        .setOngoing(true)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        // A tap lands on the option that started the walk: the Sound analysis group's "Analyse now" row.
        .setContentIntent(Notifications.openIntent(this, analysisLink(), 0))
        .addAction(
            0, tr(R.string.stop),
            PendingIntent.getService(
                this, 1, Intent(this, AnalysisService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    /** The group holding "Analyse now": Recommendations & history's Sound analysis group (the page the row is on). */
    private fun analysisLink() = Notifications.settingsLink(SettingsPageId.RECOMMENDATIONS_HISTORY.key, tr(R.string.analysis_group))

    /** The channel the walk's notification lives in: silent, so analysing says nothing aloud. */
    private fun createChannel() {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(tr(R.string.analysis_now)).build(),
        )
    }

    companion object {
        const val ACTION_STOP = "com.prism.music.analysis.STOP"
        private const val CHANNEL = "analysis"
        private const val ID = 1003

        /**
         * Starts the service that keeps the walk going — the walk itself is [com.prism.music.data.MeasureSongs]',
         * started already ([com.prism.music.data.MeasureSongs.start]). Harmless to call with one running: the service
         * watches the same walk.
         */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, AnalysisService::class.java)) }
        }
    }
}
