package com.prism.music.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * A decoder's output buffers turned into mono at a lower rate, as the scans read them: a buffer at a time in bulk into
 * one reused array, rather than a sample at a time through the buffer (a tenth of a scan's own work on the A16). Keeps its
 * place between buffers. Also notes the loudest sample it saw, and, when a [meter] is set, hands it every channel (the
 * loudness hears the song as it plays, not folded to mono first).
 */
internal class Downmix {
    private var shorts = ShortArray(4096)
    private var floats = FloatArray(4096)
    private var acc = 0f
    private var n = 0
    var peak = 0f
        private set

    /** Where each channel's loudness is measured as it comes; null when only the mono is wanted. */
    var meter: LoudnessMeter? = null
    private val channelAcc = FloatArray(MAX_LOUDNESS_CHANNELS)

    /** The loudest point between samples too ([TruePeak]), when [measureTruePeak] is on; 0 … 1 (and over, for overs). */
    var truePeak = 0f
        private set
    var measureTruePeak = false

    /** Hears the mono at the decoder's own rate, before it's decimated (the sound analysis' tagger windows). */
    var tap: ((Float) -> Unit)? = null

    /** Starts over at a new place in the song (the peak is kept). */
    fun restart() {
        acc = 0f
        n = 0
        for (i in channelAcc.indices) channelAcc[i] = 0f
    }

    /**
     * Reads [frames] frames of [channels] channels at [buf]'s position, and calls [out] with every [factor]th average.
     * [out] is inlined, so a sample costs no call.
     */
    inline fun add(buf: ByteBuffer, frames: Int, channels: Int, isFloat: Boolean, factor: Int, out: (Float) -> Unit) {
        val samples = frames * channels
        val data = samplesOf(buf.order(ByteOrder.nativeOrder()), samples, isFloat)
        if (measureTruePeak) truePeak = TruePeak.scan(data, frames, channels, truePeak, peak)
        val t = tap
        var i = 0
        for (f in 0 until frames) {
            var m = 0f
            val at = i
            for (c in 0 until channels) m += data[i++]
            if (t != null) t(m / channels)
            push(m / channels, data, at, channels)
            if (ready(factor)) out(take(factor))
        }
    }

    /** Keeps the mono average, and each channel's sum for the [meter] when one is set. */
    fun push(mono: Float, data: FloatArray, at: Int, channels: Int) {
        acc += mono
        n++
        val m = meter ?: return
        val ch = minOf(channels, m.channels)
        for (c in 0 until ch) channelAcc[c] += data[at + c]
    }

    fun ready(factor: Int) = n == factor

    fun take(factor: Int): Float {
        val v = acc / factor
        meter?.let { m ->
            m.frame(channelAcc, factor)
            for (c in 0 until m.channels) channelAcc[c] = 0f
        }
        acc = 0f
        n = 0
        return v
    }

    /** The next [samples] samples as floats (-1…1), in one array that's reused. */
    fun samplesOf(buf: ByteBuffer, samples: Int, isFloat: Boolean): FloatArray {
        if (floats.size < samples) floats = FloatArray(samples)
        val f = floats
        var top = peak
        if (isFloat) {
            buf.asFloatBuffer().get(f, 0, samples)
            for (i in 0 until samples) { val v = abs(f[i]); if (v > top) top = v }
        } else {
            if (shorts.size < samples) shorts = ShortArray(samples)
            val s = shorts
            buf.asShortBuffer().get(s, 0, samples)
            for (i in 0 until samples) { val v = s[i] / 32768f; f[i] = v; val a = abs(v); if (a > top) top = a }
        }
        peak = top
        return f
    }
}

/**
 * True peak after BS.1770-4's annex 2: each channel oversampled 4× (a windowed sinc, 16 taps a phase) and its largest
 * magnitude taken. Only worked out around samples within 6 dB of the loudest point found so far — an inter-sample over
 * is never more than a few dB above the samples beside it — so a whole song costs a small fraction of its decoding.
 * The buffer's first and last few samples are read with the taps that fall inside it.
 */
internal object TruePeak {
    private const val PHASES = 4
    private const val HALF = 8
    /** [phase][tap]: the interpolation filter for the points 1/4, 2/4 and 3/4 of the way to the next sample. */
    private val filters = Array(PHASES) { p ->
        val frac = p.toDouble() / PHASES
        FloatArray(2 * HALF) { k ->
            val x = (k - HALF + 1) - frac
            val sinc = if (x == 0.0) 1.0 else Math.sin(Math.PI * x) / (Math.PI * x)
            val w = 0.5 + 0.5 * Math.cos(Math.PI * x / (HALF + 0.5))
            (sinc * w).toFloat()
        }
    }

    /** The true peak so far after [frames] frames of interleaved [data]; [sofar] the true peak before them. */
    fun scan(data: FloatArray, frames: Int, channels: Int, sofar: Float, samplePeak: Float): Float {
        var top = maxOf(sofar, samplePeak)
        // Within 3 dB of the loudest point so far: overs further below their neighbours than that don't happen in music.
        val near = 0.7f * top
        for (c in 0 until channels) {
            for (f in 0 until frames - 1) {
                val a = abs(data[f * channels + c]); val b = abs(data[(f + 1) * channels + c])
                if (a < near && b < near) continue
                for (p in 1 until PHASES) {
                    val h = filters[p]
                    var acc = 0f
                    for (k in 0 until 2 * HALF) {
                        val idx = f + k - HALF + 1
                        if (idx < 0 || idx >= frames) continue
                        acc += data[idx * channels + c] * h[k]
                    }
                    val v = abs(acc)
                    if (v > top) top = v
                }
            }
        }
        return top
    }
}
