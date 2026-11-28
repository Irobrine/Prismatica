package com.prism.music.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.prism.music.data.stream.StreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What Mix knows about a song's rhythm and edges, all in ms from the song's start.
 *
 * @property bpm tempo, as heard (an octave can be off: 85 for a 170 BPM song); Mix compares tempos octave-free.
 * @property confidence how clearly the beat stood out (a z-score of the tempo's autocorrelation peak); ambient
 *   songs and spoken intros score low, and Mix then blends without lining beats up.
 * @property firstSoundMs where the music starts (leading silence ends).
 * @property lastSoundMs where the music ends (trailing silence or a fade-out's tail begins).
 * @property firstBeatMs the first beat at or after [firstSoundMs].
 * @property outroBeatMs a beat close to [lastSoundMs]; the song's last bars lie on `outroBeatMs + k × periodMs`.
 * @property fadeOutMs how long the song takes to die away before [lastSoundMs] (its fade-out, or a quiet last
 *   passage), up to 20 s; about 0 for a song that ends on a hit. -1 when measured before this was.
 * @property quietIntroMs how long after [firstSoundMs] the song stays quiet before it comes up to full level, up to
 *   20 s; about 0 for one that starts at full tilt. -1 when measured before this was.
 */
data class Tempo(
    val bpm: Double,
    val confidence: Double,
    val firstSoundMs: Long,
    val lastSoundMs: Long,
    val firstBeatMs: Long,
    val outroBeatMs: Long,
    val fadeOutMs: Long = -1,
    val quietIntroMs: Long = -1,
) {
    val periodMs: Double get() = 60_000.0 / bpm
    /** Clear enough to line beats up on. */
    val steady: Boolean get() = confidence >= MIN_CONFIDENCE

    fun encode() = "$VERSION|$bpm|$confidence|$firstSoundMs|$lastSoundMs|$firstBeatMs|$outroBeatMs|$fadeOutMs|$quietIntroMs"

    /** Measured before the song's fade-out and intro were (Mix's Auto length measures it again). */
    val edgesUnknown: Boolean get() = fadeOutMs < 0 || quietIntroMs < 0

    companion object {
        const val MIN_CONFIDENCE = 3.0
        /** Bumped when [analyze] changes, so songs measured the old way are measured again. */
        private const val VERSION = 3

        /** Version 2 (no fade-out or intro measured) still reads; [MixTempo.measure] scans those songs again. */
        fun decode(s: String): Tempo? = runCatching {
            val p = s.split('|')
            when (p[0].toInt()) {
                VERSION -> Tempo(p[1].toDouble(), p[2].toDouble(), p[3].toLong(), p[4].toLong(), p[5].toLong(), p[6].toLong(), p[7].toLong(), p[8].toLong())
                2 -> Tempo(p[1].toDouble(), p[2].toDouble(), p[3].toLong(), p[4].toLong(), p[5].toLong(), p[6].toLong())
                else -> null
            }
        }.getOrNull()

        /** Whether a stored reading is from before [VERSION] 3. */
        fun isOld(s: String) = s.startsWith("2|")

        /**
         * Reads tempo and edges from a song's band energies ([VideoSync.bandFeatures]: log energy per band, one frame
         * every [frameMs]). Onsets are rises in band energy; the tempo is the autocorrelation lag (60–200 BPM) that
         * stands out most, weighted toward 120 BPM so a song's half or double time isn't picked over it; beats are
         * placed by sliding that period over the onsets near each end of the song. [offsetMs] is added to every time
         * read: a frame's energy is its whole window's, so half a window puts each frame at its window's middle.
         */
        fun analyze(bands: Array<FloatArray>, frameMs: Double, offsetMs: Double = 0.0): Tempo? {
            val frames = bands.firstOrNull()?.size ?: return null
            if (frames < 3000 / frameMs) return null
            val fps = 1000.0 / frameMs

            // Loudness per frame (all bands together), for the music's start and end.
            val loud = FloatArray(frames) { t ->
                var m = Float.NEGATIVE_INFINITY
                for (b in bands) m = max(m, b[t])
                var s = 0.0
                for (b in bands) s += exp((b[t] - m).toDouble())
                (m + ln(s)).toFloat()
            }
            val sorted = loud.sortedArray()
            val peak = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)]
            val floor = peak - SILENCE_BELOW_PEAK
            val smooth = 5
            fun audible(t: Int): Boolean {
                var n = 0
                for (i in t until min(frames, t + smooth)) if (loud[i] > floor) n++
                return n >= smooth - 1
            }
            val first = (0 until frames).firstOrNull(::audible) ?: return null
            val last = (frames - 1 downTo 0).firstOrNull(::audible) ?: return null
            if (last - first < 10 * fps) return null

            // Onset strength: summed rises in band energy, minus its local average, kept positive.
            val flux = FloatArray(frames)
            for (t in 1 until frames) {
                var s = 0f
                for (b in bands) { val d = b[t] - b[t - 1]; if (d > 0) s += d }
                flux[t] = s
            }
            val half = (0.2 * fps).toInt().coerceAtLeast(1)
            val pre = DoubleArray(frames + 1).also { for (i in 0 until frames) it[i + 1] = it[i] + flux[i] }
            val od = FloatArray(frames) { t ->
                val lo = max(0, t - half); val hi = min(frames, t + half + 1)
                max(0.0, flux[t] - (pre[hi] - pre[lo]) / (hi - lo)).toFloat()
            }

            // Tempo: autocorrelation over the music (up to two minutes of it, from its middle).
            val span = min(last - first, (120 * fps).toInt())
            val from = first + ((last - first) - span) / 2
            val to = from + span
            val lagMin = (fps * 60 / 200).toInt()
            val lagMax = (fps * 60 / 60).toInt() + 1
            // Autocorrelation out to four beats of the slowest tempo, so every candidate gets its multiples' support.
            val acfMax = lagMax * 4
            if (to - from <= acfMax * 2) return null
            val acf = DoubleArray(acfMax + 1)
            for (lag in lagMin..acfMax) {
                var s = 0.0
                for (t in from until to - lag) s += od[t].toDouble() * od[t + lag]
                acf[lag] = s / (to - from - lag)
            }
            fun weighted(lag: Int): Double {
                val bpm = 60 * fps / lag
                val prior = exp(-0.5 * sq(ln(bpm / 120.0) / ln(2.0) / 0.9))
                // A beat also lines up with itself two and four beats on (a bar), which a 3:2 or 4:3 cousin of
                // the tempo (a triplet feel, a syncopated hook) doesn't: those multiples back the true period.
                return (acf[lag] + 0.5 * acf[2 * lag] + 0.25 * acf[4 * lag]) * prior
            }
            val scores = (lagMin..lagMax).map { weighted(it) }
            val bestIdx = scores.indices.maxBy { scores[it] }
            val best = lagMin + bestIdx
            val mean = scores.average()
            val sd = sqrt(scores.sumOf { sq(it - mean) } / scores.size).takeIf { it > 1e-12 } ?: return null
            val confidence = (scores[bestIdx] - mean) / sd
            // Sub-frame period from a parabola through the peak and its neighbours.
            var lag = if (best in lagMin + 1 until lagMax) {
                val a = acf[best - 1]; val b = acf[best]; val c = acf[best + 1]
                val d = a - 2 * b + c
                best + (if (d < 0) (0.5 * (a - c) / d).coerceIn(-0.5, 0.5) else 0.0)
            } else best.toDouble()

            /** Onset strength landing on a comb of beats [period] apart, starting at [start], up to frame [b]. */
            fun comb(start: Double, b: Int, period: Double): Double {
                var s = 0.0
                var t = start
                while (t < b - 1) { s += interp(od, t); t += period }
                return s
            }

            /** The first beat in [a, b) of the comb that lands on the most onset strength. */
            fun phase(a: Int, b: Int, period: Double = lag): Double {
                var bestPhase = 0.0; var bestScore = -1.0
                var ph = 0.0
                while (ph < period) {
                    val s = comb(a + ph, b, period)
                    if (s > bestScore) { bestScore = s; bestPhase = ph }
                    ph += 0.5
                }
                return a + bestPhase
            }

            // Fast tempos are often a song's eighth notes (kick on the beat, hi-hat between). If every other "beat"
            // is clearly stronger, the song is at half that tempo and the strong ones are its beats; otherwise
            // (drum & bass: kick and snare both hit hard) the fast tempo stands. Only above 152: a rock song at
            // 145–150 (Mr. Brightside, 148) has a kick on 1 and 3 louder than its snare on 2 and 4, and was halved
            // to 74; the ballads this is for read 158–184 here (Hello, Ain't No Sunshine, Creep).
            if (60 * fps / lag > HALVE_ABOVE_BPM && 60 * fps / (2 * lag) >= 70) {
                val strong = phase(from, to, 2 * lag)
                val a = comb(strong, to, 2 * lag)
                val b = comb(strong + lag, to, 2 * lag)
                if (a > 1.25 * b) lag *= 2
            }
            // Fine tempo: the autocorrelation's peak is good to about a frame; a beat comb laid over the whole
            // stretch drifts off the onsets with even a tiny error, so the period whose best comb scores highest
            // is the exact one. (A 1% error is a beat's worth of drift over a minute, and a blend leans on the
            // ending song's beats being where its last bars put them.)
            run {
                var bestP = lag; var bestS = -1.0
                var p = lag * 0.985
                while (p <= lag * 1.015) {
                    var ph = 0.0
                    while (ph < p) {
                        val s = comb(from + ph, to, p)
                        if (s > bestS) { bestS = s; bestP = p }
                        ph += 1.0
                    }
                    p += lag * 0.0005
                }
                lag = bestP
            }
            val bpm = 60 * fps / lag

            val window = (20 * fps).toInt()
            val introAnchor = phase(first, min(last, first + window))
            // First beat at or after the music starts (a hair early still counts: onsets lead the peak slightly).
            var firstBeat = introAnchor
            while (firstBeat - lag >= first - 3) firstBeat -= lag
            while (firstBeat < first - 3) firstBeat += lag
            val outroFrom = max(first, last - window)
            val outroAnchor = phase(outroFrom, last + 1)
            var outroBeat = outroAnchor
            while (outroBeat + lag <= last) outroBeat += lag

            val (fadeOut, quietIntro) = edges(loud, first, last, fps)
            return Tempo(
                bpm = bpm,
                confidence = confidence,
                firstSoundMs = (first * frameMs + offsetMs).toLong(),
                lastSoundMs = (last * frameMs + offsetMs).toLong(),
                firstBeatMs = (firstBeat * frameMs + offsetMs).toLong(),
                outroBeatMs = (outroBeat * frameMs + offsetMs).toLong(),
                fadeOutMs = (fadeOut * frameMs).toLong(),
                quietIntroMs = (quietIntro * frameMs).toLong(),
            )
        }

        /**
         * How the song dies away and comes in, in frames: from where its level (averaged over a second) last stands
         * near the song's typical level to where its music ends, and from where its music starts to where that level
         * is first reached. [loud] is per-frame log power over the music [first]..[last].
         */
        private fun edges(loud: FloatArray, first: Int, last: Int, fps: Double): Pair<Int, Int> {
            // A second's level is its energy averaged (not its log): the quiet gaps between beats would otherwise weigh as
            // much as the beats, and hide a fade until it was nearly over.
            val ref = loud.max()
            val half = (fps / 2).toInt().coerceAtLeast(1)
            val sums = DoubleArray(loud.size + 1).also { for (i in loud.indices) it[i + 1] = it[i] + exp((loud[i] - ref).toDouble()) }
            fun level(t: Int): Double {
                val lo = max(first, t - half); val hi = min(last + 1, t + half + 1)
                return ln(((sums[hi] - sums[lo]) / (hi - lo)).coerceAtLeast(1e-30)) + ref
            }
            // The song's usual level: the median of its seconds.
            val seconds = (first..last step half).map(::level).sorted()
            val typical = seconds[seconds.size / 2]
            val reach = (EDGE_MAX_S * fps).toInt()
            // Back from the end: the last moment the song is still near its usual level begins its fade.
            var fadeFrom = last
            while (fadeFrom > max(first, last - reach) && level(fadeFrom) < typical - FADE_BELOW) fadeFrom--
            // On from the start: the intro is quiet until it first comes near the usual level.
            var fullFrom = first
            while (fullFrom < min(last, first + reach) && level(fullFrom) < typical - INTRO_BELOW) fullFrom++
            return (last - fadeFrom) to (fullFrom - first)
        }

        /** A song's edges are looked for this far in (s): a longer fade or intro counts as this long. */
        private const val EDGE_MAX_S = 20.0
        /** Under the song's typical level by this much (log power: 4.5 dB), its ending is fading. */
        private const val FADE_BELOW = 1.04
        /** Under it by this much (6 dB), its intro is still quiet. */
        private const val INTRO_BELOW = 1.38

        /** Below the song's loud parts by this much (natural log of power: 40 dB), it counts as silence. */
        private const val SILENCE_BELOW_PEAK = 9.2f
        /** A tempo read faster than this may be a song's eighth notes, and is halved when every other beat leads. */
        const val HALVE_ABOVE_BPM = 152.0

        private fun sq(x: Double) = x * x

        private fun interp(x: FloatArray, t: Double): Double {
            val i = floor(t).toInt()
            if (i < 0 || i + 1 >= x.size) return 0.0
            val f = t - i
            return x[i] * (1 - f) + x[i + 1] * f
        }
    }
}

