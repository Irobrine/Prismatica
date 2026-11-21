package com.prism.music.data.sleep

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** A listener's own judgment of a song, used to fit the scoring model to their ears. */
@Serializable
enum class SleepLabel {
    SUITABLE, BORDERLINE, UNSUITABLE;

    /** The target for fitting: soft ends, so one odd song can't saturate the logistic. */
    val target: Double get() = when (this) {
        SUITABLE -> 1.0
        BORDERLINE -> 0.55
        UNSUITABLE -> 0.05
    }
}

/** Judgments kept by song id (the song the listener sees, not the recording's form id). */
class SleepLabels(context: Context) {
    private val prefs = context.getSharedPreferences("sleep_score_labels", Context.MODE_PRIVATE)
    private val _labels = MutableStateFlow(read())
    val labels: StateFlow<Map<String, SleepLabel>> = _labels

    @Volatile private var loaded = false

    private fun read(): Map<String, SleepLabel> {
        loaded = true
        return prefs.all.mapNotNull { (id, value) ->
            val text = value as? String ?: return@mapNotNull null
            runCatching { SleepLabel.valueOf(text) }.getOrNull()?.let { id to it }
        }.toMap()
    }

    fun label(songId: String): SleepLabel? {
        if (!loaded) _labels.value = read()
        return _labels.value[songId]
    }

    @Synchronized
    fun set(songId: String, label: SleepLabel?) {
        if (label == null) prefs.edit().remove(songId).apply()
        else prefs.edit().putString(songId, label.name).apply()
        _labels.value = read()
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
        _labels.value = emptyMap()
    }

    /** Enough spread to fit without overfitting one person's taste: at least two at each end. */
    fun enoughToFit(): Boolean {
        val values = _labels.value.values
        return values.count { it == SleepLabel.SUITABLE } >= 2 && values.count { it == SleepLabel.UNSUITABLE } >= 2 && values.size >= 8
    }
}
