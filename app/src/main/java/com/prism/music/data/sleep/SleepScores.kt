package com.prism.music.data.sleep

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Songs' sleep suitability figures (1.9), kept on this phone by the recording they describe
 * ([com.prism.music.playback.Loudness.formId]): a music video, a picked version or a file plays audio of its own, and
 * only what actually plays can be judged. The stored shape is [SleepScore]'s JSON, so the components, the confidence and
 * the warnings travel with the figure and the UI can explain it.
 *
 * A figure from an older [SleepAnalysis.MODEL_VERSION] is not used: [current] returns null for it, so the sleep timer's
 * filter passes the song over (never assuming a stale figure still applies) and the next calculation rewrites it. That
 * is the whole cache rule: a recording's audio is identified by its form id, and its figure is reused until the scoring
 * version changes or it is calculated again.
 *
 * Kept in SharedPreferences (like the scans' own figures): the lookups are per song while the timer runs, and a
 * library's worth of JSON is a few hundred kilobytes. Reads and writes are safe from the playback thread.
 */
class SleepScores(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sleep_scores", Context.MODE_PRIVATE)

    /** What's on the phone, read once: a queue's worth of lookups (the timer's filter, tens of times a second) never
     *  touches the preference file again, and a write keeps it in step. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, SleepScore>()
    /** Recordings looked for and found to have nothing: an unscored song isn't re-read from the file every half second. */
    private val absent = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var loaded = false

    private val _calculated = MutableStateFlow(calculatedCount())
    /** How many recordings have a figure of the current version: the settings page says how much of a library is scored. */
    val calculated: StateFlow<Int> = _calculated

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

    /** The recording's figure, whatever version made it (null when it has none): for showing what was measured. */
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

    /** The recording's figure when it's from the current scoring version; null for a missing or an outdated one. */
    fun current(formId: String): SleepScore? = stored(formId)?.takeIf { it.modelVersion == SleepAnalysis.MODEL_VERSION }

    /** The figure's score (0–100) when it's current, else null: what the sleep timer's filter asks for. */
    fun score(formId: String): Int? = current(formId)?.score

    /** Whether [formId] has been scored with the current version (what a walk leaves out of `MeasureSongs.pending`). */
    fun isCurrent(formId: String): Boolean = current(formId) != null

    @Synchronized
    fun put(formId: String, score: SleepScore) {
        prefs.edit().putString(formId, SleepScore.encode(score)).apply()
        absent.remove(formId)
        cache[formId] = score
        loaded = true
        _calculated.value = calculatedCount()
    }

    @Synchronized
    fun forget(formId: String) {
        prefs.edit().remove(formId).apply()
        cache.remove(formId)
        absent += formId
        _calculated.value = calculatedCount()
    }

    /** Drops every figure (only a scoring-version change or the listener's own reset does this). */
    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
        cache.clear()
        absent.clear()
        loaded = true
        _calculated.value = 0
    }

    /** Every recording with a figure of any version (the settings page counts them, and a reset would drop them). */
    fun ids(): Set<String> {
        load()
        return cache.keys.toSet()
    }

    private fun calculatedCount(): Int {
        load()
        return cache.values.count { it.modelVersion == SleepAnalysis.MODEL_VERSION }
    }
}
