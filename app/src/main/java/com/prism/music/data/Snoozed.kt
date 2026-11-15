package com.prism.music.data

import android.content.Context
import android.content.SharedPreferences
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * A track kept out of Prism's automatic queues until it wakes (Snooze). The song is kept with the record, so the list
 * still names it after it has left the library or before its page is loaded.
 *
 * Snoozing a track never stops the listener playing it: a queue the listener starts, Add to queue and Play next all
 * keep it. Only what Prism picks by itself (autoplay, a radio, the songs behind a list) leaves it out.
 */
@Serializable
data class SnoozedTrack(
    val song: Song,
    /** Epoch milliseconds it wakes (from then on, automatic queues may pick it again). */
    val until: Long,
    /** When it was snoozed last: the list's order, newest first. */
    val snoozedAt: Long,
)

/**
 * How long a snooze lasts (1.8.5): the default the button and Select many use, and the numbers the management list edits.
 *
 * A snooze's days are kept as the time it wakes, so editing the number moves its expiry; [daysLeft] reads it back,
 * rounded up, so a snooze just made for 30 days shows 30 until its last day.
 */
object SnoozeDuration {
    /** Days a snooze lasts unless the listener says otherwise (Settings → Player → "Snooze for"). */
    const val DEFAULT_DAYS = 30

    /** The most days a snooze can last (ten years; a typo shouldn't keep a song away for ever). */
    const val MAX_DAYS = 3650

    /** What a valid number of days is. */
    val RANGE = 1..MAX_DAYS

    const val DAY_MS = 86_400_000L

    /** When a snooze of [days] made at [now] wakes. */
    fun until(days: Int, now: Long): Long = now + days.coerceIn(RANGE) * DAY_MS

    /** The days left of a snooze that wakes at [until], rounded up; 0 once it has woken. */
    fun daysLeft(until: Long, now: Long): Int {
        val left = until - now
        return if (left <= 0) 0 else ((left + DAY_MS - 1) / DAY_MS).toInt()
    }

    /** [text] as a number of days, or null when it isn't a whole number in [RANGE] ("Invalid number"). */
    fun parse(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in RANGE }
}

/**
 * The snoozed tracks (1.8.5), kept on this phone by song id: a track, when it wakes, and what it shows as.
 * Kept in SharedPreferences, read in at start like [SongVersions]' picks, and shown in Compose at once.
 *
 * A track already snoozed isn't recorded twice: snoozing it again only moves its expiry (Select many on a list that
 * holds some already, and the player's button, stay consistent).
 */
