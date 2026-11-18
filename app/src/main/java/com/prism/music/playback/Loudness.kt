package com.prism.music.playback

import android.content.SharedPreferences
import com.prism.music.data.model.Song
import kotlin.math.abs

/**
 * What a song's sound is, and how loud it is: the one place that knows which id names the recording actually heard (its
 * *form*) and which loudness figure applies to it (1.1.3).
 *
 * A song can be heard as several recordings: its own, a music video of it (watched, or playing in its place when YouTube
 * won't play the song, #93), a version the listener picked to hear instead (#328, [com.prism.music.data.SongVersions]),
 * or a file on the phone picked for it. Each recording is mastered differently (a video's audio, a cover, a remaster),
 * and before 1.1.3 every figure was filed under the song's id whatever was heard: a picked version's measurement
 * described whichever recording was picked when it was scanned, a video's audio played at its song's level, and a
 * soundFrom play at no level at all. Every figure — measured by the scan ([MixTempo]) and YouTube's — is now keyed by
 * [formId], and no other file reads or writes one.
 *
 * Up to two figures describe a form: its measured integrated loudness and peak ([measured], [peak], from [MixTempo]'s
 * scan of its own bytes) and YouTube's `loudnessDb` ([youtubeDb]). A measured figure wins: YouTube's hardly differs
 * between a loud master and a quiet one (#210).
 */
