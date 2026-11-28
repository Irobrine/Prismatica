package com.prism.music.analysis

import com.prism.music.playback.MusicKey
import com.prism.music.playback.Tempo
import com.prism.music.playback.sing.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What the song scan measured of its loudness over every channel, before normalization ([com.prism.music.playback.LoudnessMeter]):
 * the integrated loudness, its range, and the curves the dynamics are read from.
 *
 * @property momentary momentary loudness (400 ms), every 100 ms, LUFS (-inf as [Float.NEGATIVE_INFINITY] for silence).
 * @property shortTerm short-term loudness (3 s), every second, LUFS.
 */
class LoudnessStats(
    val integrated: Float?,
    val lra: Float?,
    val maxMomentary: Float?,
    val momentary: FloatArray,
    val shortTerm: FloatArray,
)

/**
 * Prism's own sound descriptors (after the algorithms Essentia documents: LoudnessEBUR128, DynamicComplexity,
 * RhythmExtractor2013's onset detection, SpectralCentroidTime, RollOff, Flatness, Flux, MFCC, Danceability, HPSS),
 * written in Kotlin so they run on any phone with no native code, read from the same decoded audio the song scan
 * already holds: the mono ~11 kHz signal, its 16 log bands every 10 ms, its tempo, chroma and loudness curves.
 * Nothing here decodes, resamples or reads a file.
 *
 * Cost: one 1024-point FFT per 93 ms of music (about 2,600 for a four-minute song), and a few passes over the band
 * energies; a fraction of the scan's own work.
 */
object Descriptors {
    /** Bumped whenever a figure is computed differently: songs analysed the old way are analysed again. */
    const val VERSION = 2

    /**
     * A rise this big (LU) over the three seconds before it is a sudden loud moment. 6 rather than 10: a compressed
     * master (most modern rock and metal) barely moves 10 LU, yet its chorus, break or scream wakes a sleeper anyway —
     * very song that scored 59 with "+6 LU, 0/min" is why.
     */
    const val SUDDEN_LU = 6f

    private const val N = 1024
    /** The smallest onset strength counted as a note or a hit ([onsetFrames]). */
    /** The smallest onset strength counted as a note or a hit ([onsetFrames]). */
    internal const val MIN_ONSET = 3.0

    /** [SongFeatures.brightShare]: energy above this counts as bright (MIRtoolbox's brightness cut-off). */
    private const val BRIGHT_HZ = 1500.0
    private const val MELS = 40
    private const val SEGMENT_S = 10.0

    class Input(
        val pcm: FloatArray,
        val count: Int,
        val rate: Int,
        /** [com.prism.music.playback.VideoSync.bandFeatures]: log energy per band, [frameMs] apart. */
        val bands: Array<FloatArray>,
        val frameMs: Double,
        val tempo: Tempo?,
        val key: MusicKey?,
        /** [com.prism.music.playback.KeyDetector.chroma] of the song. */
        val chroma: DoubleArray?,
        val loudness: LoudnessStats?,
        val peakDb: Float?,
        val truePeakDb: Float?,
    )