class SnoozeStore(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val prefs: SharedPreferences = context.getSharedPreferences("snoozed_tracks", Context.MODE_PRIVATE)

    private val _tracks = MutableStateFlow(read())

    /** Every record, expired ones until [prune] (reads go through [isSnoozed], which checks the time). */
    val tracks: StateFlow<Map<String, SnoozedTrack>> = _tracks

    /** How many days a snooze lasts unless its list says otherwise (Settings → Player → the gear → "Snooze for"). */
    private val _defaultDays = MutableStateFlow(readDefaultDays())
    val defaultDays: StateFlow<Int> = _defaultDays

    init { prune() }

    /** The days the player's button and Select many give a snooze. */
    @Synchronized
    fun setDefaultDays(days: Int) {
        val d = days.coerceIn(SnoozeDuration.RANGE)
        prefs.edit().putInt(KEY_DEFAULT_DAYS, d).apply()
        _defaultDays.value = d
    }

    /** The snoozes still running, newest first (the management list's order). */
    fun running(at: Long = now()): List<SnoozedTrack> =
        _tracks.value.values.filter { it.until > at }.sortedByDescending { it.snoozedAt }

    /** Whether [song] is snoozed now (a record whose days are up no longer counts). */
    fun isSnoozed(song: Song, at: Long = now()): Boolean = isSnoozed(song.id, at)

    fun isSnoozed(songId: String, at: Long = now()): Boolean = (_tracks.value[songId]?.until ?: 0L) > at

    /** The day a snoozed track wakes, or 0. */
    fun until(songId: String): Long = _tracks.value[songId]?.until ?: 0L

    /**
     * [songs] without the snoozed ones: what may be added to a queue by itself. [keep] is the track the queue starts
     * on (a tap, Play next, Add to queue): the listener asked for it, so it stays even when snoozed.
     */
    fun allowed(songs: List<Song>, keep: Song? = null, at: Long = now()): List<Song> {
        if (_tracks.value.isEmpty()) return songs
        return songs.filter { (keep != null && it.id == keep.id) || !isSnoozed(it.id, at) }
    }

    /**
     * Snoozes [songs] for [days] from now. One record per song (a second snooze of the same track only moves its
     * expiry). Returns how many the operation covered.
     */
    @Synchronized
    fun snooze(songs: Collection<Song>, days: Int = _defaultDays.value, at: Long = now()): Int {
        prune(at)
        val wake = SnoozeDuration.until(days, at)
        val ed = prefs.edit()
        val next = _tracks.value.toMutableMap()
        var covered = 0
        for (song in songs.distinctBy { it.id }) {
            next[song.id] = SnoozedTrack(song, wake, at)
            ed.putString(song.id, encode(next.getValue(song.id)))
            covered++
        }
        ed.apply()
        _tracks.value = next
        return covered
    }

    /** Lets [songId] be picked by automatic queues again, at once. */
    @Synchronized
    fun unsnooze(songId: String) {
        if (_tracks.value[songId] == null) return
        prefs.edit().remove(songId).apply()
        _tracks.value = _tracks.value - songId
    }

    /** Snoozes [song] for [days], or lets it back in when it is snoozed already (the player's button). */
    @Synchronized
    fun toggle(song: Song, days: Int = _defaultDays.value, at: Long = now()) {
        if (isSnoozed(song.id, at)) unsnooze(song.id) else snooze(listOf(song), days, at)
    }

    /** Moves a snoozed track's expiry to [days] from now (the management list's number). */
    @Synchronized
    fun setDays(songId: String, days: Int, at: Long = now()) {
        val record = _tracks.value[songId] ?: return
        val next = record.copy(until = SnoozeDuration.until(days, at))
        prefs.edit().putString(songId, encode(next)).apply()
        _tracks.value = _tracks.value + (songId to next)
    }

    /** Forgets the snoozes whose days are up: they no longer hold anything back, so nothing needs keeping. */
    @Synchronized
    fun prune(at: Long = now()) {
        val expired = _tracks.value.filterValues { it.until <= at }.keys
        if (expired.isEmpty()) return
        val ed = prefs.edit()
        expired.forEach { ed.remove(it) }
        ed.apply()
        _tracks.value = _tracks.value - expired
    }

    private fun readDefaultDays(): Int =
        prefs.getInt(KEY_DEFAULT_DAYS, SnoozeDuration.DEFAULT_DAYS).coerceIn(SnoozeDuration.RANGE)

    private fun encode(t: SnoozedTrack): String =
        listOf(t.until.toString(), t.snoozedAt.toString(), InnerTube.json.encodeToString(Song.serializer(), t.song)).joinToString("\t")

    private fun read(): Map<String, SnoozedTrack> = prefs.all.mapNotNull { (id, value) ->
        val parts = (value as? String)?.split('\t', limit = 3) ?: return@mapNotNull null
        val until = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val snoozedAt = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        val song = parts.getOrNull(2)?.let {
            runCatching { InnerTube.json.decodeFromString(Song.serializer(), it) }.getOrNull()
        } ?: return@mapNotNull null
        id to SnoozedTrack(if (song.id == id) song else song.copy(id = id), until, snoozedAt)
    }.toMap()

    private companion object {
        /** Kept beside the records; a song's id is never this. */
        const val KEY_DEFAULT_DAYS = "default_days"
    }
}
