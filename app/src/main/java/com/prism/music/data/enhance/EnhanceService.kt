package com.prism.music.data.enhance

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps a walk through the enhancement queue going with Prism out of sight, as [com.prism.music.playback.MeasureService]
 * does for measuring.
 *
 * The engines are hundreds of times slower than the player, so a batch of songs can run for an hour. Android closes a
 * process with no foreground service over its background CPU line (#373), and freezes a cached one outright; either way
 * the work would wait for the screen to come back. This service keeps Prism up while the queue runs, says how far it is
 * in a silent notification with a Cancel, and cancels it from there too. When the queue drains, it goes with it.
 *
 * The work itself stays in [EnhancementService]: this only watches the same queue the app's sheets show.
 */
class EnhanceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    /** Keeps the processor awake: a service alone doesn't, and a locked phone would sleep on a run that takes an hour. */
    private var wake: android.os.PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val c: AppContainer = applicationContext.container
        createChannel()
        wake = getSystemService(android.os.PowerManager::class.java)
            ?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "prism:enhance")
            ?.apply { setReferenceCounted(false); runCatching { acquire() } }
        // Within the seconds Android allows: said from the first moment, its count once the queue has one.
        startForeground(ID, notification(c.enhance.tasks.value))
        watcher = scope.launch {
            c.enhance.tasks.collectLatest { tasks ->
                val busy = tasks.any { it.state == "queued" || it.state == "running" }
                if (!busy) {
                    // Two songs of a batch follow one another within milliseconds, which looks the same as the end: only
                    // a queue that is still empty a moment later is the end. A new state cancels this wait.
                    delay(STOP_GRACE_MS)
                    val still = c.enhance.tasks.value.any { it.state == "queued" || it.state == "running" }
                    if (!still) stopSelf()
                    return@collectLatest
                }
                NotificationManagerCompat.from(this@EnhanceService).notify(ID, notification(tasks))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The notification's Cancel: the queue stops, and the watcher above puts the service away with it.
        if (intent?.action == ACTION_CANCEL) applicationContext.container.enhance.cancelAll()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        wake?.let { runCatching { if (it.isHeld) it.release() } }
        wake = null
        super.onDestroy()
    }

    /** The running jobs' progress, as the queue's sheets show it; no count until one is running. */
    private fun notification(tasks: List<EnhanceTask>): Notification {
        val percent = tasks.filter { it.state == "running" }.maxOfOrNull { it.progress }
            ?.let { (it * 100).toInt().coerceIn(0, 100) }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.enhance_lyrics_enhancement))
            .setContentText(percent?.let { tr(R.string.enhance_running, it) })
            .setProgress(100, percent ?: 0, percent == null)
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
                0, tr(R.string.enhance_cancel),
                PendingIntent.getService(
                    this, 1, Intent(this, EnhanceService::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
    }

    /** The channel the queue's notification lives in: silent, so a batch says nothing aloud. */
    private fun createChannel() {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(tr(R.string.enhance_lyrics_enhancement)).build(),
        )
    }

    companion object {
        const val ACTION_CANCEL = "com.prism.music.enhance.CANCEL"
        private const val CHANNEL = "enhance"
        private const val ID = 1003
        /** How long an empty queue has to stay empty before the service goes: a step between two songs is not the end. */
        private const val STOP_GRACE_MS = 2_000L

        /**
         * Starts the service that keeps the queue going — the work itself is [EnhancementService]'s, started already
         * ([EnhancementService.enqueue], [EnhancementService.resumeInterrupted]). Harmless to call with one running: the
         * service watches the same queue.
         */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, EnhanceService::class.java)) }
        }
    }
}