/**
 * Scans songs, once each: their tempo and edges for Mix ([Tempo]) and their loudness for normalization
 * ([LoudnessMeter]). The song's audio is read from the phone when it's there ([local]: a download, the song
 * cache, a local file), else its smallest stream is fetched whole (1–4 MB, on Wi-Fi unless mobile data is allowed);
 * it's decoded to mono ~11 kHz for the tempo and key, and every channel is measured for its loudness. Results are
 * kept per recording scanned (`mix_tempo` and `song_loudness` prefs).
 *
 * Since 1.1.3 a scan is asked for the *form* that plays ([Loudness.formId]: the song, its video, the version picked for
 * it), and its figures are filed under that id: a version's loudness is the version's, never the song's ([sourceSongId]
 * is only where its audio may be found when the form's own copy isn't here).
 */
class MixTempo(
    private val context: Context,
    private val http: OkHttpClient,
    private val streams: StreamResolver,
    /**
     * Where a form's audio is on the phone, if it is (may copy it to the file it's given), else null. [songId] is the
     * queue's song it plays for: a picked version's download is the song's ([com.prism.music.AppContainer.audioOnPhone]).
     */
    private val local: (id: String, songId: String, scratch: File) -> android.net.Uri? = { _, _, _ -> null },
    /** Blocks while the playing song is still loading (a scan starting then competed with it); off the main thread. */
    private val yieldToPlaying: () -> Unit = {},
    /** Music is playing right now (DJ mode's quick scans then go one at a time). */
    private val playing: () -> Boolean = { false },
    /** Where each scanned song's outline goes, for the waveform progress bar (1.7.4). */
    private val waves: com.prism.music.data.SongWaves? = null,
    /** Where what's measured goes too, in one table that can be filtered (1.8.3: `song_traits`). */
    private val traits: com.prism.music.data.TraitsSink? = null,
    /** The sound analysis (sleep score, tags, similarity), read from the same decode ([com.prism.music.analysis]). */
    private val analyzer: com.prism.music.analysis.SongAnalyzer? = null,
) {
    private val prefs = context.getSharedPreferences("mix_tempo", Context.MODE_PRIVATE)
    /** Form id ([Loudness.formId]) -> integrated loudness (LUFS; NaN when it couldn't be measured). Also marks it as scanned. */
    private val loudPrefs = context.getSharedPreferences("song_loudness", Context.MODE_PRIVATE)
    /** Form id -> the [LUFS_VERSION] its loudness was measured with: one measured by another way is measured again. */
    private val loudVersion = context.getSharedPreferences("song_loudness_version", Context.MODE_PRIVATE)
    /** Form id -> its loudest sample (dBFS), measured with its loudness since 1.8.0: how far Normalize may lift it. */
    private val peakPrefs = context.getSharedPreferences("song_peak", Context.MODE_PRIVATE)
    /** Song id -> its key ([MusicKey.encode], or "none" when it has no clear one); DJ mode's harmonic ordering. */
    private val keyPrefs = context.getSharedPreferences("song_key", Context.MODE_PRIVATE)
    /** Form id -> when its scan last failed: a failure isn't forever (a file back on the phone, a read that went wrong). */
    private val failed = ConcurrentHashMap<String, Long>()
    /** Form or song id -> kept from being scanned ([skip]): a stream that can't play, so no figure can ever be had. */
    private val skippedPrefs = context.getSharedPreferences("mix_skipped", Context.MODE_PRIVATE)
    /**
     * Whole scans at once (see [scanSlots]): decoding a whole song is the heaviest thing Prism does in the background,
     * so it's one unless a walk the listener started has the processor to itself ([mayScanTwice]) — then two, which
     * overlaps one song's decoding and analysis with the next's.
     */
    private val scanSlots = ScanSlots(MAX_SCANS)

    /**
     * Whether [id]'s scan failed so lately that it isn't worth asking again yet (1.2.1). It used to be for the rest of
     * the run: one transient failure — READ_MEDIA_AUDIO revoked, a half-copied file, an SD card out for a moment —
     * kept that recording unmeasured (and so unnormalized) until Prism restarted.
     */
    private fun hasFailed(id: String): Boolean =
        failed[id]?.let { android.os.SystemClock.elapsedRealtime() - it < FAILED_RETRY_MS } == true

    /**
     * Whether [id] (a form, or a song kept with the form it plays as) is one the listener has kept from being scanned
     * ([skip]): a stream that can't play, a file that won't decode. Its figure can't be had, so no scan starts for it.
     */
    fun skipped(id: String): Boolean = skippedPrefs.getBoolean(id, false)

    /** Keeps [ids] from being scanned, now and after a restart: "Measure songs now" leaves them out, and no scan starts. */
    fun skip(ids: Collection<String>) {
        val keep = ids.filter { it.isNotBlank() }.toSet()
        if (keep.isEmpty()) return
        skippedPrefs.edit().apply { keep.forEach { putBoolean(it, true) } }.apply()
        failed.keys.removeAll(keep)
    }

    /** Lets [ids] be scanned again ([skip]). */
    fun unskip(ids: Collection<String>) {
        val again = ids.filter { it.isNotBlank() }.toSet()
        if (again.isEmpty()) return
        skippedPrefs.edit().apply { again.forEach { remove(it) } }.apply()
        failed.keys.removeAll(again)
    }

    /** Lets every song kept from being scanned be scanned again ([skip]). */
    fun unskipAll() = skippedPrefs.edit().clear().apply()

    /**
     * Forgets what was measured of [id] as a song's sound for Mix (tempo, edges, key, DJ mode's quick scan), so it's
     * measured again. Its loudness and peak stay (1.1.3): a figure belongs to the recording it was measured from (a
     * [Loudness.formId]), and a pick changing says which recording plays next, not that this one's figure was wrong.
     */
    fun forget(id: String) {
        for (p in listOf(prefs, keyPrefs, quickPrefs)) p.edit().remove(id).apply()
        failed.remove(id)
        traits?.forget(id)
    }

    /** Every song measured so far, whole or by a quick scan (what [measuredCount] counts). */
    fun measuredIds(): Set<String> = loudPrefs.all.keys + quickPrefs.all.keys

    /**
     * What the prefs hold of [id], as a `song_traits` row (1.8.3's one-time copy). Its energy goes without brightness,
     * which wasn't kept before 1.8.3 ([Energy.of]).
     */
    fun traitsFromPrefs(id: String): com.prism.music.data.SongTraits? {
        if (scanned(id)) {
            val tempo = cached(id)
            val lufs = loudness(id)
            return com.prism.music.data.SongTraits(id, tempo, key(id), lufs, Energy.of(lufs, tempo?.confidence, null), whole = true)
        }
        val (bpm, confidence, lufs) = quick(id) ?: return null
        val tempo = if (bpm > 0) Tempo(bpm, confidence, 0, 0, 0, 0) else null
        return com.prism.music.data.SongTraits(id, tempo, key(id), lufs, Energy.of(lufs, tempo?.confidence, null), whole = false)
    }

    fun cached(id: String): Tempo? = prefs.getString(id, null)?.let(Tempo::decode)

    /** How many songs have been measured so far, whole or by a quick scan: what a calm ↔ energetic radio has to go by. */
    fun measuredCount(): Int = (loudPrefs.all.keys + quickPrefs.all.keys).size

    /** The form's measured loudness (integrated LUFS), once it's been scanned: the recording heard, not its song's. */
    fun loudness(id: String): Float? = loudPrefs.getFloat(id, Float.NaN).takeIf { !it.isNaN() }

    /** Whether the form's stored loudness is from the current measurement (1.8.5: every channel; 1.1.3: of this form). */
    fun loudnessCurrent(id: String): Boolean = loudVersion.getInt(id, 0) >= LUFS_VERSION

    /** Measured before the loudness heard every channel: it's measured again when Normalize wants it. */
    private fun loudnessOld(id: String) = loudPrefs.contains(id) && !loudnessCurrent(id)

    /** The song's loudest sample (dBFS), once it's been scanned (by 1.8.0 or later). */
    fun peak(id: String): Float? = peakPrefs.getFloat(id, Float.NaN).takeIf { !it.isNaN() }

    /** Measured before peaks were (1.8.0): Normalize can lift it no more than 6 dB until it's scanned again. */
    private fun noPeak(id: String) = loudness(id) != null && !peakPrefs.contains(id)

    /**
     * Whether [id]'s normalization figure is one the player can use as it is: measured with the current meter
     * ([LUFS_VERSION], every channel) and with its peak (the lift's headroom), or measured and found to have nothing
     * measurable (silence: a scan that would say the same again). A song without one is work for the analysis walk, whose
     * scan measures it on the way ([measure]).
     */
    fun normalizationReady(id: String): Boolean = normalizationReady(
        scanned = loudPrefs.contains(id), lufs = loudness(id), current = loudnessCurrent(id), hasPeak = peakPrefs.contains(id),
    )

    /** The song's key once it's been scanned for one (null for none clear, or not scanned yet). */
    fun key(id: String): MusicKey? = keyPrefs.getString(id, null)?.let(MusicKey::decode)

    /** Scanned for its key by the current [KeyDetector] (with or without a clear answer). */
    fun keyed(id: String) = keyPrefs.getString(id, null)?.let { it.startsWith("${MusicKey.VERSION}|") || it == "none:${MusicKey.VERSION}" } == true

    private fun scanned(id: String) = loudPrefs.contains(id)

    /** Scanned before the song's fade-out and intro were measured (for Mix's Auto length): scanned again once. */
    private fun old(id: String) = prefs.getString(id, null)?.let(Tempo::isOld) == true

    internal class Scan(
        val tempo: Tempo?, val lufs: Float?, val key: MusicKey?, val peakDb: Float?, val brightness: Double?,
        /** The sound analysis' descriptors, when the scan was asked for them ([com.prism.music.analysis.SongAnalyzer]). */
        val features: com.prism.music.analysis.SongFeatures? = null,
        /** The tagger's 16 kHz windows ([com.prism.music.analysis.TaggerTap]), when its model is on the phone. */
        val tagWindows: List<FloatArray> = emptyList(),
    )

    // ---------------------------------------------------------------- DJ mode's quick scan

    /** Song id -> "bpm|confidence|lufs" from a [QuickScan] (the full scan's figures win when there are both). */
    private val quickPrefs = context.getSharedPreferences("dj_quick", Context.MODE_PRIVATE)
    /** A few quick scans at once (each is a third of a song, at background priority); separate from the full scans. */
    private val quickGate = kotlinx.coroutines.sync.Semaphore(QUICK_AT_ONCE)

    enum class QuickResult { KEYED, NEEDS_NETWORK, FAILED }

    /** The tempo DJ mode goes by: the full scan's when it's steady, else the quick scan's when that's steady. */
    fun djBpm(id: String): Double? = cached(id)?.takeIf { it.steady }?.bpm ?: quick(id)?.takeIf { it.second >= Tempo.MIN_CONFIDENCE }?.first

    /** The loudness DJ mode goes by (its energy): measured whole, else from the quick scan. */
    fun djLoudness(id: String): Float? = loudness(id) ?: quick(id)?.third

    private fun quick(id: String): Triple<Double, Double, Float?>? = quickPrefs.getString(id, null)?.split('|')?.let { p ->
        val bpm = p.getOrNull(0)?.toDoubleOrNull() ?: return null
        Triple(bpm, p.getOrNull(1)?.toDoubleOrNull() ?: 0.0, p.getOrNull(2)?.toFloatOrNull()?.takeIf { !it.isNaN() })
    }

    /**
     * DJ mode's look at a song: its key, tempo and loudness from three stretches of it ([QuickScan]), fetched by range
     * when it isn't on the phone ([network] allowing). A song already scanned for its key is [QuickResult.KEYED] at once.
     */
    suspend fun quickScan(
        id: String, network: Boolean, priority: Int = android.os.Process.THREAD_PRIORITY_BACKGROUND,
        /** A job Android scheduled (charging, screen off), which has limits of its own rather than [BackgroundBudget]'s. */
        scheduled: Boolean = false,
    ): QuickResult {
        if (keyed(id)) return QuickResult.KEYED
        if (hasFailed(id)) return QuickResult.FAILED
        // While music plays, one at a time (Reddit 1.7: "DJ mode is so laggy": three decodes beside playback were too
        // much for mid-range phones); while paused, a few at once.
        // A scheduled job does one at a time, with music off, and doesn't wait behind scans held while the listener's away.
        if (scheduled) return quickScanNow(id, network, priority, scheduled = true)
        return quickGate.withPermit { if (playing()) playingGate.withLock { quickScanNow(id, network, priority, scheduled) } else quickScanNow(id, network, priority, scheduled) }
    }

    /** One quick scan at a time while music plays ([quickScan]). */
    private val playingGate = kotlinx.coroutines.sync.Mutex()

    private suspend fun quickScanNow(id: String, network: Boolean, priority: Int, scheduled: Boolean): QuickResult {
        if (keyed(id)) return QuickResult.KEYED
        return run {
            withContext(Dispatchers.IO) {
                val started = System.nanoTime()
                val before = android.os.Process.getThreadPriority(android.os.Process.myTid())
                android.os.Process.setThreadPriority(priority)
                runCatching { yieldToPlaying() }
                AudioDiagnostics.log("background", "DJ quick scan of $id started")
                try {
                    val (r, bytes) = BackgroundBudget.asJob(scheduled) { quickRead(id, network) } ?: return@withContext QuickResult.NEEDS_NETWORK
                    keyPrefs.edit().putString(id, r.key?.encode() ?: "none:${MusicKey.VERSION}").apply()
                    quickPrefs.edit().putString(id, "${r.tempo?.bpm ?: 0.0}|${r.tempo?.confidence ?: 0.0}|${r.lufs ?: Float.NaN}").apply()
                    traits?.record(com.prism.music.data.SongTraits(id, r.tempo, r.key, r.lufs, Energy.of(r.lufs, r.tempo?.confidence, r.brightness), whole = false))
                    Log.i(
                        TAG, "quick-scanned $id in ${(System.nanoTime() - started) / 1_000_000} ms (${bytes / 1024} KB): key " +
                            (r.key?.let { "${it.name} (${it.camelot})" } ?: "unclear") + ", " +
                            (r.tempo?.let { "%.1f BPM (confidence %.1f)".format(it.bpm, it.confidence) } ?: "no clear beat"),
                    )
                    QuickResult.KEYED
                } catch (e: Exception) {
                    Log.w(TAG, "quick scan of $id failed: $e")
                    failed[id] = android.os.SystemClock.elapsedRealtime()
                    QuickResult.FAILED
                } finally {
                    android.os.Process.setThreadPriority(before)
                    AudioDiagnostics.log("background", "DJ quick scan of $id ended after ${(System.nanoTime() - started) / 1_000_000} ms")
                }
            }
        }
    }

    /** The quick scan's result and the bytes it downloaded; null when the song isn't on the phone and [network] says no. */
    private fun quickRead(id: String, network: Boolean): Pair<QuickScan.Result, Long>? {
        val scratch = File(context.cacheDir, "djq_$id.part")
        val ex = MediaExtractor()
        var ranged: RangedSource? = null
        try {
            var durationMs = 0L
            val uri = local(id, id, scratch)
            if (uri != null) ex.setDataSource(context, uri, null)
            else {
                if (!network || id.startsWith(com.prism.music.data.model.Song.LOCAL_PREFIX)) return null
                val a = streams.analysisAudio(id)
                if (a.contentLength <= 0) throw java.io.IOException("the stream's length is unknown")
                ranged = RangedSource(http, a.url, a.contentLength)
                ex.setDataSource(ranged)
                durationMs = a.durationSec * 1000
            }
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw java.io.IOException("no audio track")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            if (fmt.containsKey(MediaFormat.KEY_DURATION)) durationMs = fmt.getLong(MediaFormat.KEY_DURATION) / 1000
            if (durationMs <= 0) throw java.io.IOException("the song's length is unknown")
            val (pieces, sr) = QuickScan.decode(ex, track, QuickScan.windows(durationMs)) ?: throw java.io.IOException("couldn't decode its audio")
            // The extractor takes a failed read for the end of the song. A key from part of one isn't kept (a link that
            // ran out while the scan waited for the listener to come back, say); it's asked again another time.
            ranged?.failure?.let { throw it }
            return QuickScan.analyse(pieces, sr) to (ranged?.fetched ?: 0L)
        } finally {
            ex.release()
            ranged?.close()
            scratch.delete()
        }
    }

    /**
     * Scans the song now if it hasn't been ([network]: whether its audio may be downloaded when it isn't on the phone),
     * and returns its tempo; null when it has none that's clear, or it can't be scanned yet. [edges]: a song scanned
     * before its fade-out and intro were measured is scanned again (only Crossfade's automatic length needs them), and
     * with [key], one scanned before its key was (DJ mode), with [wave], one with no outline for the waveform bar, and
     * with [peak], one scanned before its peak was, or before its loudness heard every channel (Normalize).
     *
     * [id] is the form scanned ([Loudness.formId]): the recording that actually plays, whose figure is what Normalize
     * needs. [sourceSongId] is the queue's song it plays for, when that's another id (a picked version, a video): where
     * its audio may be found on the phone when the form's own copy isn't ([local]).
     *
     * [priority] (1.2.1): this scan is for the form being heard, whose volume waits for it ([LoudnessCatchUp]'s first
     * seconds), so other scans make way for it ([ScanPriority]). [deferToPlaying]: this scan belongs to a background
     * walk (the settings' "Measure songs now"): it starts no song while the heard form's scan waits, and gives up its
     * own where it next checks ([ease]) so that figure lands in the window rather than behind whole songs nobody is
     * hearing. A scan given up this way isn't a failure — the form is still unmeasured, so a later walk takes it up.
     */
    suspend fun measure(
        id: String, network: Boolean = true, edges: Boolean = true, key: Boolean = false, wave: Boolean = false, peak: Boolean = false,
        sourceSongId: String? = null, priority: Boolean = false, deferToPlaying: Boolean = false,
        /** The sound analysis wants this song (Settings → Sound analysis): scanned again when it has no current one. */
        analyse: Boolean = false,
        /**
         * The sound analysis this song already has is from older descriptors or an older tagger: scanned again to redo it.
         * Playback asks for this of the song being heard, so a version's change reaches the songs the listener actually
         * plays instead of waiting for the daily job to come round.
         */
        refresh: Boolean = false,
        /** A job Android scheduled (charging, screen off), with limits of its own rather than [BackgroundBudget]'s. */
        scheduled: Boolean = false,
    ): Tempo? {
        fun done() = scanned(id) && !(edges && old(id)) && !(key && !keyed(id)) && !(wave && waves?.has(id) == false) &&
            !(peak && (noPeak(id) || loudnessOld(id))) && !(analyse && analyzer?.needs(id) == true) &&
            !(refresh && analyzer?.stale(id) == true)
        // Kept from being scanned ([skip]): its figure can't be had, so neither a walk nor a play asks again.
        if (skipped(id) || (sourceSongId != null && skipped(sourceSongId))) return cached(id)
        if (done() || hasFailed(id)) return cached(id)
        if (priority) scanPriority.playingWants()
        try {
            // A walk starts no song while the form being heard is waiting for its own scan.
            if (deferToPlaying) while (scanPriority.anyPlaying()) kotlinx.coroutines.delay(PLAYING_SCAN_POLL_MS)
            // Waiting for the player to finish loading is the player's, not this scan's: done before the lock is taken,
            // so a scan the volume is waiting for isn't held behind it.
            withContext(Dispatchers.IO) { runCatching { yieldToPlaying() } }
            return withScanSlot(deferToPlaying) {
                if (done()) return@withScanSlot cached(id)
                withContext(Dispatchers.IO) {
                    if (deferToPlaying) scanPriority.markWalkScan(true)
                    try {
                        val started = System.nanoTime()
                        // Decoding a whole song is heavy: at background priority, so the playing song's own loading comes first.
                        val before = android.os.Process.getThreadPriority(android.os.Process.myTid())
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                        AudioDiagnostics.log("background", "song scan of $id started (Normalize/Crossfade/DJ)")
                        // Any scan reads the sound analysis' descriptors too when the song has none yet (they come from
                        // the same decode, for a fraction of its cost); the tagger's windows only when its model is here.
                        val describe = analyzer?.needs(id) == true
                        val tagger = describe && analyzer?.taggerReady() == true
                        val r = try {
                            runCatching { BackgroundBudget.asJob(scheduled) { scan(id, network, sourceSongId, describe, tagger) } }
                                .onFailure { if (it !is ScansAbandoned) Log.w(TAG, "scan of $id failed: $it") }
                        } finally {
                            android.os.Process.setThreadPriority(before)
                            AudioDiagnostics.log("background", "song scan of $id ended after ${(System.nanoTime() - started) / 1_000_000} ms")
                        }
                        // A scan the listener stopped (a second tap on the settings' "Measure songs now", or Prism going) isn't
                        // the song's failure: it's measured next time, not set aside as failed for the rest of the run.
                        (r.exceptionOrNull() as? kotlinx.coroutines.CancellationException)?.let { throw it }
                        // Nor is one a walk gave up for the form being heard: nothing is kept of it, and it's walked again.
                        if (r.exceptionOrNull() is ScansAbandoned) return@withContext cached(id)
                        val s = r.getOrNull()
                        when {
                            r.isFailure -> { failed[id] = android.os.SystemClock.elapsedRealtime(); cached(id) }
                            s == null -> cached(id) // not on the phone, and no network for it now
                            else -> {
                                prefs.edit().putString(id, s.tempo?.encode() ?: "none").apply()
                                loudPrefs.edit().putFloat(id, s.lufs ?: Float.NaN).apply()
                                // A figure that couldn't be worked out (silence, or a file that decodes to nothing) isn't
                                // "measured": leaving it current would keep the form at 0 dB for good, as a figure from
                                // the current scan is never asked for again (1.2.1).
                                if (s.lufs != null) loudVersion.edit().putInt(id, LUFS_VERSION).apply()
                                else loudVersion.edit().remove(id).apply()
                                peakPrefs.edit().putFloat(id, s.peakDb ?: Float.NaN).apply()
                                keyPrefs.edit().putString(id, s.key?.encode() ?: "none:${MusicKey.VERSION}").apply()
                                traits?.record(com.prism.music.data.SongTraits(id, s.tempo, s.key, s.lufs, Energy.of(s.lufs, s.tempo?.confidence, s.brightness), whole = true))
                                val ms = (System.nanoTime() - started) / 1_000_000
                                val t = s.tempo
                                Log.i(TAG, "scanned $id in $ms ms: loudness ${s.lufs?.let { "%.1f LUFS".format(it) } ?: "unknown"}, " +
                                    (if (t == null) "no clear music" else "%.1f BPM (confidence %.1f), music %d–%d ms, first beat %d ms"
                                        .format(t.bpm, t.confidence, t.firstSoundMs, t.lastSoundMs, t.firstBeatMs)) +
                                    ", key ${s.key?.let { "${it.name} (${it.camelot}, confidence %.3f)".format(it.confidence) } ?: "unclear"}")
                                // The analysis last: its tagger's inference never holds up the figure Normalize waits for.
                                s.features?.let { f ->
                                    val a = System.nanoTime()
                                    runCatching { BackgroundBudget.asJob(scheduled) { analyzer?.record(id, sourceSongId, f, s.tagWindows) } }
                                        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Log.w(TAG, "analysis of $id failed: $it") }
                                    Log.i(TAG, "analysed $id in ${(System.nanoTime() - a) / 1_000_000} ms after its scan (${s.tagWindows.size} tagger windows)")
                                }
                                t
                            }
                        }
                    } finally {
                        if (deferToPlaying) scanPriority.markWalkScan(false)
                    }
                }
            }
        } finally {
            if (priority) scanPriority.playingDone()
        }
    }

    /**
     * Holds a whole scan's place. A walk's scan ([walk]: [measure]'s `deferToPlaying`) may share the processor with one
     * other when [mayScanTwice]; every other scan, and a walk's on a phone that can't, has the slots to itself — the
     * same one-at-a-time the scans always had.
     */
    private suspend fun <T> withScanSlot(walk: Boolean, block: suspend () -> T): T =
        scanSlots.with(shared = walk && mayScanTwice(), block = block)

    /** [mayScanTwice]'s rule, on its own so it can be read (and tested) without a phone ([twoAtOnce]). */
    internal fun mayScanTwice(): Boolean {
        val runtime = Runtime.getRuntime()
        return twoAtOnce(
            cores = runtime.availableProcessors(),
            where = BackgroundBudget.where(),
            slow = slowPhone(),
            // Unknown counts as low: a second song's samples aren't risked on a phone that won't say.
            lowRam = runCatching { context.getSystemService(android.app.ActivityManager::class.java)?.isLowRamDevice }.getOrNull() ?: true,
            freeHeap = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()),
            maxHeap = runtime.maxMemory(),
        )
    }

    /** Null when the form's audio isn't on the phone and [network] doesn't allow fetching it. */
    private fun scan(id: String, network: Boolean, sourceSongId: String? = null, describe: Boolean = false, tagger: Boolean = false): Scan? {
        val file = File(context.cacheDir, "mix_$id.part")
        try {
            val uri = local(id, sourceSongId ?: id, file)
            if (uri == null) {
                if (!network || id.startsWith(com.prism.music.data.model.Song.LOCAL_PREFIX)) return null
                val a = streams.analysisAudio(id)
                if (a.contentLength <= 0) {
                    // The stream won't say how long it is, so its bytes can't be asked for by range: fetched whole first,
                    // as every stream used to be.
                    Log.d(TAG, "downloading $id to scan it (its length is unknown)")
                    http.newCall(Request.Builder().url(a.url).build()).execute().use { r ->
                        if (!r.isSuccessful) throw java.io.IOException("audio returned HTTP ${r.code}")
                        file.outputStream().use { out -> r.body.byteStream().copyTo(out) }
                    }
                    return analyse({ decodeAll(android.net.Uri.fromFile(file), describe, tagger) ?: throw java.io.IOException("couldn't decode its audio") }, onSamples = onSamples(id), describe = describe)
                }
                // Decoded as it arrives rather than downloaded whole first (1.9): the smallest stream is what the scan
                // reads either way, and only what's needed for the first [MAX_SECONDS] is fetched at all. Its blocks are
                // fetched one ahead while the decoder works, so the network and the decoding overlap.
                Log.d(TAG, "streaming $id to scan it")
                val ranged = RangedSource(http, a.url, a.contentLength, prefetch = true)
                try {
                    // Its outline and debug copy are kept only if the stream was read whole: a failed read is the
                    // extractor's end of the song, and an outline of its first seconds would outlive the discarded
                    // figure (SongWaves.has would then stop the song being measured again).
                    val scan = analyse(
                        { decodeAll(ranged, describe, tagger) ?: throw java.io.IOException("couldn't decode its audio") },
                        onSamples = { pcm, count, rate -> if (ranged.failure == null) keepSamples(id, pcm, count, rate) },
                        describe = describe,
                    )
                    // A read that failed is the extractor's end of the song: a figure from part of one isn't kept (it's
                    // asked again another time), as the quick scan does with the same source.
                    ranged.failure?.let { throw it }
                    return scan
                } finally {
                    ranged.close()
                }
            }
            return analyse({ decodeAll(uri, describe, tagger) ?: throw java.io.IOException("couldn't decode its audio") }, onSamples = onSamples(id), describe = describe)
        } finally {
            file.delete()
        }
    }

    /** What a scan reads from the samples it decoded once, beside the figures proper ([analyse]). */
    private fun onSamples(id: String): (FloatArray, Int, Int) -> Unit = { pcm, count, rate -> keepSamples(id, pcm, count, rate) }

    private fun keepSamples(id: String, pcm: FloatArray, count: Int, rate: Int) {
        // The waveform progress bar's outline, from the same audio (1.7.4).
        waves?.let { w -> runCatching { w.put(id, com.prism.music.data.SongWaves.outline(pcm, count)) } }
        // Debug builds keep the decoded audio when asked (adb shell touch …/files/mix_dump), for tuning [Tempo.analyze].
        if (com.prism.music.BuildConfig.DEBUG) context.getExternalFilesDir(null)?.let { dir ->
            if (File(dir, "mix_dump").exists()) runCatching {
                val bb = java.nio.ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until count) bb.putFloat(pcm[i])
                File(dir, "mix_${id}_$rate.f32").writeBytes(bb.array())
            }
        }
    }

    /** A song as mono PCM: the first [count] samples of [pcm], at [rate], its loudest sample before (0 … 1), and its measured loudness (LUFS). */
    internal class Decoded(
        val pcm: FloatArray, val count: Int, val rate: Int, val peak: Float, val lufs: Float?,
        /** The loudness curves and range, when the sound analysis asked ([LoudnessMeter.stats]). */
        val loudness: com.prism.music.analysis.LoudnessStats? = null,
        /** The loudest point between samples too (0 … 1), when the sound analysis asked; 0 when not measured. */
        val truePeak: Float = 0f,
        /** The tagger's 16 kHz windows ([com.prism.music.analysis.TaggerTap]). */
        val tagWindows: List<FloatArray> = emptyList(),
    )

    private fun decodeAll(uri: android.net.Uri, describe: Boolean = false, tagger: Boolean = false): Decoded? =
        decodeAll(describe, tagger) { ex -> ex.setDataSource(context, uri, null) }

    /** [decodeAll] of a source read on demand (a stream fetched as the decoder reads it), not a file. */
    private fun decodeAll(source: android.media.MediaDataSource, describe: Boolean = false, tagger: Boolean = false): Decoded? =
        decodeAll(describe, tagger) { ex -> ex.setDataSource(source) }

    /**
     * The whole file as mono PCM, decimated to roughly 11 kHz, and its loudness over every channel as it decodes
     * ([LoudnessMeter]). One array for the mono, sized from the song's length (it used to grow by doubling, 4 + 8 +
     * 16 MB for a song of 12, and be copied once more at the end); the loudness needs no copy of its own. [open] gives
     * the extractor its source (a file or a stream).
     */
    private fun decodeAll(describe: Boolean, tagger: Boolean, open: (MediaExtractor) -> Unit): Decoded? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            open(ex)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            dec.configure(fmt, null, null, 0)
            dec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            var factor = max(1, rate / 11025)
            var out = FloatArray(decodedSize(if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1000 else 0, rate / factor))
            var count = 0
            val mono = Downmix()
            // The sound analysis' extras, from the same buffers: the true peak, and the tagger's windows at 16 kHz.
            mono.measureTruePeak = describe
            val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            var tap: com.prism.music.analysis.TaggerTap? = null
            var tapRate = 0
            var meter: LoudnessMeter? = null
            var meterRate = 0
            var meterChannels = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val i = dec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val size = runCatching { ex.readSampleData(dec.getInputBuffer(i)!!, 0) }.getOrDefault(-1)
                        if (size < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                ease()
                val o = dec.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = dec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    if (count == 0) factor = max(1, rate / 11025)
                } else if (o >= 0) {
                    val buf = dec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val frames = info.size / (channels * (if (isFloat) 4 else 2))
                    // The loudness hears every channel at the decimated rate, from the same frames the mono comes from.
                    val sr = rate / factor
                    val loudChannels = channels.coerceAtMost(MAX_LOUDNESS_CHANNELS)
                    if (meter == null || meterRate != sr || meterChannels != loudChannels) {
                        if (count > 0 && meter != null) Log.w(TAG, "the decoder's format changed mid-scan: its loudness is measured from here")
                        meter = LoudnessMeter(sr, loudChannels).also { mono.meter = it }
                        meterRate = sr
                        meterChannels = loudChannels
                    }
                    if (tagger && tapRate != rate) {
                        // A new rate part-way (rare) starts the windows over at it: none is ever resampled from two rates.
                        tap = com.prism.music.analysis.TaggerTap(rate, durationUs / 1000).also { t -> mono.tap = t::sample }
                        tapRate = rate
                    }
                    mono.add(buf, frames, channels, isFloat, factor) { v ->
                        if (count == out.size) out = out.copyOf(out.size + out.size / 2)
                        out[count++] = v
                    }
                    dec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    if (count > MAX_SECONDS * (rate / factor)) break
                } else if (inputDone && o == MediaCodec.INFO_TRY_AGAIN_LATER && count > 0) {
                    break
                }
            }
            val sr = rate / factor
            if (count < sr * 20) return null
            // A mono file is heard on both speakers: one channel's power is 3 dB short of what plays.
            val lufs = meter?.loudness()?.let { if (channels == 1) it + 3f else it }
            return Decoded(
                out, count, sr, mono.peak, lufs,
                loudness = if (describe) meter?.stats()?.let { st ->
                    if (channels == 1) com.prism.music.analysis.LoudnessStats(
                        st.integrated?.plus(3f), st.lra, st.maxMomentary?.plus(3f),
                        FloatArray(st.momentary.size) { st.momentary[it] + 3f }, FloatArray(st.shortTerm.size) { st.shortTerm[it] + 3f },
                    ) else st
                } else null,
                truePeak = mono.truePeak,
                tagWindows = tap?.windows().orEmpty(),
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    companion object {
        private const val TAG = "PrismMix"
        /** DJ mode's quick scans running at once. */
        private const val QUICK_AT_ONCE = 3

        /**
         * How [LoudnessMeter] measures a song's loudness; entries measured by an older way are measured again. 1.8.5: every
         * channel (a mono fold-down with a flat +3 dB read a wide mix up to 3 dB quieter than it plays). 2 (1.1.3): a
         * measurement is a form's ([Loudness.formId]) — 1's may describe the version picked when it was scanned — so every
         * one is measured again when Normalize next wants it ([MixTempo.loudnessCurrent]'s gate).
         */
        internal const val LUFS_VERSION = 2

        /** A scan that failed (a file briefly gone, a read that went wrong) may be asked for again after this. */
        internal const val FAILED_RETRY_MS = 5 * 60_000L

    /** [normalizationReady], on its own figures so it can be read (and tested) without a phone. */
    internal fun normalizationReady(scanned: Boolean, lufs: Float?, current: Boolean, hasPeak: Boolean): Boolean = when {
        !scanned -> false
        // Scanned, and there was nothing to measure (silence, a fade): scanning again would say the same.
        lufs == null -> true
        else -> current && hasPeak
    }

        /** Whole scans running at once at most ([MixTempo.withScanSlot]): two, on the phones [MixTempo.mayScanTwice] allows. */
        private const val MAX_SCANS = 2

        /** Heap for two songs' samples at once (a song's mono is about 11 MB, its FFT working set as much again). */
        internal const val MIN_HEAP_FOR_TWO = 256L * 1024 * 1024

        /**
         * Free heap a second song needs beside the first: its samples and the key pass's spectra (a song of ten minutes is
         * about 30 MB each), with room for what a decoder holds between them.
         */
        internal const val MIN_FREE_FOR_TWO = 128L * 1024 * 1024

        /** How often a background walk looks whether the form being heard is waiting for its own scan ([measure]). */
        private const val PLAYING_SCAN_POLL_MS = 100L

        /** Room for a song of [lengthMs] at [rate], with a little over; a song whose length isn't known starts at 3 minutes. */
        internal fun decodedSize(lengthMs: Long, rate: Int): Int {
            val seconds = (if (lengthMs > 0) lengthMs / 1000 else 180L).coerceAtMost(MAX_SECONDS.toLong()) + 2
            return (seconds * rate).toInt().coerceAtLeast(1024)
        }

        /**
         * A song's scan from its decoded samples ([decode]; [onSamples] sees them too: the waveform's outline). The
         * samples (a song's are about 11 MB) are read in [hear] and let go when it returns: the steps after it (a wait of
         * its turn, Energy's brightness, the tempo) and the result hold only what was read from them (A16 heap dump,
         * 1.8.7.5). [step] is each analysis step waiting its turn ([ease]).
         */
        internal fun analyse(
            decode: () -> Decoded,
            onSamples: (pcm: FloatArray, count: Int, rate: Int) -> Unit = { _, _, _ -> },
            step: () -> Unit = ::ease,
            /** Read the sound analysis' descriptors too ([com.prism.music.analysis.Descriptors]), from the same samples. */
            describe: Boolean = false,
        ): Scan {
            val heard = hear(decode, onSamples, step, describe)
            step()
            // Energy's brightness from the same bands as the tempo (1.8.3; no extra pass).
            val brightness = runCatching { Energy.brightness(heard.bands) }.getOrNull()
            return Scan(heard.tempo, heard.lufs, heard.key, heard.peakDb, brightness, heard.features, heard.tagWindows)
        }

        /** What a scan reads straight from the samples, and none of the samples themselves. */
        private class Heard(
            val lufs: Float?, val bands: Array<FloatArray>, val frameMs: Double, val offsetMs: Double, val key: MusicKey?, val peakDb: Float?,
            val tempo: Tempo?, val features: com.prism.music.analysis.SongFeatures?, val tagWindows: List<FloatArray>,
        )

        /** The only frame that holds the decoded samples: they go when it returns. */
        private fun hear(decode: () -> Decoded, onSamples: (pcm: FloatArray, count: Int, rate: Int) -> Unit, step: () -> Unit, describe: Boolean = false): Heard {
            val decoded = decode()
            val pcm = decoded.pcm; val rate = decoded.rate; val count = decoded.count
            // Its loudness was measured as it decoded ([LoudnessMeter]): no second pass over the samples.
            val lufs = decoded.lufs
            onSamples(pcm, count, rate)
            // The analysis steps are seconds of work each on a slow phone: each waits its turn too ([ease]).
            step()
            // The bands and the key are independent passes over the same samples: the key runs on a spare thread while
            // the bands are worked out here, when the phone may spare it ([spreadBandKey]). Neither's figures depend on
            // the other, and the step between them is kept so a scan still gives way there.
            // The key from its chromagram (what KeyDetector.detect does), the chromagram kept for the sound analysis.
            val (bands: Array<FloatArray>, chroma: DoubleArray?) = spreadBandKey(
                bands = { VideoSync.bandFeatures(pcm, rate, count) },
                key = { runCatching { KeyDetector.chroma(pcm, rate, count) }.getOrNull() },
                step = step,
            )
            val key = chroma?.let { runCatching { KeyDetector.best(it) }.getOrNull() }
            val frameMs = (rate / 100) * 1000.0 / rate
            val offsetMs = VideoSync.WINDOW / 2 * 1000.0 / rate
            val peakDb = decoded.peak.takeIf { it > 0f }?.let { (20 * kotlin.math.log10(it.toDouble())).toFloat() }
            val tempo = Tempo.analyze(bands, frameMs, offsetMs = offsetMs)
            val features = if (!describe) null else runCatching {
                step()
                com.prism.music.analysis.Descriptors.extract(
                    com.prism.music.analysis.Descriptors.Input(
                        pcm, count, rate, bands, frameMs, tempo, key, chroma, decoded.loudness, peakDb,
                        decoded.truePeak.takeIf { it > 0f }?.let { (20 * kotlin.math.log10(it.toDouble())).toFloat() },
                    ),
                    step,
                )
            }.onFailure { if (it is ScansAbandoned) throw it; Log.w(TAG, "descriptors failed: $it") }.getOrNull()
            return Heard(lufs, bands, frameMs, offsetMs, key, peakDb, tempo, features, decoded.tagWindows)
        }

        /**
         * The bands here and the key on a spare thread, at once, when a scan may have the thread ([maySpread]); one
         * after the other otherwise. The two are the scan's own share of its time after decoding, so a walk the listener
         * started halves it; a phone with music playing, or one whose scans already make way, keeps to one
         * ([BackgroundBudget]) — there a second thread beside playback is what #373 was. [step] runs between them, as it
         * did when they were one after the other: the walk gives way to the form being heard at the same points.
         */
        private fun <B> spreadBandKey(bands: () -> Array<FloatArray>, key: () -> B, step: () -> Unit): Pair<Array<FloatArray>, B> {
            if (!maySpread()) {
                val b = bands()
                step()
                return b to key()
            }
            val ahead = java.util.concurrent.FutureTask(java.util.concurrent.Callable { key() })
            passes.execute(ahead)
            val b = bands()
            step()
            return try {
                b to ahead.get()
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            }
        }

        /**
         * The threads a scan's own passes run on ([spreadBandKey]): one a scan, two scans at once at most, background
         * priority, daemons, nothing kept between scans.
         */
        private val passes: java.util.concurrent.ExecutorService by lazy {
            val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)
            java.util.concurrent.Executors.newFixedThreadPool(threads) { r ->
                Thread(r, "prism-scan-pass").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
            }
        }

        /**
         * Whether a scan may run its key pass beside its bands: cores to spare, and only where the processor is there to
         * be had — on screen, or a walk the listener started. Never while music plays (a scan then has one core's worth,
         * [BackgroundBudget.share]) nor out of sight with nothing playing, and not on a phone whose scans make way for
         * touches (#343).
         */
        internal fun maySpread(
            cores: Int = Runtime.getRuntime().availableProcessors(),
            where: BackgroundBudget.Where = BackgroundBudget.where(),
            slow: Boolean = slowPhone(),
        ): Boolean = cores >= 6 && !slow &&
            (where == BackgroundBudget.Where.ON_SCREEN || where == BackgroundBudget.Where.WORKING)

        /**
         * Whether two songs may be measured at once ([MixTempo.mayScanTwice]): cores to spare, not a phone the scans
         * already make way on, not a low-RAM device, heap for two songs' samples, and only while there's a reason to
         * have the processor — on screen, or a walk the listener started.
         */
        internal fun twoAtOnce(
            cores: Int, where: BackgroundBudget.Where, slow: Boolean, lowRam: Boolean, freeHeap: Long, maxHeap: Long,
        ): Boolean = cores >= 6 && !slow && !lowRam && maxHeap >= MIN_HEAP_FOR_TWO && freeHeap >= MIN_FREE_FOR_TWO &&
            (where == BackgroundBudget.Where.ON_SCREEN || where == BackgroundBudget.Where.WORKING)

        /** When the sound last ran dry (SystemClock.elapsedRealtime; set by the playback service). */
        @Volatile var lastUnderrunAt = 0L

        /**
         * A scan's decoding steps aside while the sound has run dry in the last few seconds (GitHub #103: on a budget
         * phone, a song scan of a minute's work kept running while the output starved, again and again). Called from
         * the decode loops, off the main thread.
         */
        fun ease() {
            // A background walk's scan gives way here when the form being heard is waiting for its own (1.2.1): that
            // figure is the volume now, and it only lands in the form's first seconds ([ScanPriority], [measure]).
            if (scanPriority.abandonWalkScan()) throw ScansAbandoned()
            var waited = 0
            while (android.os.SystemClock.elapsedRealtime() - lastUnderrunAt < EASE_AFTER_UNDERRUN_MS && waited < MAX_EASE_MS) {
                Thread.sleep(250); waited += 250
            }
            // And on a phone with few cores, while the listener scrolls or taps (GitHub #343: a Redmi A3 lagged
            // scrolling playlists while the next songs were decoded beside it).
            waited = 0
            while (waited < MAX_TOUCH_WAIT_MS && stepsAside(slowPhone, touchDown, android.os.SystemClock.uptimeMillis() - touchedAt, urgentScans.get())) {
                Thread.sleep(100); waited += 100
            }
            // And within what the processor can spare out of sight (#373: Android closed Prism for it). A song's end waiting
            // for a scan doesn't wait for that.
            BackgroundBudget.pace { urgentScans.get() == 0 }
        }

        /**
         * Which of the songs up next get their whole-song scan: on screen, the playing one and those coming ([ahead]); out
         * of sight, only the very next song, whose volume is what's about to be heard (the rest wait until it's their
         * turn, and may never come: A16, 1.8.2: four songs scanned at once took a core and a half for a minute). The
         * playing song itself is only worth scanning out of sight for what reads it as it plays ([readsPlaying]: Crossfade
         * its ending, the waveform bar its outline), or for Normalize when it hasn't been measured yet
         * ([playingUnmeasured]): a first play's figure usually lands too late to use, but its next play then has it.
         */
        internal fun worthScanning(
            comingUp: List<String>, playing: String?, onScreen: Boolean, readsPlaying: Boolean, ahead: Int,
            playingUnmeasured: Boolean = false,
        ): List<String> {
            val coming = comingUp.filter { it != playing }.take(if (onScreen) ahead else 1)
            return if (playing != null && (onScreen || readsPlaying || playingUnmeasured)) listOf(playing) + coming else coming
        }

        /**
         * The order the forms found around the queue are scanned in (1.2.1). The form heard now comes first when its
         * volume is what the listen is waiting for — it has no figure yet ([playingNeedsFigure]) or the waveform bar is
         * drawn from its scan ([wave]) — and last otherwise (the songs after it are got ready while it plays, and
         * Crossfade reads its ending near the end). [forms] are distinct, as `scanAhead` makes them.
         *
         * Before, it was always last, so a first play's figure landed after up to three other songs had been scanned,
         * past [com.prism.music.playback.CATCHUP_MS]: a downloaded or streamed song started on YouTube's near-flat
         * figure until then, and a file on the phone (no YouTube figure at all) played at 0 dB for the whole listen.
         */
        internal fun scanOrder(forms: List<String>, playing: String?, playingNeedsFigure: Boolean, wave: Boolean): List<String> {
            if (playing == null) return forms
            fun rank(form: String) = when {
                form != playing -> 1
                wave || playingNeedsFigure -> 0
                else -> 2
            }
            return forms.sortedBy(::rank)
        }

        /** Whether this phone's scans make way for the listener's touches: few cores, little memory or Performance mode. */
        @Volatile var slowPhone: () -> Boolean = { false }
        @Volatile private var touchDown = false
        @Volatile private var touchedAt = -TOUCH_QUIET_MS
        /** Scans the song's end is waiting for (Crossfade near the end of a song): no scan steps aside meanwhile. */
        private val urgentScans = java.util.concurrent.atomic.AtomicInteger()

        /** Every touch on Prism's screen (MainActivity.dispatchTouchEvent): a volatile write or two, nothing more. */
        fun touched(action: Int) {
            touchedAt = android.os.SystemClock.uptimeMillis()
            when (action) {
                android.view.MotionEvent.ACTION_DOWN -> touchDown = true
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> touchDown = false
            }
        }

        /**
         * A scan waits while a finger is on the screen and for [TOUCH_QUIET_MS] after the last touch (a fling goes on
         * after the finger's lifted), on slow phones only, and never while a song's end is waiting for a scan.
         */
        internal fun stepsAside(slow: () -> Boolean, down: Boolean, sinceTouchMs: Long, urgent: Int): Boolean =
            (down || sinceTouchMs < TOUCH_QUIET_MS) && urgent == 0 && slow()

        /** Cores, memory and Performance mode, as [slowPhone] reads them. */
        internal fun isSlowPhone(cores: Int, mayBeSlow: Boolean, performanceMode: Boolean) = cores <= 4 || mayBeSlow || performanceMode

        /** [block] is a scan a song's end waits for: other scans stop stepping aside for touches until it's done. */
        internal suspend fun <T> urgently(block: suspend () -> T): T {
            urgentScans.incrementAndGet()
            try { return block() } finally { urgentScans.decrementAndGet() }
        }

        private const val TOUCH_QUIET_MS = 2_000L
        /** A minute at most per step: a scan still finishes while someone scrolls on and on. */
        private const val MAX_TOUCH_WAIT_MS = 60_000

        private const val EASE_AFTER_UNDERRUN_MS = 4_000L
        /** At most this long per step, so a scan still finishes on a phone that keeps running dry. */
        private const val MAX_EASE_MS = 4_000
        /** Longer than this (a DJ set, an hour-long mix) isn't measured past this point. */
        private const val MAX_SECONDS = 15 * 60

        /**
         * Integrated loudness after ITU-R BS.1770 / EBU R128 (what Spotify and YouTube normalize by), for one channel:
         * the signal K-weighted (a high shelf ~+4 dB above 1.7 kHz, a high-pass at 38 Hz), its power taken over 400 ms
         * blocks every 100 ms, and averaged over the blocks above -70 LUFS and no more than 10 LU below their mean (so
         * silence and quiet passages don't drag it down). Null for silence or under half a second. A whole song's
         * loudness is measured over all its channels by [LoudnessMeter] as it decodes.
         */
        fun integratedLoudness(pcm: FloatArray, rate: Int, count: Int = pcm.size): Float? {
            if (rate <= 0 || count < (0.5 * rate).toInt()) return null
            val meter = LoudnessMeter(rate, 1)
            for (i in 0 until count) meter.sample(pcm[i])
            return meter.loudness()
        }
    }
}

