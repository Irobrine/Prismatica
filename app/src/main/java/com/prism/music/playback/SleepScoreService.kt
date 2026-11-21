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
import com.prism.music.MainActivity
import com.prism.music.R
import com.prism.music.container
import com.prism.music.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the "Calculate sleep scores" walks going with Prism out of sight (1.9).
 *
 * Scoring a library is a whole recording decoded and analysed per song, the same weight as "Measure songs now": out of
 * sight Android would close the process over its background CPU line, and Prism's own budget rests such work to nothing
 * with nothing playing ([BackgroundBudget]). This service — the kind downloads run under — keeps the walk up, says how
 * far it is in a silent notification with a Stop, and stops it from there too. When the walk ends, finished or stopped,
 * the service goes with it. The walk itself is the container's ([com.prism.music.AppContainer.sleepScoreSongs]), shared
 * by Settings' row and the Select many bar, so the two never run two calculations at once.
 */
class SleepScoreService : Service() {
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
            ?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "prism:sleep-scores")
            ?.apply { setReferenceCounted(false); runCatching { acquire() } }
        // Within the seconds Android allows: said from the first moment, its count once the walk has one.
        startForeground(ID, notification(c.sleepScoreSongs.progress.value))
        watcher = scope.launch {
            combine(c.sleepScoreSongs.running, c.sleepScoreSongs.progress) { running, progress -> running to progress }
                .collect { (running, progress) ->
                    // Finished, or stopped from the row, the bar or from here: nothing left to keep Prism up for.
                    if (!running) {
                        stopSelf()
                        return@collect
                    }
                    NotificationManagerCompat.from(this@SleepScoreService).notify(ID, notification(progress))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The notification's Stop: the walk stops, and the watcher above puts the service away with it.
        if (intent?.action == ACTION_STOP) applicationContext.container.sleepScoreSongs.stop()
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
        .setContentTitle(tr(R.string.calculate_sleep_scores))
        .setContentText(progress?.let { tr(R.string.calculating_n_of_n, it.first, it.second) })
        .setProgress(progress?.second ?: 0, progress?.first ?: 0, progress == null)
        .setOngoing(true)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .addAction(
            0, tr(R.string.stop),
            PendingIntent.getService(
                this, 1, Intent(this, SleepScoreService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    /** The channel the walk's notification lives in: silent, so scoring says nothing aloud. */
    private fun createChannel() {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(tr(R.string.calculate_sleep_scores)).build(),
        )
    }

    companion object {
        const val ACTION_STOP = "com.prism.music.sleepscores.STOP"
        private const val CHANNEL = "sleep_scores"
        private const val ID = 1003

        /**
         * Starts the service that keeps the walk going — the walk itself is the container's, started already. Harmless
         * to call with one running: the service watches the same walk.
         */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, SleepScoreService::class.java)) }
        }
    }
}
