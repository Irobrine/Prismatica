package com.prism.music.data.sleep

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A song's sleep suitability (1.9): how unlikely its sound is to disturb someone falling asleep, 0–100, higher being
 * more suitable.
 *
 * The score is deliberately conservative: every figure here is a measurement of the audio itself (never its genre,
 * artist or title), and a song is only scored high when nothing in it — no section, no sudden hit, no rise into a loud
 * passage — argues otherwise. False positives (a disruptive song scored high) are the expensive mistake, so a single
 * bad section can pull a whole song down.
 *
 * [score] is the published figure ([SleepAnalysis]'s formula, version [modelVersion]); [confidence] says how much of
 * the audio could be heard well enough to judge it (a very short song, a partly decoded one, or one that is mostly
 * silence is judged with less certainty). They are kept apart: low confidence lowers [score] a little, but mainly it
 * tells the UI and the filter that the figure is a cautious estimate.
 *
 * [components] are the 0–100 parts the formula is built from, kept for the scoring page and for tuning; they are not
 * meant to be added up by hand (the model part and the conservative caps apply on top). [warnings] are the sections
 * that pulled the score down, with their times, so what a figure is made of is never lost with the samples.
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
