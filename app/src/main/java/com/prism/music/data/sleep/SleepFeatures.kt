package com.prism.music.data.sleep

import kotlinx.serialization.Serializable
import kotlin.math.exp

/**
 * The bounded, interpretable inputs to the sleep score (1.9.2). Each value is 0–1, higher meaning more disturbing, so a
 * listener's labels can be fitted to them without decoding the audio again. They travel with the stored score.
 *
 * Volume is deliberately one of the smallest inputs: playback's Normalize already brings recordings to the same level,
 * and a loud master isn't why a song wakes someone — its hits, drive, changes and unpredictability are.
 */
@Serializable
data class SleepFeatures(
    /** Absolute loudness, 0 in normalized mode (Normalize makes it the same for every recording). */
    val level: Double = 0.0,
    /** Peakiness: a high crest or a peak near full scale. */
    val peaks: Double = 0.0,
    /** Strong transients per minute and how far they rise above the passage before them. */
    val transients: Double = 0.0,
    /** Onset density and beat strength. */
    val onsets: Double = 0.0,
    /** Spectral activity and noise. */
    val activity: Double = 0.0,
    /** Sudden events, crescendos and structural changes. */
    val changes: Double = 0.0,
    /** Bright, tense or hi-hat-heavy sound. */
    val brightness: Double = 0.0,
    /** The most disturbing section's own disturbance, not its average. */
    val worstSection: Double = 0.0,
) {
    /** The fit uses a fixed order so a stored vector means the same thing after a restart. */
    fun asArray(): DoubleArray = doubleArrayOf(level, peaks, transients, onsets, activity, changes, brightness, worstSection)

    /** The volume term can be removed without changing the other measurements (normalized mode). */
    fun withoutLevel(): SleepFeatures = copy(level = 0.0)

    companion object {
        const val COUNT = 8

        fun from(values: DoubleArray): SleepFeatures = SleepFeatures(
            values.getOrNull(0) ?: 0.0, values.getOrNull(1) ?: 0.0, values.getOrNull(2) ?: 0.0, values.getOrNull(3) ?: 0.0,
            values.getOrNull(4) ?: 0.0, values.getOrNull(5) ?: 0.0, values.getOrNull(6) ?: 0.0, values.getOrNull(7) ?: 0.0,
        )
    }
}

/** A linear model in log-odds: higher [weights] mean a feature disturbs sleep more. */
@Serializable
data class SleepWeights(
    /** The intercept: what an entirely undisturbed recording scores. */
    val bias: Double = DEFAULT_BIAS,
    /** One weight per [SleepFeatures] field, in its fixed order. */
    val weights: DoubleArray = DEFAULT_WEIGHTS.copyOf(),
    /** How many listener judgments went into these weights (0: the transparent default). */
    val labels: Int = 0,
) {
    /** Squashes one song's measurements into a 0–100 score with a floor and a ceiling, never a stack of hard caps. */
    fun score(features: SleepFeatures, confidence: Int): Int {
        val f = features.asArray()
        var logit = bias
        for (i in f.indices) logit -= SLOPE * weights[i] * f[i]
        // Low confidence makes the score cautious, but cannot dominate it.
        logit += (confidence - 100) * 0.01
        val p = 1.0 / (1.0 + exp(-logit))
        return (FLOOR + SPAN * p).toInt().coerceIn(0, 100)
    }

    override fun equals(other: Any?): Boolean =
        other is SleepWeights && labels == other.labels && bias == other.bias && weights.contentEquals(other.weights)

    override fun hashCode(): Int = (bias.hashCode() * 31 + labels) * 31 + weights.contentHashCode()

    companion object {
        /** Room to rank even very disturbed songs, and a ceiling that never claims perfection. */
        const val FLOOR = 10.0
        const val SPAN = 85.0

        /** Inputs tuned so typical music lands in the middle, quiet ambient high, and a disruptive track low. */
        const val DEFAULT_BIAS = 2.0
        /** Spreads ordinary music across the scale instead of bunching it near the top. */
        const val SLOPE = 1.6
        /** level, peaks, transients, onsets, activity, changes, brightness, worstSection. */
        val DEFAULT_WEIGHTS = doubleArrayOf(0.25, 0.90, 1.60, 1.05, 0.85, 1.10, 0.40, 1.10)
    }
}
