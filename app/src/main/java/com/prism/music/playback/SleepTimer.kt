package com.prism.music.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.pow

/**
 * The sleep timer (A5, extended for the full timer): the countdown, the fade and the BPM gate of one bedtime session.
 *
 * The session is threaded together by [SleepTimerEngine], which is told what the player is doing through [SleepAudio] and
 * works everything out from the (monotonic) clock: the time left, when the current song really ends when "finish current
 * song" is on, and how loud the fade should be at this instant. Nothing is counted down by UI refreshes and no volume is
 * stepped down a little at a time: every tick reads the clock and works the gain out from it, so a late tick (a busy main
 * thread, a paused app) lands on the right volume rather than behind it.
 *
 * What the listener set is kept apart from this: the choices (time, fade, BPM limit, finish current song, volume) live in
 * the sleep timer's own preferences (SleepTimerPrefs and Settings' fade); only the running session's deadline, overtime and
 * temporary BPM override live here.
 */
object SleepFade {
    /** The ease-in's exponent: the first part of the fade barely moves, the last part does most of the work. */
    const val EXPONENT = 2.5

    /** The last part of the fade, where a smoothstep brings the gain to exactly zero. */
    const val TAPER = 0.15f

    /**
     * The fade's gain at [progress] (0: the fade starts, 1: the music stops). A power ease-in (exponent [EXPONENT]),
     * multiplied by a smoothstep over the last [TAPER] so the very end tapers instead of cutting to zero; it is exactly
     * 0 at 1 and exactly 1 at 0, and never goes back up.
     */
    fun gain(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        if (p >= 1f) return 0f
        val power = 1f - p.toDouble().pow(EXPONENT).toFloat()
        val left = (1f - p) / TAPER
        if (left >= 1f) return power.coerceIn(0f, 1f)
        val t = left.coerceIn(0f, 1f)
        return (power * (t * t * (3f - 2f * t))).coerceIn(0f, 1f)
    }
}

/** What a running timer can ask of playback. Kept small so the timer's maths can be tested without a player. */
interface SleepAudio {
    /** The item playing now: its queue index and the song's id, so a skip is told from a seek in the same song. */
    val itemKey: String?
    val isPlaying: Boolean
    val positionMs: Long
    val durationMs: Long
    /** The playback speed (the song's remaining *media* time is shorter than its remaining wall time at 1.5×). */
    val speed: Float

    fun pause()

    /** Pauses at the end of each item; the timer sets it only for overtime, where that end is the stopping point. */
    fun setPauseAtEnd(on: Boolean)

    /** The fade's gain (0 … 1), on top of the session's own volume. */
    fun setFade(gain: Float)

    /** The volume the listener picked for the session, as the slider's place (0 … 1). */
    fun setSessionVolume(volume: Float)

    /** Puts playback back to the listener's own volume (the session is over or was cancelled). */
    fun restoreVolume()

    fun reportNoEligibleSongs(maxBpm: Int)
}

/** One session's choices, as they were when it started (later edits happen through the active timer menu). */
data class SleepConfig(
    val minutes: Int,
    val fadeSeconds: Int,
    val maxBpm: Int,
    val finishSong: Boolean,
    /** The volume slider's place, 0 … 1. */
    val volume: Float,
)

/**
 * A session's clock and stop time. [SleepTimerEngine.stopAt] is where real playback stops:
 * - without "finish current song" that's the deadline;
 * - with it, the end of the song playing when the deadline arrives, when that is later (overtime), or the deadline when
 *   the song ends first. A manually picked next song, or the end of that song, ends the session at once.
 */
object SleepClock {
    /**
     * The fade a session really gets: [fadeSeconds], strictly shorter than the whole [minutes] (at least a millisecond),
     * so the volume is always on its way to zero while the timer is still running.
     */
    fun fadeMs(minutes: Int, fadeSeconds: Int): Long {
        val total = minutes.coerceAtLeast(0) * 60_000L
        return (fadeSeconds.coerceAtLeast(0) * 1_000L).coerceIn(0L, (total - 1).coerceAtLeast(0L))
    }

    /** Where the current song ends, from its position and duration at the player's speed; null when it isn't known. */
    fun songEndAt(now: Long, positionMs: Long, durationMs: Long, speed: Float): Long? {
        if (durationMs <= 0) return null
        val left = (durationMs - positionMs).coerceAtLeast(0L)
        val s = speed.takeIf { it.isFinite() && it > 0f } ?: 1f
        return now + (left / s).toLong()
    }
}