/** The most channels the loudness meter reads (7.1); a file with more is measured on its first eight. */
internal const val MAX_LOUDNESS_CHANNELS = 8

/**
 * ITU-R BS.1770 / EBU R128 integrated loudness, measured as a song decodes, over every channel: each channel is
 * K-weighted (a high shelf ~+4 dB above 1.7 kHz, a high-pass at 38 Hz) and its power summed, so a wide or hard-panned
 * mix reads as loud as it plays (folding it to mono first read it up to 3 dB quieter), in 400 ms blocks every 100 ms,
 * and averaged over the blocks above -70 LUFS and no more than 10 LU below their mean (so silence and quiet passages
 * don't drag it down).
 */
internal class LoudnessMeter(private val rate: Int, channels: Int) {
    val channels = channels.coerceAtLeast(1)

    /** BS.1770's weights: 1 for left, right and centre, 0 for LFE, +1.5 dB for the surrounds. */
    private val weights = DoubleArray(this.channels) { c ->
        when {
            c == 3 && this.channels >= 6 -> 0.0
            c >= 4 && this.channels >= 6 -> 1.41
            else -> 1.0
        }
    }
    private val shelf = biquadHighShelf(rate, 1681.974450955533, 3.999843853973347, 0.7071752369554196)
    private val pass = biquadHighPass(rate, 38.13547087602444, 0.5003270373238773)
    private val s1 = DoubleArray(this.channels)
    private val s2 = DoubleArray(this.channels)
    private val p1 = DoubleArray(this.channels)
    private val p2 = DoubleArray(this.channels)