    fun extract(input: Input, step: () -> Unit = {}): SongFeatures {
        val rate = input.rate
        val count = input.count.coerceAtMost(input.pcm.size)
        val fps = 1000.0 / input.frameMs

        // Where the music is: the scan's own edges when it found them, else all of it.
        val firstS = input.tempo?.firstSoundMs?.takeIf { it > 0 }?.div(1000.0) ?: 0.0
        val lastS = input.tempo?.lastSoundMs?.takeIf { it > 0 }?.div(1000.0) ?: (count.toDouble() / rate)
        val seconds = (lastS - firstS).coerceAtLeast(0.0)
        val segments = max(1, (seconds / SEGMENT_S).toInt())

        // ---- Spectrogram pass (timbre, HPSS)
        val spec = spectral(input.pcm, count, rate, firstS, lastS, segments)
        step()

        // ---- Onsets and rhythm (from the scan's band energies)
        val od = onsetEnvelope(input.bands)
        val onsets = onsetFrames(od, input.bands, fps)
        val firstF = (firstS * fps).toInt()
        val lastF = min(od.size, (lastS * fps).toInt())
        val musicOnsets = onsets.count { it in firstF until lastF }
        val onsetRate = if (seconds > 0) (musicOnsets / seconds).toFloat() else 0f
        val segOnsets = List(segments) { s ->
            val a = firstF + (s * SEGMENT_S * fps).toInt()
            val b = if (s == segments - 1) lastF else firstF + ((s + 1) * SEGMENT_S * fps).toInt()
            val n = onsets.count { it in a until b }
            if (b > a) (n / ((b - a) / fps)).toFloat() else 0f
        }
        val percussive = percussiveShare(input.bands, firstF, lastF)
        val pulse = input.tempo?.let { pulseClarity(it.confidence) } ?: 0f
        val bpm = input.tempo?.bpm?.takeIf { it > 0 }
        val stability = if (bpm != null && pulse > 0.15f) tempoStability(od, fps, bpm, firstF, lastF) else null
        val dance = danceability(bpm, pulse, stability, percussive)
        step()

        // ---- Loudness and dynamics (every channel, before normalization)
        val loud = input.loudness
        val (sudden, worst) = loud?.let { suddenRises(it, seconds) } ?: (0f to 0f)
        val dynamicComplexity = loud?.let { dynamicComplexity(it) }
        val segLoud = loud?.let { segmentLoudness(it.shortTerm, firstS, segments) } ?: spec.segLoud

        val chroma = input.chroma?.let { c -> val s = c.sum(); if (s > 0) c.map { (it / s).toFloat() } else null }.orEmpty()
        val clarity = input.chroma?.let { (com.prism.music.playback.KeyDetector.clarity(it) / 0.6).coerceIn(0.0, 1.0).toFloat() }

        return SongFeatures(
            seconds = seconds.toFloat(),
            lufs = loud?.integrated,
            lra = loud?.lra,
            maxMomentary = loud?.maxMomentary,
            peakDb = input.peakDb,
            truePeakDb = input.truePeakDb,
            dynamicComplexity = dynamicComplexity,
            suddenPerMinute = sudden,
            worstRise = worst,
            bpm = bpm?.toFloat(),
            pulseClarity = pulse,
            tempoStability = stability,
            onsetRate = onsetRate,
            percussive = percussive,
            danceability = dance,
            centroidHz = spec.centroid,
            rolloffHz = spec.rolloff,
            flatness = spec.flatness,
            flux = spec.flux,
            zcr = zcr(input.pcm, count, rate, firstS, lastS),
            lowRatio = spec.lowRatio,
            brightShare = spec.brightShare,
            mfcc = spec.mfcc,
            mfccSd = spec.mfccSd,
            key = input.key?.takeIf { it.sure }?.encode(),
            tonalClarity = clarity,
            chroma = chroma,
            segLoud = segLoud,
            segBright = spec.segBright,
            segOnsets = segOnsets,
        )
    }

    /** [Tempo.confidence] 1.5 (hardly a beat) … 8 (a hard, steady one) as 0 … 1, as [com.prism.music.playback.Energy] reads it. */
    fun pulseClarity(confidence: Double): Float = ((confidence - 1.5) / 6.5).coerceIn(0.0, 1.0).toFloat()

