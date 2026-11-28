package com.prism.music.analysis

import com.prism.music.R
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What a sleep score is made of. Each factor is a calmness 0 (keeps you up) … 1 (lets you sleep). The defaults come
 * from the sleep-music studies in docs/audio-analysis.md (loudness and energy separate sleep playlists most; event
 * density, dynamic variation and brightness matter in listening tests; tempo only counts as far as there's a clear beat
 * to feel).
 *
 * Two kinds: the *arousal* factors (how energetic, bright, busy, rhythmic, vocal the sound is) are averaged by [weight]
 * into the score's base; the [disruptive] ones (sudden loud moments, wide dynamics, an uneven song, an erratic beat)
 * only take away from it, each by up to [weight] of the score: being steady is needed for sleep, but a steady,
 * compressed club track isn't calm for it.
 */
enum class SleepFactor(val label: Int, val weight: Float, val disruptive: Boolean = false) {
    ENERGY(R.string.sleep_factor_energy, 1.4f),
    TEMPO(R.string.sleep_factor_tempo, 0.9f),
    BEAT(R.string.sleep_factor_beat, 1.0f),
    REGULARITY(R.string.sleep_factor_regularity, 0.15f, disruptive = true),
    DYNAMICS(R.string.sleep_factor_dynamics, 0.35f, disruptive = true),
    SUDDEN(R.string.sleep_factor_sudden, 0.6f, disruptive = true),
    BRIGHTNESS(R.string.sleep_factor_brightness, 0.9f),
    DENSITY(R.string.sleep_factor_density, 1.0f),
    VOCALS(R.string.sleep_factor_vocals, 0.6f),
    INSTRUMENTS(R.string.sleep_factor_instruments, 0.8f),
    MOOD(R.string.sleep_factor_mood, 0.5f),
    CONSISTENCY(R.string.sleep_factor_consistency, 0.35f, disruptive = true),
}

/** The listener's say in how songs are scored (Settings → Sound analysis). */
data class SleepWeights(
    /** Singing and speech count against a song (on by default; some sleep best to voices). */
    val vocalsCount: Boolean = true,
    /** Per-factor weights; [SleepFactor.weight] for any not here. */
    val custom: Map<SleepFactor, Float> = emptyMap(),
) {
    fun of(f: SleepFactor): Float = if (f == SleepFactor.VOCALS && !vocalsCount) 0f else custom[f] ?: f.weight
}

/**
 * One factor's part in a score: its calmness, the weight it counted with (after how much it applies, e.g. tempo by how
 * clear the beat is), and the figure it was read from, for the explanation ("72 BPM", "-21 LUFS").
 */
data class FactorPart(val factor: SleepFactor, val calm: Float, val weight: Float, val detail: String)

/**
 * A song's sleep score and why. [score] is what the sleep timer's limit compares; [base] the sound alone, before
 * the listener's own feedback ([personal]: learned from all their votes and sleep-timer listening; [vote]: this song's
 * own vote). [capped] says a sudden loud moment held the score down. [completeness] is how much of the full weight
 * the song had figures for (no tagger: no vocals, instruments or mood).
 */
data class SleepResult(
    val score: Int,
    val base: Int,
    val parts: List<FactorPart>,
    val capped: Boolean,
    val personal: Int,
    val vote: Int,
    val completeness: Float,
) {
    /** The factors that pull the score down most (calmness × weight furthest under the weighted mean). */
    fun against(n: Int = 3): List<FactorPart> = parts.filter { it.weight > 0 && it.calm < 0.5f }.sortedBy { (it.calm - 0.5f) * it.weight }.take(n)
    fun forIt(n: Int = 3): List<FactorPart> = parts.filter { it.weight > 0 && it.calm >= 0.6f }.sortedByDescending { (it.calm - 0.5f) * it.weight }.take(n)
}

/**
 * How suitable a song is to fall asleep to, 0 … 100, from its [SongFeatures] and (when the tagger has heard it) its
 * tag probabilities; every factor explained. Slow or instrumental alone never makes a song "sleepy": a slow song with a
 * hard beat, sudden crashes or a bright, busy mix still scores low, and a calm song with soft singing still scores high.
 */
