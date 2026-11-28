package com.prism.music.analysis

import com.prism.music.playback.MusicKey
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * One analysed song as similarity and sequencing read it: its descriptors, and (when the tagger heard it) its embedding
 * (L2-normalized) and tag profile ([TagVocabulary.profile]).
 */
class SoundEntry(
    val id: String,
    val features: SongFeatures,
    val embedding: FloatArray?,
    val profile: FloatArray?,
) {
    val key: MusicKey? by lazy { features.key?.let(MusicKey::decode) }
}

/**
 * How alike two songs sound, 0 … 1, from whatever both have: the tagger's embedding (timbre, instrumentation, genre),
 * its tag profile (genre, mood, instruments, vocals), the descriptors (energy, brightness, density, beat, dynamics,
 * timbre; z-scored across the library so no one figure's units dominate) and the tempo read octave-free.
 */
class Similarity(
    private val stats: DescriptorStats,
    /**
     * The library's mean embedding: every YAMNet embedding of music shares a large common part (they're non-negative,
     * and all say "music"), so songs are compared by how they differ from the library's average, not by that.
     */
    private val centre: FloatArray? = null,
) {
    /** 0 … 1: how alike two embeddings are, read against the library's average. */
    fun sound(a: FloatArray, b: FloatArray): Double {
        if (centre == null || centre.size != a.size || centre.size != b.size) return ((cosine(a, b) - 0.6) / 0.35).coerceIn(0.0, 1.0)
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { val x = a[i] - centre[i]; val y = b[i] - centre[i]; dot += x * y; na += x * x; nb += y * y }
        val c = if (na <= 0 || nb <= 0) 0.0 else dot / sqrt(na * nb)
        return ((c - CENTRED_LOW) / (CENTRED_HIGH - CENTRED_LOW)).coerceIn(0.0, 1.0)
    }

    fun between(a: SoundEntry, b: SoundEntry): Double {
        var sum = 0.0
        var weights = 0.0
        if (a.embedding != null && b.embedding != null) {
            sum += EMBEDDING * sound(a.embedding, b.embedding)
            weights += EMBEDDING
        }
        if (a.profile != null && b.profile != null) {
            sum += TAGS * ((cosine(a.profile, b.profile) - 0.3) / 0.65).coerceIn(0.0, 1.0)
            weights += TAGS
        }
        val za = stats.z(a.features); val zb = stats.z(b.features)
        var d = 0.0
        for (i in za.indices) d += (za[i] - zb[i]) * (za[i] - zb[i])
        d /= za.size
        sum += DESCRIPTORS * exp(-d)
        weights += DESCRIPTORS
        tempoCloseness(a.features, b.features)?.let { sum += TEMPO * it; weights += TEMPO }
        return if (weights > 0) sum / weights else 0.0
    }

    /**
     * What it costs to go from [a] straight into [b], 0 (seamless) … 1 (a jolt): the tempo read octave-free (only where
     * both have a beat to feel), a key clash (both keys clear), the step from [a]'s ending to [b]'s opening loudness, the
     * brightness step, and how far apart they sound.
     */
    fun transition(a: SoundEntry, b: SoundEntry): Double {
        val fa = a.features; val fb = b.features
        var sum = 0.0; var weights = 0.0
        tempoCloseness(fa, fb)?.let { close ->
            val w = 1.0 * minOf(fa.pulseClarity, fb.pulseClarity)
            sum += w * (1 - close); weights += w
        }
        val ka = a.key; val kb = b.key
        if (ka != null && kb != null && ka.sure && kb.sure) { sum += 0.6 * MusicKey.clash(ka, kb); weights += 0.6 }
        val out = fa.outroLoud ?: fa.lufs
        val into = fb.introLoud ?: fb.lufs
        if (out != null && into != null && out > -60 && into > -60) {
            sum += 1.0 * (abs(into - out) / 10.0).coerceAtMost(1.0); weights += 1.0
        }
        if (fa.centroidHz > 0 && fb.centroidHz > 0) {
            sum += 0.5 * (abs(ln(fb.centroidHz / fa.centroidHz)) / ln(2.0)).coerceAtMost(1.0); weights += 0.5
        }
        if (a.embedding != null && b.embedding != null) {
            sum += 0.8 * (1 - sound(a.embedding, b.embedding)); weights += 0.8
        }
        return if (weights > 0) sum / weights else 0.5
    }

    companion object {
        private const val EMBEDDING = 0.4
        private const val TAGS = 0.2
        private const val DESCRIPTORS = 0.3
        private const val TEMPO = 0.1
        /** Centred cosines of different-sounding music sit around 0 and below; of the same style, up towards 0.7. */
        private const val CENTRED_LOW = -0.1
        private const val CENTRED_HIGH = 0.6

        /** 1 for the same tempo (or its half or double), falling off over ~8 %; null unless both have a clear beat. */
        fun tempoCloseness(a: SongFeatures, b: SongFeatures): Double? {
            val x = a.bpm ?: return null
            val y = b.bpm ?: return null
            if (a.pulseClarity < 0.25f || b.pulseClarity < 0.25f || x <= 0 || y <= 0) return null
            val off = listOf(0.5, 1.0, 2.0).minOf { k -> abs(ln(x * k / y)) }
            return exp(-(off / ln(1.08)) * (off / ln(1.08)))
        }

        fun cosine(a: FloatArray, b: FloatArray): Double {
            var dot = 0.0; var na = 0.0; var nb = 0.0
            for (i in 0 until minOf(a.size, b.size)) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            return if (na <= 0 || nb <= 0) 0.0 else dot / sqrt(na * nb)
        }
    }
}