    /** A clear, steady beat between a walk and a dance (90–135 BPM, read octave-free), with some drums. */
    fun danceability(bpm: Double?, pulse: Float, stability: Float?, percussive: Float): Float {
        if (bpm == null || pulse <= 0f) return 0f
        // The tempo's octave nearest the dance range.
        var b = bpm
        while (b < 80) b *= 2
        while (b > 160) b /= 2
        val fit = when {
            b in 90.0..135.0 -> 1.0
            b < 90 -> exp(-sq((90 - b) / 15))
            else -> exp(-sq((b - 135) / 20))
        }
        return (pulse * fit * (0.5 + 0.5 * (stability ?: 0.5f)) * (0.6 + 0.4 * percussive)).toFloat().coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------ spectral

    private class Spectral(
        val centroid: Float, val rolloff: Float, val flatness: Float, val flux: Float, val lowRatio: Float,
        val brightShare: Float, val mfcc: List<Float>, val mfccSd: List<Float>, val segBright: List<Float>, val segLoud: List<Float>,
    )

    private fun spectral(pcm: FloatArray, count: Int, rate: Int, firstS: Double, lastS: Double, segments: Int): Spectral {
        val from = (firstS * rate).toInt().coerceIn(0, count)
        val to = (lastS * rate).toInt().coerceIn(from, count)
        val frames = max(0, (to - from - N) / N + 1)
        val bins = N / 2 + 1
        val df = rate.toDouble() / N
        if (frames < 4) return Spectral(0f, 0f, 0f, 0f, 0f, 0f, emptyList(), emptyList(), emptyList(), emptyList())
        val brightBin = (BRIGHT_HZ / (rate.toDouble() / N)).toInt().coerceIn(1, N / 2)
        var bright = 0.0
        val fft = Fft(N)
        val window = DoubleArray(N) { 0.5 - 0.5 * cos(2 * PI * it / N) }
        val re = DoubleArray(N); val im = DoubleArray(N)
        val mel = melBank(rate, bins)
        val melSpec = Array(frames) { FloatArray(MELS) }
        val energy = DoubleArray(frames)
        val cent = DoubleArray(frames); val roll = DoubleArray(frames); val flat = DoubleArray(frames); val flux = DoubleArray(frames)
        var prev = DoubleArray(bins)
        var cur = DoubleArray(bins)
        val lowBin = (150 / df).toInt().coerceAtLeast(1)
        var low = 0.0; var all = 0.0
        for (f in 0 until frames) {
            val at = from + f * N
            for (i in 0 until N) { re[i] = pcm[at + i] * window[i]; im[i] = 0.0 }
            fft.transform(re, im)
            var e = 0.0; var weighted = 0.0; var logSum = 0.0; var fluxUp = 0.0; var mag = 0.0
            for (k in 1 until bins) {
                val p = re[k] * re[k] + im[k] * im[k]
                cur[k] = sqrt(p)
                e += p
                weighted += p * k * df
                logSum += ln(p + 1e-12)
                val d = cur[k] - prev[k]
                if (d > 0) fluxUp += d
                mag += cur[k]
                if (k < lowBin) low += p
                if (k >= brightBin) bright += p
            }
            all += e
            energy[f] = e
            cent[f] = if (e > 0) weighted / e else 0.0
            // Roll-off: where 85 % of the energy is reached.
            var acc = 0.0; var r = bins - 1
            for (k in 1 until bins) { acc += re[k] * re[k] + im[k] * im[k]; if (acc >= 0.85 * e) { r = k; break } }
            roll[f] = r * df
            val arith = e / (bins - 1)
            flat[f] = if (arith > 0) exp(logSum / (bins - 1)) / arith else 1.0
            flux[f] = if (mag > 0 && f > 0) fluxUp / mag else 0.0
            for (m in 0 until MELS) {
                var s = 0.0
                val w = mel[m]
                for (j in w.indices step 2) s += w[j + 1] * (cur[w[j].toInt()] * cur[w[j].toInt()])
                melSpec[f][m] = s.toFloat()
            }
            val t = prev; prev = cur; cur = t
        }
        // Frames that are music, not the silence between movements or a fade's last breath.
        val sorted = energy.sortedArray()
        val ref = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)]
        val audible = BooleanArray(frames) { energy[it] > ref * 3e-5 && energy[it] > 0 }
        fun meanOf(x: DoubleArray): Float {
            var s = 0.0; var n = 0
            for (f in 0 until frames) if (audible[f]) { s += x[f]; n++ }
            return if (n > 0) (s / n).toFloat() else 0f
        }
        // MFCC: log mel energies through a DCT-II, coefficients 1–13 (0 is loudness, which the loudness figures say).
        val coefs = 13
        val sums = DoubleArray(coefs); val sq = DoubleArray(coefs); var n = 0
        val logMel = DoubleArray(MELS)
        for (f in 0 until frames) {
            if (!audible[f]) continue
            for (m in 0 until MELS) logMel[m] = ln(melSpec[f][m] + 1e-10)
            for (c in 1..coefs) {
                var s = 0.0
                for (m in 0 until MELS) s += logMel[m] * cos(PI * c * (m + 0.5) / MELS)
                s *= sqrt(2.0 / MELS)
                sums[c - 1] += s; sq[c - 1] += s * s
            }
            n++
        }
        val mfcc = List(coefs) { if (n > 0) (sums[it] / n).toFloat() else 0f }
        val mfccSd = List(coefs) { if (n > 0) sqrt(max(0.0, sq[it] / n - sq(sums[it] / n))).toFloat() else 0f }
        val framesPerSeg = SEGMENT_S * rate / N
        val segBright = List(segments) { s ->
            val a = (s * framesPerSeg).toInt().coerceAtMost(frames)
            val b = if (s == segments - 1) frames else ((s + 1) * framesPerSeg).toInt().coerceAtMost(frames)
            var sum = 0.0; var k = 0
            for (f in a until b) if (audible[f]) { sum += cent[f]; k++ }
            if (k > 0) (sum / k).toFloat() else 0f
        }
        // A stand-in for the segments' loudness when the scan gave no loudness curve (mono power, dB, not K-weighted).
        val segLoud = List(segments) { s ->
            val a = (s * framesPerSeg).toInt().coerceAtMost(frames)
            val b = if (s == segments - 1) frames else ((s + 1) * framesPerSeg).toInt().coerceAtMost(frames)
            var sum = 0.0
            for (f in a until b) sum += energy[f]
            if (b > a && sum > 0) (10 * log10(sum / (b - a) / (N * N / 8.0))).toFloat() else -70f
        }
        return Spectral(
            centroid = meanOf(cent), rolloff = meanOf(roll), flatness = meanOf(flat), flux = meanOf(flux),
            lowRatio = if (all > 0) (low / all).toFloat() else 0f,
            brightShare = if (all > 0) (bright / all).toFloat() else 0f, mfcc = mfcc, mfccSd = mfccSd, segBright = segBright, segLoud = segLoud,
        )
    }

    /**
     * Triangular mel filters over the FFT's bins, 30 Hz to Nyquist; each filter as pairs (bin, weight) in one array, so
     * a frame's mel energies are a short loop over the bins each filter covers.
     */
    private fun melBank(rate: Int, bins: Int): Array<DoubleArray> {
        fun toMel(f: Double) = 2595 * log10(1 + f / 700)
        fun fromMel(m: Double) = 700 * (Math.pow(10.0, m / 2595) - 1)
        val lo = toMel(30.0); val hi = toMel(rate / 2.0)
        val edges = DoubleArray(MELS + 2) { fromMel(lo + (hi - lo) * it / (MELS + 1)) }
        val df = rate.toDouble() / N
        return Array(MELS) { m ->
            val a = edges[m]; val c = edges[m + 1]; val b = edges[m + 2]
            val pairs = ArrayList<Double>()
            for (k in 1 until bins) {
                val f = k * df
                val w = when {
                    f <= a || f >= b -> 0.0
                    f <= c -> (f - a) / (c - a)
                    else -> (b - f) / (b - c)
                }
                if (w > 0) { pairs += k.toDouble(); pairs += w }
            }
            // A filter narrower than a bin still sees its nearest bin.
            if (pairs.isEmpty()) { pairs += (c / df).toInt().coerceIn(1, bins - 1).toDouble(); pairs += 1.0 }
            pairs.toDoubleArray()
        }
    }

    /**
     * The percussive share of the sound by median-filtering harmonic/percussive separation (Fitzgerald 2010) on the
     * scan's 16 bands every 10 ms: a sustained note is smooth along time (a median over 170 ms), a drum hit along
     * frequency (over 5 bands). Soft (Wiener) masks, over the music [firstF]..[lastF] (every other frame); 0 … 1.
     */
    internal fun percussiveShare(bands: Array<FloatArray>, firstF: Int, lastF: Int): Float {
        val nb = bands.size
        val frames = bands.firstOrNull()?.size ?: return 0f
        val from = firstF.coerceIn(0, frames); val to = lastF.coerceIn(from, frames)
        if (to - from < 20 || nb < 3) return 0f
        val tHalf = 8; val fHalf = 2
        val buf = FloatArray(2 * tHalf + 1)
        // Linear power, once per band (the bands are natural-log energies).
        val power = Array(nb) { b -> FloatArray(to - from) { exp(bands[b][from + it].toDouble()).toFloat() } }
        val n = to - from
        var perc = 0.0; var total = 0.0
        var t = 0
        while (t < n) {
            for (b in 0 until nb) {
                var k = 0
                for (u in max(0, t - tHalf)..min(n - 1, t + tHalf)) buf[k++] = power[b][u]
                val h = median(buf, k)
                k = 0
                for (q in max(0, b - fHalf)..min(nb - 1, b + fHalf)) buf[k++] = power[q][t]
                val p = median(buf, k)
                val s = power[b][t].toDouble()
                val h2 = h.toDouble() * h; val p2 = p.toDouble() * p
                if (h2 + p2 <= 0) continue
                perc += s * p2 / (h2 + p2)
                total += s
            }
            t += 2
        }
        return if (total > 0) (perc / total).toFloat() else 0f
    }

    private fun median(x: FloatArray, n: Int): Float {
        // Insertion sort: the windows are at most 17 long.
        for (i in 1 until n) { val v = x[i]; var j = i - 1; while (j >= 0 && x[j] > v) { x[j + 1] = x[j]; j-- }; x[j + 1] = v }
        return if (n % 2 == 1) x[n / 2] else (x[n / 2 - 1] + x[n / 2]) / 2
    }

    private fun zcr(pcm: FloatArray, count: Int, rate: Int, firstS: Double, lastS: Double): Float {
        val a = (firstS * rate).toInt().coerceIn(1, count)
        val b = (lastS * rate).toInt().coerceIn(a, count)
        if (b - a < 2) return 0f
        var z = 0
        for (i in a until b) if ((pcm[i] >= 0) != (pcm[i - 1] >= 0)) z++
        return z.toFloat() / (b - a)
    }

    // ------------------------------------------------------------------ rhythm

    /** Onset strength every band frame: summed rises in band energy, minus its local average (±200 ms), kept positive. */
    internal fun onsetEnvelope(bands: Array<FloatArray>): FloatArray {
        val frames = bands.firstOrNull()?.size ?: return FloatArray(0)
        if (frames == 0) return FloatArray(0)
        // Each band's energy is floored 35 dB (8 nats) under the music's loud frames: a nearly empty band's log energy
        // flickers by whole units from frame to frame (a sustained tone's leakage), which isn't a note starting.
        val top = FloatArray(frames) { t -> var m = Float.NEGATIVE_INFINITY; for (b in bands) m = max(m, b[t]); m }
        val floor = top.sortedArray()[(frames * 0.95).toInt().coerceAtMost(frames - 1)] - 8f
        val flux = FloatArray(frames)
        for (t in 1 until frames) {
            var s = 0f
            for (b in bands) { val d = max(b[t], floor) - max(b[t - 1], floor); if (d > 0) s += d }
            flux[t] = s
        }
        val half = 20
        val pre = DoubleArray(frames + 1).also { for (i in 0 until frames) it[i + 1] = it[i] + flux[i] }
        return FloatArray(frames) { t ->
            val lo = max(0, t - half); val hi = min(frames, t + half + 1)
            max(0.0, flux[t] - (pre[hi] - pre[lo]) / (hi - lo)).toFloat()
        }
    }

    /**
     * Onsets: peaks of [od] that are the highest within ±30 ms, stand above 1.5× the music's mean onset strength, and
     * come at least 50 ms after the last one, in frames that aren't silence.
     */
    internal fun onsetFrames(od: FloatArray, bands: Array<FloatArray>, fps: Double): List<Int> {
        val frames = od.size
        if (frames < 3) return emptyList()
        val loud = FloatArray(frames) { t -> var m = Float.NEGATIVE_INFINITY; for (b in bands) m = max(m, b[t]); m }
        val peak = loud.sortedArray()[(frames * 0.95).toInt().coerceAtMost(frames - 1)]
        var sum = 0.0; var sq = 0.0; var n = 0
        for (t in 0 until frames) if (loud[t] > peak - 9.2f) { sum += od[t]; sq += od[t].toDouble() * od[t]; n++ }
        val mean = if (n > 0) sum / n else 0.0
        val sd = if (n > 0) kotlin.math.sqrt(max(0.0, sq / n - mean * mean)) else 0.0
        // Well above the music's own onset strength (its mean and twice its spread), and an absolute rise too (summed over
        // the bands, natural-log energy: [MIN_ONSET] is about 13 dB in one band or a few dB across several), so a texture's
        // frame-to-frame shimmer is never counted as notes. (A threshold taken from each second locally was tried and
        // misread both ways — 0.7 kicks a second in rock, 4 in ambient texture — so the whole song's own figures stand.)
        val threshold = max(mean + 2 * sd, MIN_ONSET)
        val reach = max(1, (0.05 * fps).toInt())
        val gap = max(1, (0.08 * fps).toInt())
        val out = ArrayList<Int>()
        var last = -gap
        for (t in 0 until frames) {
            val v = od[t]
            if (v < threshold || loud[t] <= peak - 9.2f || t - last < gap) continue
            var top = true
            for (u in max(0, t - reach)..min(frames - 1, t + reach)) if (od[u] > v) { top = false; break }
            if (top) { out += t; last = t }
        }
        return out
    }

    /**
     * How steady the tempo is: the share of 20-second stretches (10 s apart) whose own strongest beat period agrees
     * with the song's tempo within 4 %, reading half and double time as the same tempo.
     */
    internal fun tempoStability(od: FloatArray, fps: Double, bpm: Double, firstF: Int, lastF: Int): Float? {
        val win = (20 * fps).toInt()
        val hop = (10 * fps).toInt()
        val lagMin = (fps * 60 / 200).toInt().coerceAtLeast(1)
        val lagMax = (fps * 60 / 50).toInt()
        if (lastF - firstF < win) return null
        var agree = 0; var total = 0
        var a = firstF
        while (a + win <= lastF) {
            var best = -1.0; var bestLag = lagMin
            for (lag in lagMin..lagMax) {
                var s = 0.0
                for (t in a until a + win - lag) s += od[t].toDouble() * od[t + lag]
                if (s > best) { best = s; bestLag = lag }
            }
            if (best > 0) {
                val local = 60 * fps / bestLag
                val off = listOf(0.5, 1.0, 2.0).minOf { k -> abs(ln(local * k / bpm)) }
                total++
                if (off < ln(1.04)) agree++
            }
            a += hop
        }
        return if (total == 0) null else agree.toFloat() / total
    }

    // ------------------------------------------------------------------ loudness

    /**
     * Sudden loud moments per minute and the biggest rise: momentary loudness that jumps [SUDDEN_LU] or more over the
     * three seconds before it (their average power), up to a level near the song's own (no more than 12 LU under its
     * integrated loudness). Rises within two seconds of one another are one moment.
     */
    internal fun suddenRises(loud: LoudnessStats, seconds: Double): Pair<Float, Float> {
        val m = loud.momentary
        val integ = loud.integrated ?: return 0f to 0f
        if (m.size < 40) return 0f to 0f
        // Linear power of each momentary value, for averages that aren't dragged down by silence's -inf.
        val p = DoubleArray(m.size) { if (m[it].isFinite()) Math.pow(10.0, (m[it] + 0.691) / 10) else 0.0 }
        val pre = DoubleArray(p.size + 1).also { for (i in p.indices) it[i + 1] = it[i] + p[i] }
        // Momentary values every 100 ms; the first is a full 400 ms block, so a song's first sound counts from there.
        val start = m.indexOfFirst { it.isFinite() && it > integ - 30 }.takeIf { it >= 0 } ?: return 0f to 0f
        var events = 0
        var lastEvent = -100
        var worst = 0f
        for (t in max(start + 30, 30) until m.size) {
            if (!m[t].isFinite() || m[t] < integ - 20) continue
            // Measured over half a second (the last five 100 ms blocks), not three: a chorus, a scream or a break lands at
            // once, while an ambient pad's slow swell takes seconds and isn't a jolt. Only out of music, and only to near
            // the song's own level.
            var audible = 0
            for (u in t - 30 until t - 5) if (m[u].isFinite() && m[u] > integ - 30f) audible++
            if (audible < 10) continue
            val before = (pre[t] - pre[t - 5]) / 5
            val base = if (before > 0) (-0.691 + 10 * log10(before)).toFloat() else integ - 40f
            val rise = m[t] - base
            if (rise > worst) worst = rise
            if (rise >= SUDDEN_LU && m[t] >= integ - 6f && t - lastEvent > 20) { events++; lastEvent = t }
        }
        val minutes = (seconds / 60).coerceAtLeast(0.5)
        return (events / minutes).toFloat() to worst.coerceAtLeast(0f)
    }

    /** The short-term loudness' average distance from the integrated loudness (LU), over what isn't near-silence. */
    internal fun dynamicComplexity(loud: LoudnessStats): Float? {
        val integ = loud.integrated ?: return null
        var s = 0.0; var n = 0
        for (v in loud.shortTerm) if (v.isFinite() && v > integ - 20) { s += abs(v - integ); n++ }
        return if (n > 0) (s / n).toFloat() else null
    }

    private fun segmentLoudness(shortTerm: FloatArray, firstS: Double, segments: Int): List<Float> {
        // shortTerm[i] is the 3 s ending at second i + 3 (the meter's first full block): close enough to the segment's.
        val from = firstS.toInt()
        return List(segments) { s ->
            val a = (from + s * SEGMENT_S.toInt()).coerceAtMost(shortTerm.size)
            val b = (from + (s + 1) * SEGMENT_S.toInt()).coerceAtMost(shortTerm.size)
            var sum = 0.0; var k = 0
            for (i in a until b) if (shortTerm[i].isFinite()) { sum += Math.pow(10.0, (shortTerm[i] + 0.691) / 10); k++ }
            if (k > 0 && sum > 0) (-0.691 + 10 * log10(sum / k)).toFloat() else -70f
        }
    }

    private fun sq(x: Double) = x * x
}
