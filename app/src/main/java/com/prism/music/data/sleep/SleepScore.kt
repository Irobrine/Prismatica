package com.prism.music.data.sleep

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A song's sleep suitability (1.9): how unlikely its sound is to disturb someone falling asleep, 0–100, higher being
 * more suitable.
 *
 * Every figure here comes from the audio itself (never genre, artist or title), and the listener can fit the model to
 * their own taste by marking songs. Precision still matters more than recall, but a disruptive song is excluded by a
 * low score, not by a pile of hard caps that flatten an entire library to zero.
 *
 * [score] is the published figure (a bounded logistic over [features], version [modelVersion]); [confidence] says how
 * much audio could be heard well enough to judge it. They are kept apart: low confidence only nudges [score], and
 * mainly tells the UI and the filter that the figure is a cautious estimate.
 *
 * [features] are the bounded measurements the model used, stored so listener labels can be fitted without decoding the
 * audio again. [components] are their 0–100 mirror for diagnostics. [warnings] keep the sections that behaved badly,
 * with their times, so what a figure is made of is never lost with the samples.
 *
 * Stored per recording ([com.prism.music.playback.Loudness.formId]): a music video, a picked version or a file plays
 * different audio from the song's own stream, and only the audio that is actually heard can be judged.
 */
@Serializable
data class SleepScore(
    /** 0–100, higher being more suitable for sleep. */
    val score: Int,
    /** 0–100: how much evidence the figure stands on, kept apart from [score] itself. */
    val confidence: Int,
    val components: SleepComponents,
    val warnings: List<SleepWarning> = emptyList(),
    /** The [SleepAnalysis.MODEL_VERSION] that produced it: an older one is measured again. */
    val modelVersion: Int,
    val durationMs: Long,
    /** When it was calculated (the settings' page says how old a library's figures are). */
    val analyzedAtMs: Long,
    /**
     * Whether it was calculated on the sound as Normalize plays it (1.9): the loudness change playback applies, from
     * the recording's measured loudness and peak. A figure is only valid in the mode it was made for, and the two are
     * kept apart ([SleepScores]): with normalized mode on, a normalized figure is the one used, overriding a plain one;
     * with it off, only plain figures count.
     */
    val normalized: Boolean = false,
    /**
     * The Normalize target (LUFS) a normalized figure was made for. Changing the target changes the gain, so normalized
     * figures are dropped and calculated again when it moves; null for a plain figure.
     */
    val normalizedTarget: Int? = null,
    /** The bounded inputs behind this figure, so labels can be fitted and scores rescored without decoding the audio. */
    val features: SleepFeatures = SleepFeatures(),
    /** How many listener judgments went into the weights used for this figure. */
    val calibrationLabels: Int = 0,
) {
    /** The worst section's own score, 0–100: where the conservative caps and penalties come from. */
    val worstSection: Int get() = components.worstSection

    companion object {
        /** How long a figure is kept, and its exact shape, are frozen with [modelVersion]: see [SleepScores]. */
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        fun encode(score: SleepScore): String = json.encodeToString(serializer(), score)

        fun decode(text: String?): SleepScore? =
            text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/**
 * The parts [SleepScore.score] is built from, 0–100 each, higher being more suitable. [model] is the audio model's own
 * impression (a fixed-weight model over the same measurements, not a probability); [worstSection] is the lowest score
 * of any ten-second part of the song, which the final formula treats as a hard ceiling.
 */
@Serializable
data class SleepComponents(
    /** How quiet the loudest parts are (a loud master disturbs even at a low volume). */
    val quietness: Int,
    /** Predictability of level: little variation, no sudden jumps, no crescendo. */
    val dynamics: Int,
    /** Absence of strong transients: drum hits, slams, spikes. */
    val transients: Int,
    /** Little rhythmic drive: fewer onsets, a weak or absent beat, less percussion. */
    val rhythm: Int,
    /** A simple, steady arrangement: little spectral change, few structural shifts. */
    val complexity: Int,
    /** Gentle rather than tense/dramatic: dark, smooth, consonant-sounding spectra. */
    val mood: Int,
    /** The audio model's impression (see [SleepScore]). */
    val model: Int,
    /** The lowest of any section's own score. */
    val worstSection: Int,
)

/** What in a song's audio pulled its score down. */
@Serializable
enum class SleepWarningKind {
    /** A passage that is loud throughout. */
    LOUD,

    /** A sudden loud event: a hit, a slam, an abrupt rise in level. */
    SUDDEN_LOUD,

    /** A rise over several seconds into a louder passage. */
    CRESCENDO,

    /** An abrupt change of level or timbre between two parts (a hard cut, a drop). */
    ABRUPT_CHANGE,

    /** A dense, busy passage: many onsets, strong beat, high spectral activity. */
    ENERGETIC,

    /** The waveform is clipped: loud, distorted peaks. */
    CLIPPING,

    /** The audio couldn't be analysed with confidence (mostly silence, a very short or partly decoded file). */
    UNSTABLE,
}

/** One place in a song that argues against sleep, with its time, so the UI can point at it. */
@Serializable
data class SleepWarning(
    val kind: SleepWarningKind,
    /** Where the offending passage starts (ms from the beginning of the audio). */
    val startMs: Long,
    /** Where it ends; equal to [startMs] for an event without a length (a hit). */
    val endMs: Long,
    /** 1 noticeable, 2 disruptive, 3 severe. Higher severities cap the score harder. */
    val severity: Int,
)