    /** 100 ms hops; a block is four of them (400 ms), as BS.1770 measures. */
    private val hopFrames = (0.1 * rate).toInt().coerceAtLeast(1)
    private var hopDone = 0
    private var hopSum = 0.0
    private val hops = DoubleArray(HOPS)
    private var hopIndex = 0
    private var hopsFilled = 0

    private var powers = DoubleArray(INITIAL)
    private var blocks = 0

    /** The last 3 s of hops, for short-term loudness ([stats]). */
    private val stHops = DoubleArray(ST_HOPS)
    private var stIndex = 0
    private var stFilled = 0
    private var sinceShort = ST_EVERY - 1
    private var shortPowers = DoubleArray(INITIAL / 10)
    private var shorts = 0

    /**
     * The loudness figures the sound analysis reads beside [loudness] (no second pass): loudness range after EBU Tech
     * 3342 (short-term loudness gated at -70 LUFS and 20 LU under its mean, 10th to 95th percentile), the loudest
     * momentary loudness, and both curves.
     */
    fun stats(): com.prism.music.analysis.LoudnessStats {
        fun lufs(power: Double) = if (power > 0) (-0.691 + 10 * kotlin.math.log10(power)).toFloat() else Float.NEGATIVE_INFINITY
        val momentary = FloatArray(blocks) { lufs(powers[it]) }
        val shortTerm = FloatArray(shorts) { lufs(shortPowers[it]) }
        val gated = shortTerm.filter { it > -70f }
        val lra = if (gated.size < 3) null else {
            val mean = -0.691 + 10 * kotlin.math.log10(gated.sumOf { Math.pow(10.0, (it + 0.691) / 10) } / gated.size)
            val kept = gated.filter { it > mean - 20 }.sorted()
            if (kept.size < 3) null else kept[((kept.size - 1) * 0.95).toInt()] - kept[((kept.size - 1) * 0.10).toInt()]
        }
        return com.prism.music.analysis.LoudnessStats(
            integrated = loudness(),
            lra = lra,
            maxMomentary = momentary.filter { it.isFinite() }.maxOrNull(),
            momentary = momentary,
            shortTerm = shortTerm,
        )
    }

