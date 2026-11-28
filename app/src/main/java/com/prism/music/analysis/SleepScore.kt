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
    TEMPO(R.string.sleep_factor_tempo, 0.7f),
    BEAT(R.string.sleep_factor_beat, 1.0f),
    REGULARITY(R.string.sleep_factor_regularity, 0.15f, disruptive = true),
    DYNAMICS(R.string.sleep_factor_dynamics, 0.35f, disruptive = true),
    SUDDEN(R.string.sleep_factor_sudden, 0.6f, disruptive = true),
    BRIGHTNESS(R.string.sleep_factor_brightness, 0.6f),
    DENSITY(R.string.sleep_factor_density, 1.0f),
    VOCALS(R.string.sleep_factor_vocals, 0.6f),
    INSTRUMENTS(R.string.sleep_factor_instruments, 1.0f),
    MOOD(R.string.sleep_factor_mood, 0.5f),
    CONSISTENCY(R.string.sleep_factor_consistency, 0.35f, disruptive = true),
    HARSHNESS(R.string.sleep_factor_harshness, 0.45f, disruptive = true),
}

/** The listener's say in how songs are scored (Settings → Sound analysis). */
data class SleepWeights(
    /** Singing and speech count against a song (on by default; some sleep best to voices). */
    val vocalsCount: Boolean = true,
    /**
     * The level playback brings every song to (the Normalize target, in LUFS), or null when Normalize is off and each
     * master plays at its own level. ENERGY's weight follows what the player's gain leaves of the difference:
     * [RESIDUAL_FULL_DB] or more and the level is heard in full (1.4); none left, and loudness is only a proxy for a
     * dense master (0.6).
     */
    val normalizeTarget: Float? = -14f,
    /** Per-factor weights; [SleepFactor.weight] for any not here. */
    val custom: Map<SleepFactor, Float> = emptyMap(),
) {
    fun of(f: SleepFactor, features: SongFeatures? = null): Float = when {
        f == SleepFactor.VOCALS && !vocalsCount -> 0f
        custom[f] != null -> custom.getValue(f)
        f == SleepFactor.ENERGY -> energy(features)
        else -> f.weight
    }

    /**
     * ENERGY's pull for a song: the player's gain ([com.prism.music.playback.NormalizerProcessor.gainFor]: the target's
     * cut, or a lift its peaks allow) is added to the master's loudness, and what's left between that and the target is
     * still heard. Nothing left: 0.6. A recording the player can't lift all the way (a very quiet, peak-limited one), or
     * a "Loud" target that barely cuts: up to 1.4.
     */
    fun energy(features: SongFeatures?): Float {
        val target = normalizeTarget ?: return SleepFactor.ENERGY.weight
        val lufs = features?.lufs ?: return NORMALIZED_ENERGY
        val gain = com.prism.music.playback.NormalizerProcessor.gainFor(target, lufs, features.peakDb)
        val residual = kotlin.math.abs(target - (lufs + gain))
        return NORMALIZED_ENERGY + (SleepFactor.ENERGY.weight - NORMALIZED_ENERGY) * (residual / RESIDUAL_FULL_DB).coerceIn(0f, 1f)
    }

    companion object {
        /**
         * ENERGY's weight where the player has equalized the level entirely: 0.6 against 1.4. Loudness is still a proxy
         * for a dense, compressed master, so it isn't nothing; but at equal playback level it must not decide a score
         * over what's actually heard.
         */
        const val NORMALIZED_ENERGY = 0.6f
        /** How far (dB) a song may still sit from the target before its level counts in full. */
        const val RESIDUAL_FULL_DB = 6f
    }
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
    const val VERSION = 2

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
        // A factor's pull: its weight, how much it applies (a tempo with no beat to feel), and how sure its evidence is.
        val measured = factors(f, scores, weights)
        val factorsKnown = measured.filter { it.available }.map { it.factor }.distinct()
        val parts = measured.map { r ->
            FactorPart(r.factor, r.calm.coerceIn(0f, 1f), weights.of(r.factor, f) * r.applies * r.confidence, r.detail)
        }
        // The base: the arousal factors' weighted calmness.
        val arousal = parts.filter { !it.factor.disruptive }
        val total = arousal.sumOf { it.weight.toDouble() }
        val raw = if (total > 0) arousal.sumOf { it.calm.toDouble() * it.weight } / total else 0.5
        // Then each disruption takes its share away: a factor at calm 0 removes [SleepFactor.weight] of the score.
        var kept = 1.0
        for (p in parts) if (p.factor.disruptive) kept *= 1.0 - (p.weight * (1f - p.calm)).coerceIn(0f, 1f)
        // A figure the song doesn't have (no tagger: no vocals, instruments or mood; no beat: no tempo) counts as neutral,
        // not as calm: what's unknown mustn't raise a score. [SleepFactor.weight] weighs what a song with every figure has.
        val full = SleepFactor.entries.sumOf { weights.of(it, f).toDouble() }
        // Figures the song has: a tag factor counts only if the tagger had any evidence at all (a tagger that heard nothing
        // leaves the score as the untagged one, not "complete but neutral").
        val present = factorsKnown.sumOf { weights.of(it, f).toDouble() }
        val completeness = if (full > 0) (present / full).toFloat().coerceIn(0f, 1f) else 0f
        var base = (50.0 + (raw * kept * 100 - 50.0) * completeness).roundToInt()
        val capped = f.worstRise >= CAP_RISE && f.suddenPerMinute > 0f && base > CAP
        if (capped) base = CAP
        val shift = personal?.shift(parts) ?: 0
        val voteShift = vote.coerceIn(-1, 1) * VOTE_SHIFT
        return SleepResult(
            score = (base + shift + voteShift).coerceIn(0, 100),
            base = base,
            parts = parts,
            capped = capped,
            personal = shift,
            vote = voteShift,
            completeness = completeness,
        )
    }

    private data class Raw(
        val factor: SleepFactor,
        val calm: Float,
        val applies: Float,
        val detail: String,
        /** How sure the evidence behind [calm] is (1 for the scan's own figures; the tagger's and segments' vary). */
        val confidence: Float = 1f,
        /** Whether it's a figure at all: a tag factor the tagger heard nothing for isn't (it mustn't make a song "known"). */
        val available: Boolean = true,
    )

    /** 1 at or under [good], 0 at or over [bad], eased between. */
    internal fun down(x: Float, good: Float, bad: Float): Float {
        if (x <= good) return 1f
        if (x >= bad) return 0f
        val t = (x - good) / (bad - good)
        return 1f - t * t * (3 - 2 * t)
    }

    private fun f1(x: Float) = "%.1f".format(x)
    private fun f2(x: Float) = "%.2f".format(x)
    private fun f0(x: Float) = x.roundToInt().toString()

    /** 0 at or under [low], 1 at or over [high], eased between. */
    internal fun up(x: Float, low: Float, high: Float): Float = 1f - down(x, low, high)

    private fun factors(f: SongFeatures, scores: FloatArray?, weights: SleepWeights): List<Raw> {
        val out = ArrayList<Raw>()
        // Energy: how loud it's heard and how squashed the master is. The level is the master's after the player's gain
        // (Normalize lifts a quiet master to the same level as the rest: read at its own level, a -21 LUFS upload of a
        // hyper pop song was "calm"). How squashed is the peak-to-loudness ratio, whatever level it plays at: a wall-of-
        // sound master sits 6–9 dB, an open, dynamic recording 14 or more.
        f.lufs?.let { l ->
            val gain = weights.normalizeTarget?.let { com.prism.music.playback.NormalizerProcessor.gainFor(it, l, f.peakDb) } ?: 0f
            val heard = down(l + gain, -22f, -8f)
            val level = 0.6f * heard + 0.4f * (f.maxMomentary?.let { down(it + gain, -16f, -4f) } ?: heard)
            val peak = f.truePeakDb ?: f.peakDb
            val crest = peak?.let { up(it - l, 8f, 16f) }
            val calm = when {
                // No peak to tell how squashed it is: the master's own level is the best proxy for that (as before v2).
                crest == null -> 0.6f * down(l, -22f, -8f) + 0.4f * (f.maxMomentary?.let { down(it, -16f, -4f) } ?: down(l, -22f, -8f))
                // Equalized, the level says little (it's the target for nearly every song); the master's density does.
                weights.normalizeTarget != null -> 0.4f * level + 0.6f * crest
                else -> 0.7f * level + 0.3f * crest
            }
            out += Raw(SleepFactor.ENERGY, calm, 1f, "${f0(l)} LUFS" + (peak?.let { " · PLR ${f0(it - l)} dB" } ?: ""))
        }
        // Tempo counts as far as there's a beat to feel it by: an ambient piece "at 140" has none. A wide curve and a
        // modest weight: the scan's tempo is often a half or double of the felt one (a ballad "at 134", a punk song "at
        // 82"), and slow mustn't make a loud song sleepy.
        val pulse = f.pulseClarity
        if (f.bpm != null) {
            out += Raw(SleepFactor.TEMPO, down(f.bpm, 80f, 160f), 0.2f + 0.8f * pulse, "${f0(f.bpm)} BPM")
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
            down(f.suddenPerMinute, 0.2f, 2f) * (0.4f + 0.6f * down(f.worstRise, 9f, 20f)),
            1f,
            "${f1(f.suddenPerMinute)}/min · +${f0(f.worstRise)} LU",
        )
        // Brightness: the share above 1.5 kHz of what's above the sub-bass (150 Hz). Of the whole spectrum, a bass-boosted
        // or distorted mix read "dark" (1–5 %) and so calm, while a soft, airy recording read bright: real exports had the
        // songs marked "too high" darker than those marked "too low". Without the sub-bass: 0–5 % for mellow, 10–20 % for
        // most pop, 30 % and more for bright guitars, cymbals and noise.
        if (f.brightShare > 0f) {
            val share = f.brightShare / (1f - f.lowRatio).coerceAtLeast(0.1f)
            out += Raw(SleepFactor.BRIGHTNESS, down(share, 0.06f, 0.40f), 1f, "${f0(share * 100)}% > 1.5 kHz")
        } else if (f.centroidHz > 0f) {
            out += Raw(SleepFactor.BRIGHTNESS, down(f.centroidHz, 500f, 1200f), 1f, "${f0(f.centroidHz)} Hz")
        }
        out += Raw(SleepFactor.DENSITY, down(f.onsetRate, 1.5f, 5f), 1f, "${f1(f.onsetRate)}/s")
        // Harshness: how noise-like the sound is (spectral flatness). Distorted guitars, cymbals and screams are noise with
        // most of their energy low-mid, so a metal mix can read "dark" by spectral share (a sample at 5 % above 1.5 kHz) and
        // 59/100 with it. Measured on real recordings: tonal or acoustic music sits at 0.001–0.05, a noise wall at 0.10.
        if (f.flatness > 0f) out += Raw(SleepFactor.HARSHNESS, down(f.flatness, 0.04f, 0.10f), 1f, "flat ${f2(f.flatness)}")
        // A song of three segments says less about how even it is than one of twenty.
        consistency(f)?.let { (calm, detail) -> out += Raw(SleepFactor.CONSISTENCY, calm, 1f, detail, (f.segLoud.size / 6f).coerceIn(0f, 1f)) }
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
        // How much the tagger heard at all: a song it could say nothing about (very quiet, or a sound it doesn't know)
        // mustn't be read as "no voice, so instrumental" — its tag factors count for as little as its evidence.
        val heard = scores.maxOrNull() ?: 0f
        val gate = (heard / 0.08f).coerceIn(0f, 1f)
        // Below a noise floor the tagger said nothing about the song at all: its tag factors are neither evidence nor figures.
        val heardSomething = heard >= 0.02f
        // Vocals: speech and rap more than singing; humming and a choir hardly at all.
        val singing = score(scores, "vocals:singing")
        val speech = max(score(scores, "vocals:speech"), score(scores, "vocals:rap"))
        val voice = max(0.7f * singing, speech)
        // Sure either way: a clear voice, or clearly none. Between them (a word here and there) it says little for sleep,
        // where 0.6 of voice is already as vocal as it gets.
        val voiceShare = min(1f, voice / 0.6f)
        val voiceSure = kotlin.math.abs(2f * voiceShare - 1f)
        out += Raw(SleepFactor.VOCALS, 1f - min(1f, voice / 0.6f), 1f, "${f0(voice * 100)}%", voiceSure * gate, heardSomething)
        // Soft instruments and styles against hard ones. YAMNet's genre and style classes are spread thin (a clear rock song
        // reads 0.15–0.30), so a modest reading is already evidence: in real exports these were what set the loud songs
        // marked "too high" apart, where tempo and brightness had read them calm.
        val soft = listOf("instrument:piano", "instrument:strings", "instrument:harp", "instrument:acoustic-guitar", "instrument:bells",
            "instrument:woodwind", "style-kind:meditative", "style-kind:lullaby", "style:ambient", "style:new-age",
            "sounds:rain", "sounds:water", "sounds:wind", "sounds:noise").maxOf { score(scores, it) }
        val hard = listOf("instrument:drums", "instrument:drum-machine", "instrument:electric-guitar", "instrument:brass",
            "genre:Rock", "genre:Metal", "style:heavy-metal", "style:punk", "style:grunge", "style:progressive-rock",
            "style:dubstep", "style:drum-and-bass", "style:techno", "style:edm", "style:trance", "style:house", "style-kind:dance").maxOf { score(scores, it) }
        out += Raw(
            SleepFactor.INSTRUMENTS, (0.5f + (soft - hard) / 0.5f).coerceIn(0f, 1f), 1f,
            "+${f0(soft * 100)}% / −${f0(hard * 100)}%", (max(soft, hard) / 0.18f).coerceIn(0f, 1f) * gate, heardSomething,
        )
        // Mood: tender, sad and lullabies calm; exciting, angry and scary don't.
        val calmMood = max(max(score(scores, "mood:tender"), 0.7f * score(scores, "mood:sad")), score(scores, "style-kind:lullaby"))
        val rousing = maxOf(score(scores, "mood:exciting"), score(scores, "mood:angry"), score(scores, "mood:scary"), 0.6f * score(scores, "mood:happy"))
        out += Raw(
            SleepFactor.MOOD, (0.5f + (calmMood - rousing) / 0.4f).coerceIn(0f, 1f), 1f,
            "+${f0(calmMood * 100)}% / −${f0(rousing * 100)}%", (max(calmMood, rousing) / 0.25f).coerceIn(0f, 1f) * gate,
            heardSomething,
        )
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
