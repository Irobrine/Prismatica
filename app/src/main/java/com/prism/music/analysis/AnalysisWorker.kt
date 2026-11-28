package com.prism.music.analysis

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prism.music.container
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Analyses the songs on the phone (files, downloads, songs kept whole in the cache) that have no current analysis, a
 * song at a time through the song scan ([com.prism.music.playback.MixTempo.measure] with `analyse`): the same decode
 * Normalize uses, so each song's loudness is measured on the way. Nothing is downloaded for it.
 *
 * Scheduled once a day, while charging (unless the listener lets it run on battery) with the battery not low, and only
 * while nobody uses the phone: the screen off and nothing playing; it stops as soon as either changes and tries again
 * later, keeping what's done. Settings' "Analyse now" is the listener's own walk instead ([com.prism.music.data.MeasureSongs]
 * over the sets they pick, kept going by [com.prism.music.playback.AnalysisService]). Most played and liked songs first.
 */
class AnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val prefs = c.settings.current.behavior.analysis
        if (!prefs.background) return Result.success()
        val power = applicationContext.getSystemService(PowerManager::class.java)
        // Only while nobody uses the phone: "Analyse now" is the listener's own walk ([com.prism.music.data.MeasureSongs]).
        fun idle() = mayRun(screenOn = power?.isInteractive != false, playing = c.player.isPlaying.value)
        if (!idle()) return Result.retry()
        c.analysis.load()
        return walk(c, ::idle)
    }

    private suspend fun walk(c: com.prism.music.AppContainer, idle: () -> Boolean): Result {
        val oneLevel = c.playsAtOneLevel()
        val forms = candidates(applicationContext).filter { (form, _) ->
            (c.analysis.needs(form) || (oneLevel && !c.mixTempo.normalizationReady(form))) && !c.mixTempo.skipped(form)
        }
        if (forms.isEmpty()) return Result.success()
        val started = System.currentTimeMillis()
        var done = 0
        var interrupted = false
        withTimeoutOrNull(MAX_MINUTES * 60_000L) {
            for ((form, song) in forms) {
                if (isStopped || !idle()) { interrupted = true; break }
                runCatching {
                    // peak: the scan brings the player's normalization figure with it (the same decode).
                    c.mixTempo.measure(
                        form, network = false, edges = false, peak = true, sourceSongId = song.takeIf { it != form },
                        deferToPlaying = true, analyse = true, scheduled = true,
                    )
                }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                if (!c.analysis.needs(form) && (!oneLevel || c.mixTempo.normalizationReady(form))) done++
            }
        } ?: run { interrupted = true }
        Log.i(TAG, "analysed $done of ${forms.size} songs in ${(System.currentTimeMillis() - started) / 1000} s" + if (interrupted) " (more later)" else "")
        // Ran out of time, or the phone came into use, with songs left: tried again later (WorkManager's backoff), keeping
        // what's done.
        return if (interrupted) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "PrismAnalysis"
        private const val DAILY = "sound_analysis"
        /** A run's length: WorkManager stops a job at ten minutes. */
        private const val MAX_MINUTES = 9

        /** Only while nobody's using the phone: the screen off and nothing playing (as DJ mode's pre-scan). */
        internal fun mayRun(screenOn: Boolean, playing: Boolean) = !screenOn && !playing

        /**
         * The songs on the phone worth analysing, as (form, song): played most in the last year first, then liked, then
         * downloads and files, each once.
         */
        internal suspend fun candidates(context: Context): List<Pair<String, String>> {
            val c = context.container
            val since = System.currentTimeMillis() - 365L * 86_400_000
            val played = runCatching { c.db.plays().mostPlayedIds(since, 5_000) }.getOrDefault(emptyList())
            val liked = runCatching { c.db.songs().liked().map { it.id } }.getOrDefault(emptyList())
            val downloaded = c.downloads.completedSongs().map { it.id }
            val local = c.localMusic.tracks.value.map { it.song.id }
            return (played + liked + downloaded + local).distinct()
                .filter { it.startsWith(com.prism.music.data.model.Song.LOCAL_PREFIX) || c.availableOffline(it) }
                .map { c.loudnessIndex.formId(it, null) to it }
                .distinctBy { it.first }
        }

        /** The daily job, as the setting says (kept, or put back with the charging rule changed; removed when off). */
        fun schedule(context: Context, prefs: com.prism.music.data.prefs.AnalysisPrefs = context.container.settings.current.behavior.analysis) {
            val wm = WorkManager.getInstance(context)
            if (!prefs.background) { wm.cancelUniqueWork(DAILY); return }
            val req = PeriodicWorkRequestBuilder<AnalysisWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresCharging(prefs.charging).setRequiresBatteryNotLow(true).build())
                .addTag(if (prefs.charging) "charging" else "battery")
                .build()
            wm.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
