package com.prism.music.data.sleep

import com.prism.music.playback.sing.Fft
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How suitable a recording's sound is for sleep, worked out from its own samples (1.9).
 *
 * The analysis reads the decoded mono audio in ~46 ms frames (a Hann-windowed FFT each), measures quietness, level
 * changes, transients, onsets, spectral activity and brightness, and then judges the song in ten-second sections so a
 * calm intro can never speak for a loud chorus. The published figure is conservative by construction:
 *
 *  - the sections are read for their worst, not their average (a quiet average still hides a chorus),
 *  - hard caps apply to severe events anywhere in the song (a sudden slam, a clipped peak, a rise into a loud part),
 *  - the worst section sets a ceiling, and
 *  - a low confidence (little audio, mostly silence) lowers the figure a little on top of that.
 *
 * Nothing depends on genre, title, artist or any online information: the same samples always produce the same figure
 * for a given [MODEL_VERSION]. It is a heuristic, not a probability; a model's similarity is not a likelihood, and the
 * components are kept apart so the formula can be retuned without pretending the old figures were certainties.
 *
 * The audio-model part ([SleepComponents.model]) is a fixed-weight model over the same measurements. It is deliberately
 * small and deterministic (no download, no inference runtime); a bundled neural audio tagger can replace it behind the
 * [MODEL_VERSION] gate without changing the stored format, since a new version recalculates everything.
 *
 * The samples are read as they are decoded ([Analyzer.feed]): a ten-minute song never has to sit in memory as a whole,
 * only its per-frame measurements do (under a megabyte for a ten-minute song), which is what lets a mid-range phone score
a library.
 */
object SleepAnalysis {
    /**
     * What the figures mean. Any change to the formula, the features or the caps needs a new version: stored figures
     * say which one made them, and the walk recalculates the ones that are older.
     */
    const val MODEL_VERSION = 1

    /** The shortest audio worth judging; anything shorter is scored cautiously and with little confidence. */
    const val MIN_CONFIDENT_MS = 60_000L

    /** Ten seconds: long enough to hold a chorus line or a drum pattern, short enough that a calm verse can't hide one. */
    private const val SECTION_MS = 10_000L

    /** Frame length: ~46 ms, so a kick or a hat is a handful of frames rather than a smear. */
    private const val WINDOW_SECONDS = 0.046

    /** A frame quieter than this is silence, not a quiet passage. */
    private const val SILENCE_DB = -58.0

    /** A sample this close to full scale counts as clipped. */
    private const val CLIP_LEVEL = 0.995

    /**
     * Analyses [count] samples of mono audio at [rate] (Hz) and returns the score, its component parts, its confidence
     * and where the song misbehaves. [expectedMs] is the length the song should have (its known duration) when that is
     * known: a decode that stops well short of it is judged with less confidence, never more.
     *
     * Deterministic: the same samples, rate, version and [analyzedAtMs] give the same figure.
     */
    fun analyze(pcm: FloatArray, count: Int, rate: Int, analyzedAtMs: Long, expectedMs: Long = 0L): SleepScore {
        val analyzer = Analyzer(rate, expectedMs)
        var at = 0
        val n = count.coerceIn(0, pcm.size)
        while (at < n) {
            val step = min(n - at, 16_384)
            analyzer.feed(pcm, at, step)
            at += step
        }
        return analyzer.finish(analyzedAtMs)
    }