    /** One frame, a value per channel; each is a sum of [factor] samples when the sound was decimated. */
    fun frame(values: FloatArray, factor: Int = 1) {
        var power = 0.0
        for (c in 0 until channels) power += weights[c] * kWeighted(c, values[c] / factor)
        addPower(power)
    }

    /** One mono sample, for [MixTempo.integratedLoudness]. */
    fun sample(x: Float) {
        addPower(kWeighted(0, x))
    }

    /** The squared, K-weighted sample for channel [c] (direct form II transposed, one stage into the next). */
    private fun kWeighted(c: Int, x: Float): Double {
        val v = x.toDouble()
        val y1 = shelf[0] * v + s1[c]
        s1[c] = shelf[1] * v - shelf[3] * y1 + s2[c]
        s2[c] = shelf[2] * v - shelf[4] * y1
        val y2 = pass[0] * y1 + p1[c]
        p1[c] = pass[1] * y1 - pass[3] * y2 + p2[c]
        p2[c] = pass[2] * y1 - pass[4] * y2
        return y2 * y2
    }

    private fun addPower(power: Double) {
        hopSum += power
        if (++hopDone < hopFrames) return
        hops[hopIndex] = hopSum
        hopIndex = (hopIndex + 1) % HOPS
        hopsFilled = minOf(HOPS, hopsFilled + 1)
        hopSum = 0.0
        hopDone = 0
        // Short-term loudness (3 s) every second, for the loudness range and the sound analysis' dynamics.
        stHops[stIndex] = hops[(hopIndex + HOPS - 1) % HOPS]
        stIndex = (stIndex + 1) % ST_HOPS
        stFilled = minOf(ST_HOPS, stFilled + 1)
        if (stFilled == ST_HOPS && ++sinceShort >= ST_EVERY) {
            sinceShort = 0
            var s = 0.0
            for (h in 0 until ST_HOPS) s += stHops[h]
            if (shorts == shortPowers.size) shortPowers = shortPowers.copyOf(shortPowers.size * 2)
            shortPowers[shorts++] = s / (ST_HOPS * hopFrames)
        }
        if (hopsFilled < HOPS) return
        var sum = 0.0
        for (h in 0 until HOPS) sum += hops[h]
        if (blocks == powers.size) powers = powers.copyOf(powers.size * 2)
        // The four hops' energy over the four hops' samples: the block's mean square.
        powers[blocks++] = sum / (HOPS * hopFrames)
    }