object SleepScore {
    /** Bumped when the score is worked out differently: cached scores are worked out again. */
    const val VERSION = 1

    /** A sudden loud moment this big (LU) caps the score at [CAP]: one crash is enough to wake someone. */
    const val CAP_RISE = 15f
    const val CAP = 45

    /** Within ±[VOTE_SHIFT] for the song's own vote, ±[PERSONAL_SHIFT] for what's learned from all of them. */
    const val VOTE_SHIFT = 25
    const val PERSONAL_SHIFT = 15

    fun of(
        f: SongFeatures,
        /** The tagger's pooled class probabilities, null when it hasn't heard the song. */
        scores: FloatArray? = null,
        weights: SleepWeights = SleepWeights(),
        personal: SleepPersonal? = null,
        /** This song's own vote: +1 good for sleep, -1 not, 0 none. */
        vote: Int = 0,
    ): SleepResult {
        val parts = factors(f, scores).map { (factor, calm, applies, detail) -> FactorPart(factor, calm.coerceIn(0f, 1f), weights.of(factor) * applies, detail) }
        // The base: the arousal factors' weighted calmness.
        val arousal = parts.filter { !it.factor.disruptive }
        val total = arousal.sumOf { it.weight.toDouble() }
        val raw = if (total > 0) arousal.sumOf { it.calm.toDouble() * it.weight } / total else 0.5
        // Then each disruption takes its share away: a factor at calm 0 removes [SleepFactor.weight] of the score.
        var kept = 1.0
        for (p in parts) if (p.factor.disruptive) kept *= 1.0 - (p.weight * (1f - p.calm)).coerceIn(0f, 1f)
        var base = (raw * kept * 100).roundToInt()
        val capped = f.worstRise >= CAP_RISE && f.suddenPerMinute > 0f && base > CAP
        if (capped) base = CAP
        val shift = personal?.shift(parts) ?: 0
        val voteShift = vote.coerceIn(-1, 1) * VOTE_SHIFT
        val full = SleepFactor.entries.sumOf { weights.of(it).toDouble() }
        return SleepResult(
            score = (base + shift + voteShift).coerceIn(0, 100),
            base = base,
            parts = parts,
            capped = capped,
            personal = shift,
            vote = voteShift,
            completeness = if (full > 0) (parts.sumOf { weights.of(it.factor).toDouble() } / full).toFloat().coerceAtMost(1f) else 0f,
        )
    }

    private data class Raw(val factor: SleepFactor, val calm: Float, val applies: Float, val detail: String)

    /** 1 at or under [good], 0 at or over [bad], eased between. */
    internal fun down(x: Float, good: Float, bad: Float): Float {
        if (x <= good) return 1f
        if (x >= bad) return 0f
        val t = (x - good) / (bad - good)
        return 1f - t * t * (3 - 2 * t)
    }

    private fun f1(x: Float) = "%.1f".format(x)
    private fun f0(x: Float) = x.roundToInt().toString()

