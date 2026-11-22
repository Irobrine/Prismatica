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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps a duplicate-finding walk going after the listener leaves the screen (the same shape as [MeasureService]).
 *
 * The scan is still manual — this service only keeps the walk Android would otherwise suspend: a foreground service
 * of the declared `dataSync` type and a partial wake lock. It shows how many files were processed and a Stop action,
 * and goes away with the walk. The scan itself touches no network for local files.
 */
class DedupScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var wake: android.os.PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val c: AppContainer = applicationContext.container
        createChannel()
        wake = getSystemService(android.os.PowerManager::class.java)
            ?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "prism:dedup")
            ?.apply { setReferenceCounted(false); runCatching { acquire() } }
        startForeground(ID, notification(c.dedup.scanner.progress.value))
        watcher = scope.launch {
            combine(c.dedup.scanner.running, c.dedup.scanner.progress) { running, progress -> running to progress }
                .collect { (running, progress) ->
                    if (!running) {
                        stopSelf()
                        return@collect
                    }
                    NotificationManagerCompat.from(this@DedupScanService).notify(ID, notification(progress))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) applicationContext.container.dedup.scanner.stop()
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
        .setContentTitle(tr(R.string.dedup_scan_running))
        .setContentText(progress?.let { tr(R.string.dedup_scan_progress, it.first, it.second) })
        .setProgress(progress?.second ?: 0, progress?.first ?: 0, progress == null)
        .setOngoing(true)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        // A tap returns to the Duplicate songs sheet, where the scan was started and its findings show.
        .setContentIntent(Notifications.openIntent(this, Notifications.OPEN_DEDUP, 0))
        .addAction(
            0, tr(R.string.stop),
            PendingIntent.getService(
                this, 1, Intent(this, DedupScanService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun createChannel() {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(tr(R.string.dedup_scan_running)).build(),
        )
    }

    companion object {
        const val ACTION_STOP = "com.prism.music.dedup.STOP"
        private const val CHANNEL = "dedup"
        private const val ID = 1003

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DedupScanService::class.java)) }
        }
    }
}