class Loudness(
    /** YouTube's figure per form heard ([formId]); the pref file is `loudness`, as it always was (a backup keeps it). */
    private val prefs: SharedPreferences,
    /** [MixTempo.loudness]: a form's measured integrated loudness (LUFS), once it's been scanned. */
    private val measuredOf: (id: String) -> Float?,
    /** [MixTempo.peak]: a form's loudest sample (dBFS), measured with its loudness (1.8.0). */
    private val peakOf: (id: String) -> Float?,
    /** [MixTempo.loudnessCurrent]: whether what's measured of the form is from the current measurement (1.8.5). */
    private val measuredCurrentOf: (id: String) -> Boolean,
    /**
     * [com.prism.music.data.SongVersions.of]: the recording picked for a song — a version to stream, or a file on the
     * phone ([Song.LOCAL_PREFIX]; never streamed, but its sound is what plays, so it is the form). Not [SongVersions.streamedOf],
     * which leaves a file-picked song looking like the song itself: the file's sound would then play at the song's own
     * figures, and the song's at the file's.
     */
    private val pickedOf: (songId: String) -> String?,
    /** [com.prism.music.AppContainer.soundFrom]: the music video whose sound plays in a song's place (#93). */
    private val soundFrom: Map<String, String>,
) {
    /**
     * The id of the audio actually heard when [songId] plays with [videoId] armed (its music video, as the item carries it:
     * [MediaItem.videoModeId]): exactly what playback resolves ([com.prism.music.AppContainer.resolveAudio] and
     * [com.prism.music.AppContainer.songCacheKey]): the video, else the video whose sound plays in its place, else the
     * recording picked for it — a version, or a file on the phone — else the song itself.
     *
     * A video wins over a pick: watching a song's video plays the video's own sound (its audio leg is the video's), not
     * the version picked for the song.
     */
    fun formId(songId: String, videoId: String? = null): String =
        videoId ?: soundFrom[songId] ?: pickedOf(songId) ?: songId

    /** Whether [id] names one of the phone's own files ([Song.LOCAL_PREFIX]): measured from the file, never asked of YouTube. */
    fun isLocal(id: String): Boolean = id.startsWith(Song.LOCAL_PREFIX)

    /**
     * Where a [Figure] came from, worst first, so that a later figure from a better one may take over ([LoudnessCatchUp]):
     * a measured figure describes the form heard; YouTube's stands in until it's measured; a fallback is another
     * recording's (the song's own, while its video just started).
     */
    enum class Source {
        NONE, YOUTUBE_FALLBACK, YOUTUBE, MEASURED_FALLBACK, MEASURED;

        /** Whether a figure from this source says more than one from [other] (a later, better figure may take over). */
        fun improves(on: Source): Boolean = ordinal > on.ordinal

        /** How the figure is named in the diagnostics and the PrismNormalize log. */
        val label: String get() = when (this) {
            NONE -> "none"
            YOUTUBE -> "YouTube"
            YOUTUBE_FALLBACK -> "YouTube (the song's)"
            MEASURED -> "measured"
            MEASURED_FALLBACK -> "measured (the song's, video just switched)"
        }
    }

    /**
     * The figure for a form: what it's played at, and where that came from.
     *
     * @property gainDb the gain (dB) to play the form at ([NormalizerProcessor.gainFor]: loud ones lowered, quiet ones
     *   lifted as far as their peaks allow).
     * @property source where it came from; [Source.NONE] is 0 dB, played as it is.
     * @property level the figure itself, for the stats overlay: LUFS when measured, YouTube's dB when not, null for none.
     */
    data class Figure(val gainDb: Float, val source: Source, val level: Float? = null)

    /**
     * The figure that applies to the form heard when [songId] plays with [videoId] armed, at [target] LUFS (the setting's
     * "Loudness target"), best first: the form's measured loudness, its YouTube figure, else nothing.
     *
     * A video of the song may start on the song's own figures until its own are in: it's the same recording's other
     * master, measured by the same scan, and it was just switched to, so its figure is on its way ([LoudnessCatchUp] puts
     * it on). A *picked version* or a soundFrom video gets none of that: it's another recording (a cover, a live take, a
     * video with its own intro), so the song's figure would be a guess at a different sound — 0 dB until its own figure
     * lands is honest, and the fetch and scan make that quick.
     */
    fun figure(songId: String, videoId: String?, target: Float): Figure {
        val form = formId(songId, videoId)
        // A video armed for the song: the only form that may fall back to the song's figures (not a pick, not soundFrom).
        val videoForm = videoId != null && videoId != songId
        measuredFigure(form, target, Source.MEASURED)?.let { return it }
        if (videoForm) measuredFigure(songId, target, Source.MEASURED_FALLBACK)?.let { return it }
        youtubeFigure(form, target, Source.YOUTUBE)?.let { return it }
        if (videoForm) youtubeFigure(songId, target, Source.YOUTUBE_FALLBACK)?.let { return it }
        return Figure(0f, Source.NONE)
    }

    /**
     * [id]'s measured figure, if what's measured of it is from the current [MixTempo.LUFS_VERSION]. An older measurement
     * may describe another recording — before 1.1.3 it was filed under the song's id whichever recording was scanned
     * then — so it isn't trusted; the scan measures the form again ([MixTempo.measure]) and [LoudnessCatchUp] puts the
     * figure on when it lands.
     */
    private fun measuredFigure(id: String, target: Float, source: Source): Figure? {
        if (!measuredCurrentOf(id)) return null
        val lufs = measuredOf(id) ?: return null
        return Figure(NormalizerProcessor.gainFor(target, lufs, peakOf(id)), source, lufs)
    }

    /** [id]'s YouTube figure as a gain: its `loudnessDb` is relative to [YOUTUBE_REFERENCE_LUFS], with no peak to go by. */
    private fun youtubeFigure(id: String, target: Float, source: Source): Figure? {
        val db = youtubeDb(id) ?: return null
        return Figure(NormalizerProcessor.gainFor(target, YOUTUBE_REFERENCE_LUFS + db), source, db)
    }

    /** YouTube's loudness figure for [id] (dB from its reference level), or null when it hasn't been asked for. */
    fun youtubeDb(id: String): Float? = prefs.getFloat(id, Float.NaN).takeIf { !it.isNaN() }

    /** Whether YouTube's figure for [id] is kept, whatever it says (it's asked for once: it doesn't change). */
    fun hasYoutube(id: String): Boolean = prefs.contains(id)

    /** Keeps YouTube's figure for [id] for the next start, offline too (the audio thread reads it: [PlaybackService.startingGain]). */
    fun rememberYoutube(id: String, db: Float) {
        prefs.edit().putFloat(id, db).apply()
    }

    /** The form's measured integrated loudness (LUFS), once it's been scanned: its own recording's. */
    fun measured(id: String): Float? = measuredOf(id)

    /** Whether what's measured of [id] is from the current measurement ([MixTempo.loudnessCurrent]). */
    fun measuredCurrent(id: String): Boolean = measuredCurrentOf(id)

    /** The form's loudest sample (dBFS), measured with its loudness. */
    fun peak(id: String): Float? = peakOf(id)

    companion object {
        /**
         * What YouTube's `loudnessDb` is measured from: 0 is a song at YouTube's own -14 LUFS, positive means louder than
         * that (so it's brought down that much at Prism's default target).
         */
        const val YOUTUBE_REFERENCE_LUFS = -14f
    }
}

