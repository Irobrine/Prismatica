package com.prism.music.data.sleep

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Songs' sleep suitability figures (1.9), kept on this phone by the recording they describe
 * ([com.prism.music.playback.Loudness.formId]): a music video, a picked version or a file plays audio of its own, and
 * only what actually plays can be judged. The stored shape is [SleepScore]'s JSON, so the components, the confidence,
 * the warnings and the mode travel with the figure.
 *
 * Two kinds are kept apart, each in its own preference file:
 *
 *  - **plain** figures ([SleepScore.normalized] false), measured on the recording's own sound, and
 *  - **normalized** figures, measured with playback's Normalize gain applied (the "Use normalized audio" setting).
 *
 * A figure is only used in the mode it was made for: [current] with the mode reads that mode's file, so with normalized
 * mode on a normalized figure is the one that counts (it overrides a plain one for the same recording), and with it off
 * only plain figures do — the normalized ones stay stored, so switching back does not have to recalculate them. That is
 * also the cache rule: a recording's audio is identified by its form id, and each mode's figure is reused until the
 * scoring version changes or it is calculated again.
 *
 * A figure from an older [SleepAnalysis.MODEL_VERSION] is not used: [current] returns null, the sleep timer's filter
 * passes the song over and the next calculation rewrites it in the active mode. Kept in SharedPreferences (like the
 * scans' own figures): the lookups are per song while the timer runs, and a library's worth of JSON is a few hundred
 * kilobytes. Reads and writes are safe from the playback thread.
 */
class SleepScores(context: Context) {
    /** One mode's figures: its own file, cache and count. */
    private class Shelf(private val prefs: SharedPreferences) {
        /**
         * What's on the phone, read once: a queue's worth of lookups (the timer's filter, tens of times a second) never
         * touches the preference file again, and a write keeps it in step.
         */
        private val cache = java.util.concurrent.ConcurrentHashMap<String, SleepScore>()

        /** Recordings looked for and found to have nothing: an unscored song isn't re-read on every half-second check. */
        private val absent = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        @Volatile private var loaded = false

        private val _count = MutableStateFlow(0)
        val count: StateFlow<Int> = _count

        init { _count.value = countNow() }

        private fun load() {
            if (loaded) return
            synchronized(cache) {
                if (loaded) return
                for ((key, value) in prefs.all) {
                    val text = value as? String ?: continue
                    SleepScore.decode(text)?.let { cache[key] = it }
                }
                loaded = true
            }
        }

        fun stored(formId: String): SleepScore? {
            load()
            cache[formId]?.let { return it }
            if (formId in absent) return null
            // Not in the cache: look the one key up (a figure written by something else than this instance, a backup's
            // restore), and remember either answer.
            val parsed = SleepScore.decode(prefs.getString(formId, null))
            if (parsed == null) absent += formId else cache[formId] = parsed
            return parsed
        }

        @Synchronized
        fun put(formId: String, score: SleepScore) {
            prefs.edit().putString(formId, SleepScore.encode(score)).apply()
            absent.remove(formId)
            cache[formId] = score
            loaded = true
            _count.value = countNow()
        }

        @Synchronized
        fun forget(formId: String) {
            prefs.edit().remove(formId).apply()
            cache.remove(formId)
            absent += formId
            _count.value = countNow()
        }

        @Synchronized
        fun clear() {
            prefs.edit().clear().apply()
            cache.clear()
            absent.clear()
            loaded = true
            _count.value = 0
        }

        fun ids(): Set<String> {
            load()
            return cache.keys.toSet()
        }

        fun all(): Map<String, SleepScore> {
            load()
            return cache.toMap()
        }

        private fun countNow(): Int {
            load()
            return cache.values.count { it.modelVersion == SleepAnalysis.MODEL_VERSION }
        }
    }

    private val plain = Shelf(context.getSharedPreferences("sleep_scores", Context.MODE_PRIVATE))
    private val normalized = Shelf(context.getSharedPreferences("sleep_scores_normalized", Context.MODE_PRIVATE))

    /** How many recordings have a plain figure of the current version (the settings page's row in plain mode). */
    val calculated: StateFlow<Int> = plain.count

    /** How many recordings have a normalized figure of the current version (the settings page's row in that mode). */
    val calculatedNormalized: StateFlow<Int> = normalized.count

    /** The active mode's count, for a page that follows the setting. */
    fun calculated(normalized: Boolean): StateFlow<Int> = if (normalized) this.normalized.count else plain.count

    private fun shelf(normalized: Boolean) = if (normalized) this.normalized else plain

    /** The recording's figure for [normalized]'s mode, whatever version made it (null when that mode has none). */
    fun stored(formId: String, normalized: Boolean = false): SleepScore? =
        shelf(normalized).stored(formId)?.takeIf { it.normalized == normalized }

    /** The recording's figure for the mode when it's from the current scoring version; null for missing or outdated. */
    fun current(formId: String, normalized: Boolean = false): SleepScore? =
        stored(formId, normalized)?.takeIf { it.modelVersion == SleepAnalysis.MODEL_VERSION }

    /** The figure's score (0–100) for the mode when it's current, else null: what the sleep timer's filter asks for. */
    fun score(formId: String, normalized: Boolean = false): Int? = current(formId, normalized)?.score

    /** Whether [formId] has a figure of the current version for the mode (what a walk leaves out of its set). */
    fun isCurrent(formId: String, normalized: Boolean = false): Boolean = current(formId, normalized) != null

    /** Stores a figure in the shelf its own [SleepScore.normalized] names: the mode that made it owns it. */
    @Synchronized
    fun put(formId: String, score: SleepScore) {
        shelf(score.normalized).put(formId, score)
    }

    @Synchronized
    fun forget(formId: String, normalized: Boolean = false) {
        shelf(normalized).forget(formId)
    }

    /** Drops both modes' figures (only a scoring-version change or the listener's own reset does this). */
    @Synchronized
    fun clear() {
        plain.clear()
        normalized.clear()
    }

    /** Drops the normalized figures: their gain is the Normalize target's, so a new target makes them wrong. */
    @Synchronized
    fun clearNormalized() {
        normalized.clear()
    }

    /** Every recording with a figure of the mode, whatever its version. */
    fun ids(normalized: Boolean = false): Set<String> = shelf(normalized).ids()

    /** The figures of both modes, with their features, for fitting the model to listener labels. */
    fun all(): Map<String, SleepScore> = plain.all() + normalized.all()

    /** Reads both files once, off the playback thread (the settings page and the filter ask for them later). */
    fun warm() {
        plain.ids()
        normalized.ids()
    }
}