    /**
     * One song's analysis, fed its samples as they are decoded. [rate] is their sample rate (mono), [expectedMs] the
     * length the song is known to have (0 when unknown). [feed] may be called any number of times; [finish] once.
     */
    class Analyzer(private val rate: Int, private val expectedMs: Long = 0L) {
        private val size = nextPowerOfTwo((rate * WINDOW_SECONDS).roundToInt().coerceAtLeast(256))
        private val hop = size / 2
        private val fft = Fft(size)
        private val re = DoubleArray(size)
        private val im = DoubleArray(size)
        private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2.0 * Math.PI * it / (size - 1)) }
        private val mag = DoubleArray(size / 2)
        private val previous = DoubleArray(size / 2)
        private val binHz = if (rate > 0) rate.toDouble() / size else 1.0
        private val pending = FloatArray(size)
        private var pendingCount = 0

        private val rmsDb = Growable()
        private val peakDb = Growable()
        private val centroid = Growable()
        private val flatness = Growable()
        private val flux = Growable()
        private val lowRatio = Growable()
        private val highRatio = Growable()

        /** How many samples were fed, for the duration. */
        private var fed = 0L
        private var clipped = 0L
        private var finished = false

        /** Adds [count] mono samples from [samples] starting at [offset]. */
        fun feed(samples: FloatArray, offset: Int = 0, count: Int = samples.size - offset) {
            if (rate <= 0 || finished) return
            var i = 0
            while (i < count) {
                val take = min(size - pendingCount, count - i)
                for (k in 0 until take) {
                    val v = samples[offset + i + k]
                    if (abs(v) >= CLIP_LEVEL) clipped++
                    pending[pendingCount + k] = v
                }
                pendingCount += take
                i += take
                fed += take
                if (pendingCount == size) {
                    frame()
                    // Half-overlapping frames: the next one starts a hop on.
                    System.arraycopy(pending, hop, pending, 0, size - hop)
                    pendingCount -= hop
                }
            }
        }

        private fun frame() {
            var sumSq = 0.0
            var peak = 0.0
            for (i in 0 until size) {
                val v = pending[i].toDouble()
                sumSq += v * v
                if (abs(v) > peak) peak = abs(v)
                re[i] = v * window[i]
                im[i] = 0.0
            }
            val level = 20.0 * log10(sqrt(sumSq / size).coerceAtLeast(1e-7))
            rmsDb.add(level)
            peakDb.add(20.0 * log10(peak.coerceAtLeast(1e-7)))
            // A silent frame's spectrum is the window's own leakage, not the music: it isn't "noisy" or "bright", and
            // counting it as either would punish the rests a gentle piece is made of.
            val quietFrame = level < SILENCE_DB
            fft.transform(re, im)

            var total = 0.0
            var weighted = 0.0
            var logPower = 0.0
            var power = 0.0
            var lowEnergy = 0.0
            var highEnergy = 0.0
            for (k in 0 until size / 2) {
                val m = sqrt(re[k] * re[k] + im[k] * im[k])
                mag[k] = m
                val hz = k * binHz
                total += m
                weighted += m * hz
                val p = m * m + 1e-12
                power += p
                logPower += ln(p)
                if (hz < 250.0) lowEnergy += m
                if (hz > 4000.0) highEnergy += m
            }
            centroid.add(if (!quietFrame && total > 0) weighted / total else 0.0)
            flatness.add(
                if (!quietFrame) exp(logPower / (size / 2)) / (power / (size / 2)) else 0.0,
            )
            lowRatio.add(if (!quietFrame && total > 0) lowEnergy / total else 0.0)
            highRatio.add(if (!quietFrame && total > 0) highEnergy / total else 0.0)
            var rise = 0.0
            if (rmsDb.n > 1) for (k in 0 until size / 2) {
                val d = mag[k] - previous[k]
                if (d > 0) rise += d
            }
            // A frame with no sound in it has no attack either: its "flux" would be the leakage of the window divided by
            // itself, which reads as a burst of onsets in every rest.
            flux.add(if (!quietFrame && rmsDb.n > 1 && total > 0) rise / total else 0.0)
            System.arraycopy(mag, 0, previous, 0, size / 2)
        }

        /** The score once every sample has been fed. Further [feed]s are ignored. */
        fun finish(analyzedAtMs: Long): SleepScore {
            finished = true
            val durationMs = if (rate > 0) fed * 1000L / rate else 0L
            if (rmsDb.n < 8) return silent(durationMs, analyzedAtMs)
            val frames = Frames(
                rmsDb.toArray(), peakDb.toArray(), centroid.toArray(), flatness.toArray(), flux.toArray(),
                lowRatio.toArray(), highRatio.toArray(), rmsDb.n, hop.toDouble() / rate, durationMs,
            )
            val baseline = blockBaseline(frames.rmsDb, frames.frameSeconds)
            val fluxBaseline = blockBaseline(frames.flux, frames.frameSeconds)
            val onsets = onsets(frames, fluxBaseline)
            val transients = transients(frames, baseline, onsets)
            val jumped = suddenEvents(frames, baseline)
            val clip = if (fed > 0) clipped.toDouble() / fed else 0.0
            // Nothing audible anywhere: a decode that lost its audio, or a silent file. There is no evidence of
            // suitability in it, so it gets no credit (the UI shows it as unjudgeable, not as a lullaby).
            var audible = 0
            for (i in 0 until frames.n) if (frames.rmsDb[i] >= SILENCE_DB) audible++
            if (audible.toDouble() / frames.n < 0.1) return silent(durationMs, analyzedAtMs)
            val sections = sections(frames, onsets, transients)
            if (sections.isEmpty()) return silent(durationMs, analyzedAtMs)

            val warnings = ArrayList<SleepWarning>()
            // Loud passages: a section ten decibels into the red is disruptive whatever else it does.
            for (s in sections) {
                val severity = when {
                    s.levelDb >= -10.0 -> 3
                    s.levelDb >= -14.0 -> 2
                    s.levelDb >= -18.0 -> 1
                    else -> 0
                }
                if (severity > 0) {
                    warnings += SleepWarning(SleepWarningKind.LOUD, s.startMs, s.endMs, severity)
                }
            }
            for (e in jumped) {
                val severity = when {
                    e.strengthDb >= 12.0 -> 3
                    e.strengthDb >= 9.0 -> 2
                    else -> 1
                }
                warnings += SleepWarning(SleepWarningKind.SUDDEN_LOUD, e.startMs, e.endMs, severity)
            }
            for ((index, s) in sections.withIndex()) {
                // A rise across the section, or a rise the whole song makes into it (both are crescendos to a sleeper).
                // The opening section's fade-in is not one: a track that starts quietly and settles is not building to
                // anything, and neither is a section that stays near silence all the way through.
                if (index == 0 || s.levelDb < -32.0) continue
                val rise = s.levelDb - s.levelFirstDb
                val severity = when {
                    s.slopeDbPerSec >= 0.9 && rise >= 8.0 -> 3
                    (s.slopeDbPerSec >= 0.9 && rise >= 5.0) || rise >= 8.0 -> 2
                    rise >= 5.0 || s.slopeDbPerSec >= 0.6 -> 1
                    else -> 0
                }
                if (severity > 0) {
                    warnings += SleepWarning(SleepWarningKind.CRESCENDO, s.startMs, s.endMs, severity)
                }
            }
            for (i in 1 until sections.size) {
                val change = abs(sections[i].levelDb - sections[i - 1].levelDb)
                if (change >= 8.0) {
                    val severity = if (change >= 13.0) 3 else 2
                    warnings += SleepWarning(SleepWarningKind.ABRUPT_CHANGE, sections[i].startMs, sections[i].endMs, severity)
                }
            }
            if (clip >= 0.02) warnings += SleepWarning(SleepWarningKind.CLIPPING, 0, durationMs, 3)
            else if (clip >= 0.002) warnings += SleepWarning(SleepWarningKind.CLIPPING, 0, durationMs, 2)
            for (s in sections) {
                if (s.score < 30) warnings += SleepWarning(SleepWarningKind.ENERGETIC, s.startMs, s.endMs, if (s.score < 15) 3 else 2)
            }

            val confidence = confidence(frames, sections, durationMs, expectedMs)
            return judge(frames, sections, transients, jumped, warnings.sortedBy { it.startMs }, confidence, durationMs, analyzedAtMs)
        }

        private fun silent(durationMs: Long, analyzedAtMs: Long): SleepScore = SleepScore(
            score = 0,
            confidence = 5,
            components = SleepComponents(0, 0, 0, 0, 0, 0, 0, 0),
            warnings = listOf(SleepWarning(SleepWarningKind.UNSTABLE, 0, durationMs, 3)),
            modelVersion = MODEL_VERSION,
            durationMs = durationMs,
            analyzedAtMs = analyzedAtMs,
        )
    }

    // ---------------------------------------------------------------- frames

    /** A growable primitive array: the frames of a whole song are not known in advance when it is still decoding. */
    private class Growable {
        var data = DoubleArray(1024)
            private set
        var n = 0
            private set

        fun add(v: Double) {
            if (n == data.size) data = data.copyOf(data.size * 2)
            data[n++] = v
        }

        fun toArray(): DoubleArray = data.copyOf(n)
    }

    /** The per-frame measurements, all parallel arrays of the same length. */
    private class Frames(
        val rmsDb: DoubleArray,
        val peakDb: DoubleArray,
        val centroid: DoubleArray,
        val flatness: DoubleArray,
        val flux: DoubleArray,
        val lowRatio: DoubleArray,
        val highRatio: DoubleArray,
        val n: Int,
        val frameSeconds: Double,
        val durationMs: Long,
    )

    // ---------------------------------------------------------------- events

    /** A per-frame baseline of what came before it: the median of the previous three one-second blocks. */
    private fun blockBaseline(values: DoubleArray, frameSeconds: Double): DoubleArray {
        val perBlock = max(1, (1.0 / frameSeconds).roundToInt())
        val blocks = (values.size + perBlock - 1) / perBlock
        val medians = DoubleArray(blocks)
        for (b in 0 until blocks) {
            val from = b * perBlock
            val to = min(values.size, from + perBlock)
            medians[b] = percentile(values, from, to, 0.5)
        }
        val out = DoubleArray(values.size)
        for (i in values.indices) {
            val b = i / perBlock
            out[i] = percentile(medians, max(0, b - 3), b, 0.5)
        }
        return out
    }

    /** Frames where the spectrum jumps: an attack, kept as onsets for density and beat. */
    private fun onsets(frames: Frames, baseline: DoubleArray): BooleanArray {
        val out = BooleanArray(frames.n)
        for (i in 1 until frames.n) {
            val v = frames.flux[i]
            if (v < 0.035) continue
            if (v < max(0.02, baseline[i]) * 2.0) continue
            var isPeak = true
            for (j in max(0, i - 2)..min(frames.n - 1, i + 2)) {
                if (j != i && frames.flux[j] > v) { isPeak = false; break }
            }
            if (isPeak) out[i] = true
        }
        return out
    }

    /** Onsets strong enough to be a hit: the frame's level stands well above the passage before it. */
    private fun transients(frames: Frames, baseline: DoubleArray, onsets: BooleanArray): List<Transient> {
        val out = ArrayList<Transient>()
        var last = -10
        for (i in 1 until frames.n) {
            if (!onsets[i]) continue
            val rise = frames.rmsDb[i] - baseline[i]
            val crack = frames.peakDb[i] - baseline[i]
            val strength = max(rise, crack - 6.0)
            // A hit has both a spectral jump and a level jump; a quiet cymbal swell is neither.
            if (strength < 4.5 || (rise < 2.5 && crack < 10.0)) continue
            val transient = Transient(i, strength, frames, strength >= 9.0)
            if (i - last <= 3 && out.isNotEmpty()) {
                // The attack and its tail: keep one hit, the stronger.
                if (transient.strengthDb > out[out.size - 1].strengthDb) out[out.size - 1] = transient
            } else {
                out += transient
            }
            last = i
        }
        return out
    }

    private class Transient(val frame: Int, val strengthDb: Double, frames: Frames, val strong: Boolean) {
        val startMs: Long = (frame * frames.frameSeconds * 1000.0).toLong()
    }

    /** Runs where the level jumps sharply above what came before (a slam, a drop, a sudden loud entry). */
    private fun suddenEvents(frames: Frames, baseline: DoubleArray): List<Event> {
        val out = ArrayList<Event>()
        var i = 1
        val minFrames = max(1, (0.15 / frames.frameSeconds).roundToInt())
        while (i < frames.n) {
            val rise = frames.rmsDb[i] - baseline[i]
            if (rise < 8.0) { i++; continue }
            var end = i
            var peak = rise
            while (end + 1 < frames.n && frames.rmsDb[end + 1] - baseline[end + 1] >= 6.0) {
                end++
                peak = max(peak, frames.rmsDb[end] - baseline[end])
            }
            if (end - i + 1 >= minFrames) {
                val startMs = (i * frames.frameSeconds * 1000.0).toLong()
                val endMs = ((end + 1) * frames.frameSeconds * 1000.0).toLong()
                if (out.none { it.startMs >= startMs - 1_000 && it.startMs <= endMs + 1_000 }) {
                    out += Event(startMs, endMs, peak)
                }
            }
            i = end + 1
        }
        return out
    }

    private class Event(val startMs: Long, val endMs: Long, val strengthDb: Double)

    // ---------------------------------------------------------------- sections

    /** One ten-second stretch of the song, measured on its own. */
    private class Section(
        val startMs: Long,
        val endMs: Long,
        val levelDb: Double,
        val levelFirstDb: Double,
        val levelRangeDb: Double,
        val microDb: Double,
        val centroid: Double,
        val flatness: Double,
        val flux: Double,
        val lowRatio: Double,
        val highRatio: Double,
        val onsetsPerSec: Double,
        val beat: Double,
        val transientPerMin: Double,
        val strongestTransientDb: Double,
        val slopeDbPerSec: Double,
        val crest: Double,
        var score: Double,
    )

    private fun sections(
        frames: Frames, onsets: BooleanArray, transients: List<Transient>,
    ): List<Section> {
        val perSection = max(1, (SECTION_MS / 1000.0 / frames.frameSeconds).roundToInt())
        val count = (frames.n + perSection - 1) / perSection
        val out = ArrayList<Section>(count)
        for (s in 0 until count) {
            val from = s * perSection
            val to = min(frames.n, from + perSection)
            if (to - from < max(4, perSection / 4)) continue
            val seconds = (to - from) * frames.frameSeconds
            var onsetsIn = 0
            for (i in from until to) if (onsets[i]) onsetsIn++
            val inside = transients.filter { it.frame in from until to }
            val strongest = inside.maxOfOrNull { it.strengthDb } ?: 0.0
            val level = percentile(frames.rmsDb, from, to, 0.5)
            val section = Section(
                startMs = (from * frames.frameSeconds * 1000.0).toLong(),
                endMs = (to * frames.frameSeconds * 1000.0).toLong(),
                levelDb = level,
                levelFirstDb = percentile(frames.rmsDb, from, min(to, from + max(1, perSection / 3)), 0.5),
                levelRangeDb = percentile(frames.rmsDb, from, to, 0.9) - percentile(frames.rmsDb, from, to, 0.1),
                microDb = percentile(frames.rmsDb, from, to, 0.95) - percentile(frames.rmsDb, from, to, 0.5),
                centroid = percentile(frames.centroid, from, to, 0.5),
                flatness = percentile(frames.flatness, from, to, 0.75),
                flux = percentile(frames.flux, from, to, 0.75),
                lowRatio = percentile(frames.lowRatio, from, to, 0.5),
                highRatio = percentile(frames.highRatio, from, to, 0.5),
                onsetsPerSec = onsetsIn / seconds,
                beat = beat(frames.flux, from, to, frames.frameSeconds),
                transientPerMin = inside.size * 60.0 / seconds,
                strongestTransientDb = strongest,
                slopeDbPerSec = slope(frames.rmsDb, from, to, frames.frameSeconds),
                crest = percentile(frames.peakDb, from, to, 0.98) - level,
                score = 0.0,
            )
            section.score = sectionScore(section)
            out += section
        }
        return out
    }

    /** How strongly the onset envelope repeats at a beat period (30–200 BPM): 0 for rubato, ~1 for a driving four-four. */
    private fun beat(flux: DoubleArray, from: Int, to: Int, frameSeconds: Double): Double {
        val n = to - from
        if (n < 16) return 0.0
        var mean = 0.0
        for (i in from until to) mean += flux[i]
        mean /= n
        var energy = 0.0
        for (i in from until to) {
            val d = flux[i] - mean
            energy += d * d
        }
        if (energy < 1e-9) return 0.0
        val minLag = (0.30 / frameSeconds).roundToInt().coerceAtLeast(1)
        val maxLag = (2.0 / frameSeconds).roundToInt().coerceAtMost(n - 2)
        if (maxLag <= minLag) return 0.0
        var best = 0.0
        for (lag in minLag..maxLag) {
            var sum = 0.0
            for (i in from until to - lag) sum += (flux[i] - mean) * (flux[i + lag] - mean)
            best = max(best, sum / energy)
        }
        return best.coerceIn(0.0, 1.0)
    }

    /** The level's trend across [from, to), in dB per second (positive: getting louder). */
    private fun slope(values: DoubleArray, from: Int, to: Int, frameSeconds: Double): Double {
        val n = to - from
        if (n < 4) return 0.0
        val meanX = (n - 1) / 2.0
        var meanY = 0.0
        for (i in from until to) meanY += values[i]
        meanY /= n
        var numerator = 0.0
        var denominator = 0.0
        for (i in 0 until n) {
            val dx = i - meanX
            numerator += dx * (values[from + i] - meanY)
            denominator += dx * dx
        }
        if (denominator <= 0) return 0.0
        return numerator / denominator / frameSeconds
    }

    // ---------------------------------------------------------------- the formula

    /**
     * A section's own score: the same components as the song's, from its own measurements. A section isn't held to the
     * whole song's evidence (it has no confidence of its own); it is a witness, not a verdict.
     */
    private fun sectionScore(s: Section): Double {
        val quiet = quietness(s.levelDb)
        val dynamics = dynamics(s.levelRangeDb, 0, 0.0, s.microDb)
        val transients = transients(s.transientPerMin, s.strongestTransientDb, s.crest)
        val rhythm = rhythm(s.onsetsPerSec, s.beat, s.lowRatio)
        val complexity = complexity(s.flux, s.flatness, 0)
        val mood = mood(s.centroid, s.flatness, s.highRatio)
        val parts = doubleArrayOf(s.levelDb, s.levelRangeDb, s.transientPerMin, s.strongestTransientDb, s.onsetsPerSec, s.beat, s.flux, s.flatness, s.centroid, 0.0, s.microDb)
        val model = model(parts)
        return (0.16 * quiet + 0.20 * dynamics + 0.22 * transients + 0.14 * rhythm + 0.16 * complexity + 0.12 * mood) * 0.8 +
            model * 0.2
    }

    private fun judge(
        frames: Frames,
        sections: List<Section>,
        transients: List<Transient>,
        jumped: List<Event>,
        warnings: List<SleepWarning>,
        confidence: Int,
        durationMs: Long,
        analyzedAtMs: Long,
    ): SleepScore {
        val levels = DoubleArray(sections.size) { sections[it].levelDb }
        val levelTop = percentile(levels, 0, levels.size, 0.9)
        val levelRange = percentile(levels, 0, levels.size, 0.95) - percentile(levels, 0, levels.size, 0.05)
        val micro = DoubleArray(sections.size) { sections[it].microDb }.sorted()[sections.size / 2]
        val seconds = durationMs / 1000.0
        val strongPerMin = transients.count { it.strong } * 60.0 / seconds.coerceAtLeast(1.0)
        val strongest = transients.maxOfOrNull { it.strengthDb } ?: 0.0
        val crest = percentile(frames.peakDb, 0, frames.n, 0.98) - percentile(frames.rmsDb, 0, frames.n, 0.5)
        val onsetsPerSec = percentile(DoubleArray(sections.size) { sections[it].onsetsPerSec }, 0, sections.size, 0.9)
        val beat = sections.maxOfOrNull { it.beat } ?: 0.0
        val lowRatio = percentile(DoubleArray(sections.size) { sections[it].lowRatio }, 0, sections.size, 0.5)
        val flux = sections.maxOfOrNull { it.flux } ?: 0.0
        val flatness = percentile(DoubleArray(sections.size) { sections[it].flatness }, 0, sections.size, 0.9)
        val highRatio = percentile(DoubleArray(sections.size) { sections[it].highRatio }, 0, sections.size, 0.5)
        val centroid = percentile(DoubleArray(sections.size) { sections[it].centroid }, 0, sections.size, 0.5)
        val changes = warnings.count { it.kind == SleepWarningKind.ABRUPT_CHANGE }

        val quiet = quietness(levelTop)
        val dynamics = dynamics(levelRange, jumped.size, warnings.filter { it.kind == SleepWarningKind.CRESCENDO }.maxOfOrNull { it.severity }?.toDouble() ?: 0.0, micro)
        val transientScore = transients(strongPerMin, strongest, crest)
        val rhythm = rhythm(onsetsPerSec, beat, lowRatio)
        val complexity = complexity(flux, flatness, changes)
        val mood = mood(centroid, flatness, highRatio)
        val parts = doubleArrayOf(levelTop, levelRange, strongPerMin, strongest, onsetsPerSec, beat, flux, flatness, centroid, changes.toDouble(), micro)
        val model = model(parts)

        val dsp = 0.16 * quiet + 0.20 * dynamics + 0.22 * transientScore + 0.14 * rhythm + 0.16 * complexity + 0.12 * mood
        var score = dsp * 0.8 + model * 0.2
        // A weak part isn't averaged away: each component's own shortfall below 55 costs its weight's worth of points, so a
        // song with no transients but a loud, driving chorus can't pass on the strength of its quiet moments.
        for ((part, weight) in listOf(
            quiet to 0.16, dynamics to 0.20, transientScore to 0.22, rhythm to 0.14, complexity to 0.16, mood to 0.12,
        )) {
            score -= max(0.0, 55.0 - part) * weight
        }
        val worst = sections.minOfOrNull { it.score } ?: score

        // Conservative: a bad section anywhere is the song's problem, however calm the rest is.
        score = min(score, worst + 30.0)
        score -= 0.45 * max(0.0, 60.0 - worst)
        // A recording this loud is not made suitable by being steady: at a sleeper's volume a loud passage is still the
        // loudest thing in the room, and loud masters are the compressed, dense ones. This caps by absolute level, before
        // any of the event caps below.
        score = min(
            score,
            when {
                levelTop >= -8.0 -> 35.0
                levelTop >= -11.0 -> 45.0
                levelTop >= -14.0 -> 58.0
                levelTop >= -17.0 -> 70.0
                else -> 100.0
            },
        )
        // Too short to stand behind: a clip's figure may not be high, whatever it measured (a 20 s excerpt can miss the
        // chorus entirely, and missing the chorus is exactly the mistake this score must not make).
        if (durationMs < 30_000L) score = min(score, 55.0)
        // Hard caps for severe events the averages would otherwise wash out.
        for (w in warnings) {
            val cap = when {
                w.kind == SleepWarningKind.SUDDEN_LOUD && w.severity >= 3 -> 45.0
                w.kind == SleepWarningKind.SUDDEN_LOUD && w.severity == 2 -> 62.0
                w.kind == SleepWarningKind.CRESCENDO && w.severity >= 3 -> 52.0
                w.kind == SleepWarningKind.CRESCENDO && w.severity == 2 -> 66.0
                w.kind == SleepWarningKind.CLIPPING && w.severity >= 3 -> 45.0
                w.kind == SleepWarningKind.CLIPPING -> 60.0
                w.kind == SleepWarningKind.LOUD && w.severity >= 3 -> 45.0
                w.kind == SleepWarningKind.LOUD && w.severity == 2 -> 60.0
                w.kind == SleepWarningKind.ENERGETIC && w.severity >= 3 -> 55.0
                w.kind == SleepWarningKind.ENERGETIC -> 65.0
                else -> null
            } ?: continue
            score = min(score, cap)
        }
        // A short, partly decoded or silent recording is judged cautiously, but that is a confidence matter, not evidence
        // of suitability: it lowers the figure only a little and never raises it.
        score -= 0.12 * (100 - confidence)
        val finalScore = score.roundToInt().coerceIn(0, 100)

        val components = SleepComponents(
            quietness = quiet.roundToInt(),
            dynamics = dynamics.roundToInt(),
            transients = transientScore.roundToInt(),
            rhythm = rhythm.roundToInt(),
            complexity = complexity.roundToInt(),
            mood = mood.roundToInt(),
            model = model.roundToInt(),
            worstSection = worst.roundToInt(),
        )
        return SleepScore(finalScore, confidence, components, warnings, MODEL_VERSION, durationMs, analyzedAtMs)
    }

    /**
     * How well the audio could be judged, 0–100: length, how much of the expected song was decoded, how much of it is
     * silence rather than music, and how many sections there were to read. Kept apart from the score itself: a song can
     * be quietly unsuitable (long, clear, steadily bad) or uncertain (short, mostly silent, partly decoded).
     */
    private fun confidence(frames: Frames, sections: List<Section>, durationMs: Long, expectedMs: Long): Int {
        var c = 100.0
        if (durationMs < MIN_CONFIDENT_MS) c -= 45.0 * (1.0 - durationMs.toDouble() / MIN_CONFIDENT_MS)
        if (expectedMs > 0 && durationMs < expectedMs * 0.8) {
            c -= 35.0 * (1.0 - durationMs.toDouble() / expectedMs)
        }
        var silent = 0
        for (i in 0 until frames.n) if (frames.rmsDb[i] < SILENCE_DB) silent++
        val silentShare = silent.toDouble() / frames.n
        if (silentShare > 0.35) c -= 45.0 * (silentShare - 0.35) / 0.65
        if (sections.size < 3) c -= 10.0 * (3 - sections.size)
        return c.roundToInt().coerceIn(0, 100)
    }

    // ---------------------------------------------------------------- the components

    /** How quiet the loudest parts are: 100 at −32 dBFS and below, 0 at −10 dBFS and above. */
    private fun quietness(levelTopDb: Double): Double = ramp(levelTopDb, -32.0, -10.0)

    /** Predictable level: little spread between sections, no jumps, no crescendo, little within-section pumping. */
    private fun dynamics(levelRangeDb: Double, jumps: Int, crescendo: Double, microDb: Double): Double {
        var v = ramp(levelRangeDb, 5.0, 24.0)
        v -= 9.0 * min(jumps, 3)
        v -= 12.0 * crescendo
        v -= 3.0 * max(0.0, microDb - 4.0)
        return v.coerceIn(0.0, 100.0)
    }

    /** Few strong hits: density per minute, the strongest hit's rise, and peaky frames. */
    private fun transients(perMin: Double, strongestDb: Double, crestDb: Double): Double {
        var v = 100.0
        v -= 8.0 * min(perMin, 10.0)
        v -= 5.0 * max(0.0, strongestDb - 6.0)
        v -= 2.0 * max(0.0, crestDb - 18.0)
        return v.coerceIn(0.0, 100.0)
    }

    /** Little rhythmic drive: a low onset rate, a weak beat, little energy in the bass. */
    private fun rhythm(onsetsPerSec: Double, beat: Double, lowRatio: Double): Double {
        var v = ramp(onsetsPerSec, 0.4, 3.0)
        v -= 24.0 * beat.coerceIn(0.0, 1.0)
        v -= 30.0 * max(0.0, lowRatio - 0.30)
        return v.coerceIn(0.0, 100.0)
    }

    /** A simple arrangement: little spectral change, low noise, few structural shifts. */
    private fun complexity(flux: Double, flatness: Double, changes: Int): Double {
        var v = 100.0
        v -= 130.0 * max(0.0, flux - 0.10)
        v -= 60.0 * max(0.0, flatness - 0.06)
        v -= 10.0 * changes
        return v.coerceIn(0.0, 100.0)
    }

    /** Gentle rather than tense: dark, tonal spectra without a hiss or a lot of top-end edge. */
    private fun mood(centroidHz: Double, flatness: Double, highRatio: Double): Double {
        var v = ramp(centroidHz, 900.0, 3800.0)
        v -= 35.0 * max(0.0, flatness - 0.10)
        v -= 35.0 * max(0.0, highRatio - 0.22)
        return v.coerceIn(0.0, 100.0)
    }

    /**
     * The audio model: a fixed-weight linear model over the same measurements, its output squashed to 0–100. Its input
     * is centred and scaled ([MODEL_CENTER], [MODEL_SCALE]), so a typical pop song sits near 50 and only a genuinely
     * gentle one reaches the eighties; its weights ([MODEL_WEIGHTS]) are all negative, because each measurement is a
     * source of stimulation, and it exists to be retuned independently of the hand-written components.
     *
     * This is a model *impression*, not a probability: it is not calibrated against human votes here, and the UI must
     * never present it as one.
     */
    private fun model(parts: DoubleArray): Double {
        var z = MODEL_BIAS
        for (i in parts.indices) {
            val x = ((parts[i] - MODEL_CENTER[i]) / MODEL_SCALE[i]).coerceIn(-1.5, 1.5)
            z += MODEL_WEIGHTS[i] * x
        }
        val p = 1.0 / (1.0 + exp(-z))
        return 100.0 * p
    }

    /** Feature centres: the value at which a measurement stops adding evidence. */
    private val MODEL_CENTER = doubleArrayOf(
        -22.0, // loudest sections' level, dBFS
        10.0, // level range across sections, dB
        2.0, // strong transients per minute
        8.0, // strongest transient, dB above the passage before it
        1.2, // onsets per second
        0.25, // beat strength
        0.18, // spectral flux
        0.18, // spectral flatness
        1800.0, // spectral centroid, Hz
        2.0, // structural changes
        5.0, // within-section dynamic spread, dB
    )
    private val MODEL_SCALE = doubleArrayOf(12.0, 8.0, 4.0, 6.0, 1.0, 0.35, 0.2, 0.15, 1200.0, 3.0, 4.0)
    private val MODEL_WEIGHTS = doubleArrayOf(-0.85, -0.60, -1.10, -0.80, -0.70, -0.50, -0.60, -0.70, -0.50, -0.50, -0.40)
    private const val MODEL_BIAS = 0.85

    // ---------------------------------------------------------------- helpers

    /** 100 at [good] and below, 0 at [bad] and above, straight in between (bad > good for "lower is better"). */
    private fun ramp(x: Double, good: Double, bad: Double): Double =
        if (bad == good) if (x <= good) 100.0 else 0.0 else (100.0 * (bad - x) / (bad - good)).coerceIn(0.0, 100.0)

    /** The [p] quantile of [values] in [from, to), by nearest rank. */
    private fun percentile(values: DoubleArray, from: Int, to: Int, p: Double): Double {
        val n = to - from
        if (n <= 0) return 0.0
        if (n == 1) return values[from]
        val sorted = values.copyOfRange(from, to)
        sorted.sort()
        val at = ((n - 1) * p).roundToInt().coerceIn(0, n - 1)
        return sorted[at]
    }

    private fun nextPowerOfTwo(n: Int): Int {
        var v = 1
        while (v < n) v = v shl 1
        return v
    }
}
