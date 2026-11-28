package com.prism.music.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The listener's own verdict on a score while it's being tuned: too high, or too low. */
enum class ScoreMark { HIGH, LOW }

/**
 * Songs whose sleep score looks wrong to the listener, marked from the Sleep scores list ("High" / "Low") while the
 * weights are being tuned. **Debug only**: it changes nothing about the score, the sleep timer, radio or anything else —
 * it is a column and a flag in "Export sleep scores", so a batch of wrong scores can be collected, shared and read.
 * Kept on this phone, like a note in the margin.
 */
class SleepScoreMarks(context: Context) {
    private val prefs = context.getSharedPreferences("sleep_score_marks", Context.MODE_PRIVATE)
    private val _marks = MutableStateFlow(read())
    val marks: StateFlow<Map<String, ScoreMark>> = _marks

    fun mark(songId: String, mark: ScoreMark?) {
        prefs.edit().apply {
            if (mark == null) remove(songId) else putString(songId, mark.name)
        }.apply()
        _marks.value = read()
    }

    /** Marks the song [mark], or takes the mark off when it's already that one. */
    fun toggle(songId: String, mark: ScoreMark) {
        mark(songId, if (_marks.value[songId] == mark) null else mark)
    }

    fun of(songId: String): ScoreMark? = _marks.value[songId]

    fun clear() {
        prefs.edit().clear().apply()
        _marks.value = emptyMap()
    }

    private fun read(): Map<String, ScoreMark> = prefs.all.mapNotNull { (id, v) ->
        (v as? String)?.let { name -> runCatching { ScoreMark.valueOf(name) }.getOrNull()?.let { id to it } }
    }.toMap()
}