    /** The integrated loudness (LUFS), or null for a song with nothing above the gate. */
    fun loudness(): Float? {
        if (blocks == 0) return null
        fun lufs(power: Double) = -0.691 + 10 * kotlin.math.log10(power)
        var loudSum = 0.0
        var loudCount = 0
        for (b in 0 until blocks) if (powers[b] > 0 && lufs(powers[b]) > -70) { loudSum += powers[b]; loudCount++ }
        if (loudCount == 0) return null
        val relative = lufs(loudSum / loudCount) - 10
        var gatedSum = 0.0
        var gatedCount = 0
        for (b in 0 until blocks) {
            val p = powers[b]
            if (p > 0 && lufs(p) > -70 && lufs(p) > relative) { gatedSum += p; gatedCount++ }
        }
        return lufs(if (gatedCount > 0) gatedSum / gatedCount else loudSum / loudCount).toFloat()
    }

    private companion object {
        /** 100 ms hops in a 400 ms block. */
        const val HOPS = 4
        /** 100 ms hops in a short-term (3 s) block, and how many hops apart the short-term values are taken (1 s). */
        const val ST_HOPS = 30
        const val ST_EVERY = 10
        /** Room for ten minutes of blocks (a scan reads fifteen at most). */
        const val INITIAL = 8192
    }
}

