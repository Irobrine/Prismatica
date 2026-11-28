package com.prism.music.analysis

import kotlinx.serialization.Serializable

/**
 * What [Descriptors] read from a song's sound: one versioned record, stored as JSON in `song_analysis`
 * (docs/audio-analysis.md, section 2, says which audio each figure is read from). Loudness, peaks and dynamics describe
 * the recording before Normalize's gain, the EQ or any effect.
 *
 * Every figure is a plain number so the sleep score, similarity and tags can be explained and recomputed without the
 * audio. Lists are per 10-second segment of the music (leading and trailing silence left out).
 */
@Serializable
data class SongFeatures(
    /** [Descriptors.VERSION] these were read with. */
    val v: Int = Descriptors.VERSION,
    /** The music's length (s), from first to last sound. */
    val seconds: Float = 0f,

    // ---- Loudness and dynamics (every channel, before normalization)
    /** Integrated loudness, LUFS (BS.1770). */
    val lufs: Float? = null,
    /** Loudness range, LU (EBU Tech 3342: short-term loudness, 10th to 95th percentile). */
    val lra: Float? = null,
    /** The loudest 400 ms (momentary loudness), LUFS. */
    val maxMomentary: Float? = null,
    /** Sample peak, dBFS. */
    val peakDb: Float? = null,
    /** True peak (4× oversampled), dBTP. */
    val truePeakDb: Float? = null,
    /** Mean distance of the short-term loudness from the integrated loudness, LU (as Essentia's DynamicComplexity). */
    val dynamicComplexity: Float? = null,
    /** Sudden loud moments per minute: a rise of [Descriptors.SUDDEN_LU] or more over the three seconds before it. */
    val suddenPerMinute: Float = 0f,
    /** The biggest such rise, LU. */
    val worstRise: Float = 0f,

    // ---- Rhythm
    /** Tempo as heard (Mix's scan); null with no clear beat. */
    val bpm: Float? = null,
    /** 0 (no pulse) … 1 (a hard, clear beat), from how far the tempo stood out. */
    val pulseClarity: Float = 0f,
    /** 0 … 1: how much the local tempo agrees across the song (null: too short or no pulse to tell). */
    val tempoStability: Float? = null,
    /** Note and drum onsets per second (event density). */
    val onsetRate: Float = 0f,
    /** 0 … 1: the share of the sound that is percussive (harmonic/percussive split of the spectrogram). */
    val percussive: Float = 0f,
    /** 0 … 1: a steady, clear beat at a walking-to-dancing tempo. */
    val danceability: Float = 0f,

    // ---- Timbre (mono, 0–5.5 kHz)
    /** Spectral centroid, Hz (brightness). */
    val centroidHz: Float = 0f,
    /** Where 85 % of the energy lies below, Hz. */
    val rolloffHz: Float = 0f,
    /** Spectral flatness, 0 (tonal) … 1 (noise). */
    val flatness: Float = 0f,
    /** Mean spectral flux (how fast the spectrum changes), normalized. */
    val flux: Float = 0f,
    /** Zero crossings per sample. */
    val zcr: Float = 0f,
    /** Share of the energy below 150 Hz. */
    val lowRatio: Float = 0f,
    /** Share of the energy above 1.5 kHz (brightness as MIRtoolbox measures it). */
    val brightShare: Float = 0f,
    /** MFCC 1–13 (mean over the song): the shape of the spectrum. */
    val mfcc: List<Float> = emptyList(),
    /** MFCC 1–13 (spread over the song). */
    val mfccSd: List<Float> = emptyList(),

    // ---- Harmony
    /** The key ([com.prism.music.playback.MusicKey.encode]), when clear. */
    val key: String? = null,
    /** 0 … 1: how strongly a few notes dominate (tonal vs. noisy / percussive). */
    val tonalClarity: Float? = null,
    /** Chroma C … B, summing to 1. */
    val chroma: List<Float> = emptyList(),

    // ---- Across the song (10 s segments)
    /** Short-term loudness per segment, LUFS. */
    val segLoud: List<Float> = emptyList(),
    /** Spectral centroid per segment, Hz. */
    val segBright: List<Float> = emptyList(),
    /** Onsets per second per segment. */
    val segOnsets: List<Float> = emptyList(),
) {
    /** The first and last 20 s's loudness (transitions): what a song starts and ends on. */
    val introLoud: Float? get() = segLoud.take(2).takeIf { it.isNotEmpty() }?.average()?.toFloat()
    val outroLoud: Float? get() = segLoud.takeLast(2).takeIf { it.isNotEmpty() }?.average()?.toFloat()
}

/** A song's measured figures as a line in units, the same in every language ("92 BPM · A minor (8A) · -11.2 LUFS"). */
object SoundFacts {
    fun line(e: SoundEntry): String {
        val f = e.features
        return listOfNotNull(
            f.bpm?.let { "${kotlin.math.round(it).toInt()} BPM" },
            e.key?.takeIf { it.sure }?.let { "${it.name} (${it.camelot})" },
            f.lufs?.let { "%.1f LUFS".format(it) },
            f.lra?.let { "LRA %.1f LU".format(it) },
            f.truePeakDb?.let { "%.1f dBTP".format(it) },
        ).joinToString(" · ")
    }

    /** The tagger that heard a song, by its own name ("YAMNet 1"). */
    fun tagger(id: String, version: Int?): String = (if (id == YamnetTagger.ID) "YAMNet" else id) + (version?.let { " $it" } ?: "")
}