    private fun factors(f: SongFeatures, scores: FloatArray?): List<Raw> {
        val out = ArrayList<Raw>()
        // Energy: how loud it's made and how loud its loudest moments are (normalization evens playback, but a loud
        // master is a dense, compressed, energetic one).
        f.lufs?.let { l ->
            val loud = down(l, -22f, -8f)
            val peak = f.maxMomentary?.let { down(it, -16f, -4f) } ?: loud
            out += Raw(SleepFactor.ENERGY, 0.6f * loud + 0.4f * peak, 1f, "${f0(l)} LUFS")
        }
        // Tempo counts as far as there's a beat to feel it by: an ambient piece "at 140" has none.
        val pulse = f.pulseClarity
        if (f.bpm != null) {
            out += Raw(SleepFactor.TEMPO, down(f.bpm, 85f, 135f), 0.2f + 0.8f * pulse, "${f0(f.bpm)} BPM")
        }
        val perc = ((f.percussive - 0.2f) / 0.4f).coerceIn(0f, 1f)
        out += Raw(SleepFactor.BEAT, 1f - (0.6f * pulse + 0.4f * perc), 1f, "${f0(pulse * 100)}% · ${f0(f.percussive * 100)}%")
        // A steady beat is predictable; an erratic one keeps attention. Only matters where there is a beat.
        f.tempoStability?.let { out += Raw(SleepFactor.REGULARITY, it, pulse, "${f0(it * 100)}%") }
        // Dynamics: loudness range and how far the loudness strays from its average.
        if (f.lra != null || f.dynamicComplexity != null) {
            val a = f.lra?.let { down(it, 6f, 20f) }
            val b = f.dynamicComplexity?.let { down(it, 2f, 8f) }
            val calm = listOfNotNull(a, b).average().toFloat()
            out += Raw(SleepFactor.DYNAMICS, calm, 1f, f.lra?.let { "LRA ${f1(it)} LU" } ?: "${f1(f.dynamicComplexity!!)} LU")
        }
        out += Raw(
            SleepFactor.SUDDEN,
            down(f.suddenPerMinute, 0f, 1.5f) * (0.4f + 0.6f * down(f.worstRise, 8f, 18f)),
            1f,
            "${f1(f.suddenPerMinute)}/min · +${f0(f.worstRise)} LU",
        )
        // Brightness: the share of the energy above 1.5 kHz (real recordings: 1–4 % for dark, bass-led music, 10–20 % for
        // bright guitars and noise), with the centroid for songs analysed before it was measured.
        if (f.brightShare > 0f) {
            out += Raw(
                SleepFactor.BRIGHTNESS,
                0.75f * down(f.brightShare, 0.03f, 0.15f) + 0.25f * down(f.centroidHz, 500f, 1200f),
                1f, "${f0(f.brightShare * 100)}% > 1.5 kHz",
            )
        } else if (f.centroidHz > 0f) {
            out += Raw(SleepFactor.BRIGHTNESS, down(f.centroidHz, 500f, 1200f), 1f, "${f0(f.centroidHz)} Hz")
        }
        out += Raw(SleepFactor.DENSITY, down(f.onsetRate, 1.5f, 5f), 1f, "${f1(f.onsetRate)}/s")
        consistency(f)?.let { (calm, detail) -> out += Raw(SleepFactor.CONSISTENCY, calm, 1f, detail) }
        if (scores != null) tagged(scores, out)
        return out
    }

    /** Even throughout: segment loudness spread, the loudest stretch above the typical one, and brightness swings. */
    private fun consistency(f: SongFeatures): Pair<Float, String>? {
        val loud = f.segLoud.filter { it > -60f }
        if (loud.size < 3) return null
        val sorted = loud.sorted()
        val p10 = sorted[(sorted.size * 0.1).toInt()]
        val p90 = sorted[min(sorted.lastIndex, (sorted.size * 0.9).toInt())]
        val median = sorted[sorted.size / 2]
        val spread = p90 - p10
        val above = sorted.last() - median
        val bright = f.segBright.filter { it > 0f }
        val cv = if (bright.size >= 3) {
            val m = bright.average()
            (kotlin.math.sqrt(bright.sumOf { (it - m) * (it - m) } / bright.size) / m).toFloat()
        } else 0f
        val calm = 0.5f * down(spread, 3f, 12f) + 0.3f * down(above, 2f, 9f) + 0.2f * down(cv, 0.1f, 0.4f)
        return calm to "${f1(spread)} LU"
    }

    private fun score(scores: FloatArray, key: String): Float {
        val d = TagVocabulary.def(key) ?: return 0f
        var m = 0f
        for (c in d.classes) if (c in scores.indices) m = max(m, scores[c])
        return m
    }