/** [b0, b1, b2, a1, a2] (a0 = 1) for a high shelf (RBJ's cookbook, as BS.1770's stage 1 at any sample rate). */
private fun biquadHighShelf(rate: Int, fc: Double, gainDb: Double, q: Double): DoubleArray {
    val a = Math.pow(10.0, gainDb / 40)
    val w = 2 * Math.PI * fc / rate
    val cw = kotlin.math.cos(w)
    val alpha = kotlin.math.sin(w) / (2 * q)
    val sa = 2 * kotlin.math.sqrt(a) * alpha
    val b0 = a * ((a + 1) + (a - 1) * cw + sa)
    val b1 = -2 * a * ((a - 1) + (a + 1) * cw)
    val b2 = a * ((a + 1) + (a - 1) * cw - sa)
    val a0 = (a + 1) - (a - 1) * cw + sa
    val a1 = 2 * ((a - 1) - (a + 1) * cw)
    val a2 = (a + 1) - (a - 1) * cw - sa
    return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
}

/** [b0, b1, b2, a1, a2] (a0 = 1) for a high-pass (BS.1770's stage 2, the "RLB" curve). */
private fun biquadHighPass(rate: Int, fc: Double, q: Double): DoubleArray {
    val w = 2 * Math.PI * fc / rate
    val cw = kotlin.math.cos(w)
    val alpha = kotlin.math.sin(w) / (2 * q)
    val a0 = 1 + alpha
    return doubleArrayOf((1 + cw) / 2 / a0, -(1 + cw) / a0, (1 + cw) / 2 / a0, -2 * cw / a0, (1 - alpha) / a0)
}