/**
 * The library's spread of each descriptor [Similarity] compares (mean and spread), so a difference is read as "how
 * unusual" rather than in Hz or LUFS. Worked out from every analysed song; sensible fixed values until there are a few.
 */
class DescriptorStats(private val mean: DoubleArray, private val sd: DoubleArray) {
    fun z(f: SongFeatures): DoubleArray {
        val v = vector(f)
        return DoubleArray(v.size) { (v[it] - mean[it]) / sd[it] }
    }

    companion object {
        /** lufs, log centroid, onsets/s, pulse, percussive, LRA, flatness, MFCC 1–4. */
        fun vector(f: SongFeatures): DoubleArray = doubleArrayOf(
            (f.lufs ?: -14f).toDouble(),
            ln(max(f.centroidHz, 50f).toDouble()),
            f.onsetRate.toDouble(),
            f.pulseClarity.toDouble(),
            f.percussive.toDouble(),
            (f.lra ?: 8f).toDouble(),
            f.flatness.toDouble(),
            f.mfcc.getOrElse(0) { 0f }.toDouble(),
            f.mfcc.getOrElse(1) { 0f }.toDouble(),
            f.mfcc.getOrElse(2) { 0f }.toDouble(),
            f.mfcc.getOrElse(3) { 0f }.toDouble(),
        )

        /** Typical music, for a library with too few songs analysed to say. */
        val DEFAULT = DescriptorStats(
            doubleArrayOf(-11.0, ln(1500.0), 3.0, 0.4, 0.35, 8.0, 0.1, 0.0, 0.0, 0.0, 0.0),
            doubleArrayOf(4.0, 0.4, 1.5, 0.3, 0.15, 4.0, 0.08, 20.0, 10.0, 8.0, 6.0),
        )

        fun of(features: Collection<SongFeatures>): DescriptorStats {
            if (features.size < 20) return DEFAULT
            val vs = features.map(::vector)
            val n = vs.first().size
            val mean = DoubleArray(n) { i -> vs.sumOf { it[i] } / vs.size }
            val sd = DoubleArray(n) { i ->
                sqrt(vs.sumOf { (it[i] - mean[i]) * (it[i] - mean[i]) } / vs.size).takeIf { it > 1e-6 } ?: DEFAULT.sd[i]
            }
            return DescriptorStats(mean, sd)
        }
    }
}