    private fun tagged(scores: FloatArray, out: MutableList<Raw>) {
        // Vocals: speech and rap more than singing; humming and a choir hardly at all.
        val singing = score(scores, "vocals:singing")
        val speech = max(score(scores, "vocals:speech"), score(scores, "vocals:rap"))
        val voice = max(0.7f * singing, speech)
        out += Raw(SleepFactor.VOCALS, 1f - min(1f, voice / 0.6f), 1f, "${f0(voice * 100)}%")
        // Soft instruments and sounds against hard ones.
        val soft = listOf("instrument:piano", "instrument:strings", "instrument:harp", "instrument:acoustic-guitar", "instrument:bells",
            "instrument:woodwind", "style-kind:meditative", "sounds:rain", "sounds:water", "sounds:wind", "sounds:noise").maxOf { score(scores, it) }
        val hard = listOf("instrument:drums", "instrument:drum-machine", "instrument:electric-guitar", "instrument:brass", "style:heavy-metal",
            "style:punk", "style:dubstep", "style:drum-and-bass", "style:techno").maxOf { score(scores, it) }
        out += Raw(SleepFactor.INSTRUMENTS, (0.5f + (soft - hard) / 0.8f).coerceIn(0f, 1f), 1f, "+${f0(soft * 100)}% / −${f0(hard * 100)}%")
        // Mood: tender, sad and lullabies calm; exciting, angry and scary don't.
        val calmMood = max(max(score(scores, "mood:tender"), 0.7f * score(scores, "mood:sad")), score(scores, "style-kind:lullaby"))
        val rousing = maxOf(score(scores, "mood:exciting"), score(scores, "mood:angry"), score(scores, "mood:scary"), 0.6f * score(scores, "mood:happy"))
        out += Raw(SleepFactor.MOOD, (0.5f + (calmMood - rousing) / 0.4f).coerceIn(0f, 1f), 1f, "+${f0(calmMood * 100)}% / −${f0(rousing * 100)}%")
    }
}

/**
 * What a listener's own feedback says about sleep music, beyond any one song: a small logistic model over the factor
 * calmnesses, fitted to their votes ("good for sleep" / "not for sleep") and to how songs fared while the sleep timer ran
 * (skipped: not; played through: a little yes). Ridge-regularized towards nothing, so a handful of examples moves
 * scores only a little; it shifts a score by at most ±[SleepScore.PERSONAL_SHIFT].
 */
class SleepPersonal private constructor(private val w: FloatArray, private val bias: Float, val examples: Int) {
    /** The shift for a song with these [parts] (0 without enough examples of both kinds). */
    fun shift(parts: List<FactorPart>): Int {
        if (examples < MIN_EXAMPLES) return 0
        val x = vector(parts)
        var z = bias
        for (i in x.indices) z += w[i] * x[i]
        val p = 1f / (1f + exp(-z))
        return ((p - 0.5f) * 2 * SleepScore.PERSONAL_SHIFT).roundToInt().coerceIn(-SleepScore.PERSONAL_SHIFT, SleepScore.PERSONAL_SHIFT)
    }

    /** One example: a song's factor parts, whether it suited sleep, and how much it counts (a vote 1, a skip 0.5…). */
    class Example(val parts: List<FactorPart>, val good: Boolean, val weight: Float)

    companion object {
        const val MIN_EXAMPLES = 4
        private const val LAMBDA = 0.5f
        private const val STEPS = 300
        private const val RATE = 0.5f

        /** Calmness per factor, centred (a factor the song has no figure for counts as neutral). */
        internal fun vector(parts: List<FactorPart>): FloatArray {
            val x = FloatArray(SleepFactor.entries.size)
            for (p in parts) x[p.factor.ordinal] = p.calm - 0.5f
            return x
        }

        fun fit(examples: List<Example>): SleepPersonal {
            val n = SleepFactor.entries.size
            val w = FloatArray(n)
            var b = 0f
            // Both kinds needed: all "good" only says the listener likes what they voted on.
            if (examples.size < MIN_EXAMPLES || examples.none { it.good } || examples.none { !it.good }) return SleepPersonal(w, 0f, 0)
            val xs = examples.map { vector(it.parts) }
            val total = examples.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(1e-3f)
            repeat(STEPS) {
                val gw = FloatArray(n)
                var gb = 0f
                for ((i, e) in examples.withIndex()) {
                    val x = xs[i]
                    var z = b
                    for (k in 0 until n) z += w[k] * x[k]
                    val p = 1f / (1f + exp(-z))
                    val err = (p - if (e.good) 1f else 0f) * e.weight
                    for (k in 0 until n) gw[k] += err * x[k]
                    gb += err
                }
                for (k in 0 until n) w[k] -= RATE * (gw[k] / total + LAMBDA * w[k])
                b -= RATE * (gb / total + LAMBDA * b)
            }
            return SleepPersonal(w, b, examples.size)
        }
    }
}