/** The BPM rule itself: a song is allowed when its measured tempo is known, sane and no faster than [maxBpm]. */
object SleepBpm {
    /** [maxBpm] 0 (or less) means no limit: everything is allowed. */
    fun allows(bpm: Double?, maxBpm: Int): Boolean {
        if (maxBpm <= 0) return true
        // An unknown or impossible reading is never let through: the limit is the limit.
        if (bpm == null || !bpm.isFinite() || bpm <= 0.0) return false
        return bpm <= maxBpm
    }
}

/**
 * The running sleep timer. [SleepTimerPrefs] holds what the listener chose; this holds the one session that is running
 * now: its deadline, overtime, temporary BPM override and the fade being applied.
 */
class SleepTimerEngine(
    private val audio: SleepAudio,
    private val bpmOf: (String) -> Double?,
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** The deadline as a wall-clock time, for the countdown the UI shows (0 when no timer runs). */
    private val _wallDeadline = MutableStateFlow(0L)
    val wallDeadline: StateFlow<Long> = _wallDeadline.asStateFlow()

    /** "Finish current song" for this session: the UI says "Song end" while the deadline waits on the song. */
    private val _finishSong = MutableStateFlow(false)
    val finishSong: StateFlow<Boolean> = _finishSong.asStateFlow()

    /** The temporary "allow every BPM" override of the active menu. Reset when the session ends. */
    private val _bpmDisabled = MutableStateFlow(false)
    val bpmDisabled: StateFlow<Boolean> = _bpmDisabled.asStateFlow()

    /** The limit this session was started with (0 when none). */
    private val _maxBpm = MutableStateFlow(0)
    val maxBpm: StateFlow<Int> = _maxBpm.asStateFlow()

    private var deadline = 0L
    private var fadeMs = 0L

    /** Snapshot of the running session: read from the playback thread too (the BPM gate asks from autoplay's work). */
    @Volatile private var config = SleepConfig(0, 0, 0, false, 1f)
    private var overtime = false
    private var overtimeKey: String? = null

    /** The deadline the session is heading for right now (0 when it isn't running). */
    val deadlineElapsed: Long get() = if (_active.value) deadline else 0L

    /**
     * Starts one session, replacing any other: there is only ever one. [config]'s fade is cut to strictly less than the
     * countdown (see [SleepClock.fadeMs]) and its volume is applied at once.
     */
    fun start(config: SleepConfig) {
        cancel()
        if (config.minutes <= 0) return
        this.config = config.copy(
            fadeSeconds = (config.fadeSeconds.coerceAtLeast(0)),
            maxBpm = config.maxBpm.coerceAtLeast(0),
            volume = config.volume.coerceIn(0f, 1f),
        )
        fadeMs = SleepClock.fadeMs(this.config.minutes, this.config.fadeSeconds)
        deadline = clock() + this.config.minutes * 60_000L
        overtime = false
        overtimeKey = null
        _maxBpm.value = this.config.maxBpm
        _finishSong.value = this.config.finishSong
        _bpmDisabled.value = false
        _wallDeadline.value = wallClock() + this.config.minutes * 60_000L
        _active.value = true
        audio.setSessionVolume(this.config.volume)
        audio.setFade(1f)
        audio.setPauseAtEnd(false)
        tick()
    }

    /** Ends the session now (or cancels it), putting the volume back. Safe to call when nothing runs. */
    fun cancel() {
        _active.value = false
        _wallDeadline.value = 0L
        _finishSong.value = false
        _bpmDisabled.value = false
        _maxBpm.value = 0
        overtime = false
        overtimeKey = null
        deadline = 0L
        audio.setPauseAtEnd(false)
        audio.restoreVolume()
    }

    /**
     * -5 / +5 minutes on the running timer: the deadline moves at once, the remaining time never goes below zero, and a
     * new stop and fade schedule is worked out on the next tick (overtime starts over from the current song).
     */
    fun adjust(minutes: Int) {
        if (!_active.value || minutes == 0) return
        val now = clock()
        val left = (deadline - now).coerceAtLeast(0L)
        val moved = left + minutes * 60_000L
        val remaining = moved.coerceAtLeast(0L)
        deadline = now + remaining
        // The fade is worked out again for the new countdown: never longer than what's left (strictly shorter), and back
        // to the set duration when +5 gives it room again.
        fadeMs = (config.fadeSeconds * 1_000L).coerceIn(0L, (remaining - 1).coerceAtLeast(0L))
        overtime = false
        overtimeKey = null
        audio.setPauseAtEnd(false)
        _wallDeadline.value = wallClock() + remaining
        tick()
    }

    /** The active menu's temporary override. The configured limit itself is untouched (it lives in the preferences). */
    fun setBpmDisabled(disabled: Boolean) {
        if (!_active.value) return
        _bpmDisabled.value = disabled
    }

    /** Whether the session's BPM limit rules [songId] out right now (the override and a missing limit allow everything). */
    fun blocksBpm(songId: String?): Boolean {
        if (!_active.value || _bpmDisabled.value || config.maxBpm <= 0) return false
        return !SleepBpm.allows(songId?.let(bpmOf), config.maxBpm)
    }

    /**
     * A new item started. In overtime ("finish current song" after the deadline) that means the song the timer was
     * finishing is over: the session stops there. Otherwise the next tick works the stop time out from the new song.
     */
    fun onItemChanged() {
        if (!_active.value) return
        // The deadline is behind us and another item has started: the song the timer was finishing is over (or was
        // skipped), so this is where playback stops, even if a late tick hadn't marked the overtime yet.
        if (config.finishSong && clock() >= deadline) {
            end()
            return
        }
        if (overtime) {
            val key = audio.itemKey
            if (key != overtimeKey) {
                end()
                return
            }
        }
        tick()
    }

    /** Play/pause and seeks change when the current song ends: work the stop time out again at once. */
    fun onPlaybackChanged() {
        if (_active.value) tick()
    }

    /** Where playback will stop, as an elapsed-time instant. */
    fun stopAt(now: Long = clock()): Long {
        if (!_active.value) return now
        if (!config.finishSong) return deadline
        // Nothing plays: a paused timer has nothing to let the song finish; past the deadline it's over.
        if (!audio.isPlaying) return if (deadline <= now) now else deadline
        val end = SleepClock.songEndAt(now, audio.positionMs, audio.durationMs, audio.speed)
        if (!overtime) {
            // The song playing when the deadline arrives may end after it: that end is the real stopping point.
            return if (end == null) deadline else maxOf(deadline, end)
        }
        // Overtime: only the song that was playing at the deadline may run on; a different one means it has ended.
        if (audio.itemKey != overtimeKey) return now
        return end ?: now
    }

    /**
     * Works out the fade and the stop for [now], applies the gain, and says how long the caller may sleep before looking
     * again (short during the fade, so the volume is smooth; a longer, cheap tick otherwise).
     */
    fun tick(now: Long = clock()): Long {
        if (!_active.value) return 0L
        if (config.finishSong && now >= deadline && !overtime && audio.isPlaying) {
            // The deadline has come and the song is still playing: its end is now the stopping point.
            if (audio.durationMs > 0) {
                overtime = true
                overtimeKey = audio.itemKey
            }
        }
        val stop = stopAt(now)
        val fadeStart = stop - fadeMs
        val gain = when {
            fadeMs <= 0L -> if (now >= stop) 0f else 1f
            else -> SleepFade.gain(((now - fadeStart).toDouble() / fadeMs).toFloat())
        }
        audio.setFade(gain)
        audio.setPauseAtEnd(overtime && audio.isPlaying && audio.itemKey == overtimeKey)
        if (now >= stop) {
            end()
            return 0L
        }
        val left = stop - now
        return if (fadeMs > 0 && left <= fadeMs) 40L else minOf(500L, left).coerceAtLeast(20L)
    }

    /** Pauses where the schedule says and clears the session; the caller's tail restores the volume after the buffer. */
    private fun end() {
        if (!_active.value) return
        audio.setFade(0f)
        audio.setPauseAtEnd(false)
        audio.pause()
        _active.value = false
        _wallDeadline.value = 0L
        _finishSong.value = false
        _bpmDisabled.value = false
        _maxBpm.value = 0
        overtime = false
        overtimeKey = null
        deadline = 0L
    }
}
