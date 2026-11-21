package com.prism.music.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the listener chooses in the sleep timer's menu, kept on this phone so it's the same next time and after a
 * restart. These are choices, not the running session: a cancelled timer leaves them as they were, and the temporary
 * "allow every BPM" override is never written here (it lasts as long as one session).
 *
 * The fade itself is the older Settings → Playback row ([SettingsRepository.setSleepFade]), still the same setting the
 * timer reads, so there's one fade preference rather than two.
 */
data class SleepTimerChoices(
    /** The countdown the menu starts with, in minutes. */
    val minutes: Int = 30,
    /** The fastest a song may be while the timer runs; 0 is no limit. */
    val maxBpm: Int = 0,
    /** Whether playback finishes the song playing at the deadline instead of stopping at it. */
    val finishSong: Boolean = false,
    /** The sleep timer volume, as the slider's place (0 … 1); NormalizerProcessor.volumeFor's curve is applied to it. */
    val volume: Float = 1f,
    /** A track to play once the timer has finished (null: nothing; the music just stops). */
    val afterSong: com.prism.music.data.model.Song? = null,
    /** How long the after-timer track fades in over, in seconds; 0 starts it at full volume. */
    val fadeInSeconds: Int = 0,
    /** Whether the after-timer track starts over when it ends. */
    val loopAfter: Boolean = false,
)

/** Stores [SleepTimerChoices] in SharedPreferences, read at start and shown in Compose at once (like SnoozeStore). */
class SleepTimerPrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sleep_timer", Context.MODE_PRIVATE)
    private val _choices = MutableStateFlow(read())
    val choices: StateFlow<SleepTimerChoices> = _choices

    private fun read() = SleepTimerChoices(
        minutes = (prefs.getInt(KEY_MINUTES, 30)).coerceIn(MIN_MINUTES, MAX_MINUTES),
        maxBpm = (prefs.getInt(KEY_MAX_BPM, 0)).coerceIn(0, MAX_BPM_LIMIT),
        finishSong = prefs.getBoolean(KEY_FINISH_SONG, false),
        volume = prefs.getFloat(KEY_VOLUME, 1f).coerceIn(0f, 1f),
        afterSong = prefs.getString(KEY_AFTER_SONG, null)?.let {
            runCatching { InnerTube.json.decodeFromString(Song.serializer(), it) }.getOrNull()
        },
        fadeInSeconds = (prefs.getInt(KEY_FADE_IN, 0)).coerceIn(0, MAX_FADE_IN_SECONDS),
        loopAfter = prefs.getBoolean(KEY_LOOP_AFTER, false),
    )

    @Synchronized
    fun setMinutes(minutes: Int) = put { putInt(KEY_MINUTES, minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)) }

    @Synchronized
    fun setMaxBpm(maxBpm: Int) = put { putInt(KEY_MAX_BPM, maxBpm.coerceIn(0, MAX_BPM_LIMIT)) }

    @Synchronized
    fun setFinishSong(on: Boolean) = put { putBoolean(KEY_FINISH_SONG, on) }

    @Synchronized
    fun setVolume(volume: Float) = put { putFloat(KEY_VOLUME, volume.coerceIn(0f, 1f)) }

    @Synchronized
    fun setAfterSong(song: Song?) = put {
        if (song == null) remove(KEY_AFTER_SONG)
        else putString(KEY_AFTER_SONG, InnerTube.json.encodeToString(Song.serializer(), song))
    }

    @Synchronized
    fun setFadeInSeconds(seconds: Int) = put { putInt(KEY_FADE_IN, seconds.coerceIn(0, MAX_FADE_IN_SECONDS)) }

    @Synchronized
    fun setLoopAfter(on: Boolean) = put { putBoolean(KEY_LOOP_AFTER, on) }

    private fun put(block: SharedPreferences.Editor.() -> SharedPreferences.Editor) {
        prefs.edit().block().apply()
        _choices.value = read()
    }

    companion object {
        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 240
        /** The BPM slider's top; 0 is off. */
        const val MAX_BPM_LIMIT = 220
        /** The fade-in slider's top, the same as the fade-out's. */
        const val MAX_FADE_IN_SECONDS = 60

        /** A typed number of minutes, kept inside the menu's range; null when it isn't a whole number. */
        fun parseMinutes(text: String): Int? = text.trim().toIntOrNull()?.coerceIn(MIN_MINUTES, MAX_MINUTES)

        private const val KEY_MINUTES = "minutes"
        private const val KEY_MAX_BPM = "max_bpm"
        private const val KEY_FINISH_SONG = "finish_song"
        private const val KEY_VOLUME = "volume"
        private const val KEY_AFTER_SONG = "after_song"
        private const val KEY_FADE_IN = "fade_in"
        private const val KEY_LOOP_AFTER = "loop_after"
    }
}