/**
 * The catch-up window (1.1.3): a better figure that lands in the first [windowMs] of a form still sets its volume, once,
 * so a song's first play lands at the same level as every later one.
 *
 * A form usually starts on a figure that isn't its own: YouTube's (which hardly differs between a loud master and a quiet
 * one), the song's while its video's is fetched, or none at all. Its measured loudness — the real figure — comes from a
 * scan of the whole song, seconds of work that starts once the form is under way, so it lands in the first seconds of the
 * sound. Before 1.1.3 only the first two seconds could still move the volume, so that figure usually arrived too late and
 * was kept for the next play: a song heard for the first time played at a different level from the same song again.
 *
 * Within the window, a figure from a better source ([Loudness.Source.improves]) is applied once, over
 * [CATCHUP_GLIDE_SEC], and only when it's worth hearing ([minChangeDb]); after it, a jump mid-song is worse than a figure
 * a little off. One catch-up per listen: a song, or another form of it (its video, the version picked for it), is a new
 * listen ([start]), and opens the window again. Times are [android.os.SystemClock.elapsedRealtime] ms.
 */
class LoudnessCatchUp(
    private val windowMs: Long = CATCHUP_MS,
    private val minChangeDb: Float = CATCHUP_MIN_CHANGE_DB,
) {
    /** The form this listen is of, and when it started. */
    private var form: String? = null
    private var startedAtMs = 0L
    /** The source of the figure now in use (what a better one must say more than). */
    private var source = Loudness.Source.NONE
    /** The catch-up has been used for this listen: no figure moves it again. */
    private var used = false

    /** The form [formId] starts now, at [atMs]: its window opens, and a better figure may set its volume. */
    fun start(formId: String?, atMs: Long) {
        form = formId
        startedAtMs = atMs
        source = Loudness.Source.NONE
        used = false
    }

    /** The figure now in use for [formId]: later arrivals improve on this one. */
    fun note(formId: String?, figure: Loudness.Figure) {
        if (formId != null && formId == form) source = figure.source
    }

    /**
     * Whether [figure], landing at [atMs], should take over the volume in use ([gainDbNow]): the form is this listen's,
     * the window is open, it hasn't been used yet, the figure says more than the one in use, and it's at least
     * [minChangeDb] away (a change the listener would hear).
     */
    fun wants(formId: String?, figure: Loudness.Figure, gainDbNow: Float, atMs: Long): Boolean =
        formId != null && formId == form && !used && atMs - startedAtMs < windowMs &&
            figure.source.improves(source) && abs(figure.gainDb - gainDbNow) >= minChangeDb

    /** The figure is the volume now: no figure moves this listen's volume a second time. */
    fun took(formId: String?, figure: Loudness.Figure) {
        used = true
        note(formId, figure)
    }
}
