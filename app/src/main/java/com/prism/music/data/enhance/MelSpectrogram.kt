package com.prism.music.data.enhance

import android.os.Process
import com.prism.music.playback.sing.Fft
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/**
 * Whisper's log-mel front end at 16 kHz, the one Qwen3-ASR's aligner expects: a 400-point STFT (hop 160, periodic Hann,
 * reflected at both ends), 128 Slaney mel bands over 201 bins, log10 with a floor, clamped to the loudest frame's -8,
 * then scaled to (x + 4) / 4. The filterbank and window are the model's own constants.
 *
 * A 400-point transform isn't a power of two, so it is done exactly with Bluestein's algorithm on a 1024-point FFT:
 * direct evaluation of 400 bins for every 10 ms of a song would cost far more than the encoder that follows it.
 */
class MelSpectrogram private constructor(
    private val filters: FloatArray,
    private val window: FloatArray,
    private val fft: Fft,
    private val chirpRe: DoubleArray,
    private val chirpIm: DoubleArray,
    private val kernelRe: DoubleArray,
    private val kernelIm: DoubleArray,
) {
    private val workers = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    private val pool by lazy {
        java.util.concurrent.Executors.newFixedThreadPool(workers) { r ->
            Thread(r, "PrismAlignMel").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
        }
    }

    /** [128, T] features for [audio] (mono, 16 kHz), row-major; T = floor(frames / hop). */
    fun compute(audio: FloatArray): FloatArray {
        val frames = audio.size / HOP
        if (frames <= 0) return FloatArray(0)
        val out = FloatArray(N_MELS * frames)
        val maxes = FloatArray(workers)
        val threads = minOf(workers, frames)
        if (threads <= 1) {
            work(audio, 0, frames, out, maxes, 0)
        } else {
            val callables = (0 until threads).map { w ->
                java.util.concurrent.Callable {
                    val from = w * frames / threads
                    val to = (w + 1) * frames / threads
                    work(audio, from, to, out, maxes, w)
                }
            }
            pool.invokeAll(callables).forEach { it.get() }
        }
        val top = maxes.max()
        // log10 values are stored in place; shift and scale them into the model's range.
        for (i in out.indices) {
            val v = max(out[i], top - 8f)
            out[i] = (v + 4f) / 4f
        }
        return out
    }

    private fun work(audio: FloatArray, from: Int, to: Int, out: FloatArray, maxes: FloatArray, slot: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val n = audio.size
        val frames = n / HOP
        val re = DoubleArray(M)
        val im = DoubleArray(M)
        val frame = FloatArray(N_FFT)
        val power = FloatArray(BINS)
        var top = -Float.MAX_VALUE
        for (f in from until to) {
            val start = f * HOP - PAD
            for (i in 0 until N_FFT) {
                // reflect: -1 -> sample 1, -2 -> sample 2, and the same at the far end.
                val at = reflect(start + i, n)
                frame[i] = audio[at] * window[i]
            }
            java.util.Arrays.fill(re, 0.0); java.util.Arrays.fill(im, 0.0)
            // a[m] = x[m] * w[m]
            for (i in 0 until N) {
                val a = chirpRe[i]; val b = chirpIm[i]
                re[i] = frame[i] * a; im[i] = frame[i] * b
            }
            fft.transform(re, im)
            for (k in 0 until M) {
                val ar = re[k]; val ai = im[k]
                val br = kernelRe[k]; val bi = kernelIm[k]
                re[k] = ar * br - ai * bi
                im[k] = ar * bi + ai * br
            }
            fft.transform(re, im, inverse = true)
            for (k in 0 until BINS) {
                val cRe = re[k]; val cIm = im[k]
                // X[k] = w[k] * c[k]
                val xr = cRe * chirpRe[k] - cIm * chirpIm[k]
                val xi = cRe * chirpIm[k] + cIm * chirpRe[k]
                power[k] = (xr * xr + xi * xi).toFloat()
            }
            for (m in 0 until N_MELS) {
                var sum = 0f
                val off = m * BINS
                for (k in 0 until BINS) sum += filters[off + k] * power[k]
                val v = log10(max(sum, 1e-10f))
                // [mel][frame], the layout the model's input_features tensor has.
                out[m * frames + f] = v
                if (v > top) top = v
            }
        }
        maxes[slot] = top
    }

    private fun reflect(i: Int, n: Int): Int {
        if (n <= 1) return 0
        var x = i
        // choose a symmetric mapping because only a few hundred samples at each edge are outside the signal
        while (x < 0 || x >= n) {
            x = if (x < 0) -x else 2 * (n - 1) - x
        }
        return x
    }

    companion object {
        const val RATE = 16_000
        const val N_FFT = 400
        const val HOP = 160
        const val N_MELS = 128
        const val BINS = N_FFT / 2 + 1
        private const val N = N_FFT
        private const val M = 1024
        private const val PAD = N_FFT / 2

        /** Loads the model's mel table and window (128 x 201 and 400 floats, little-endian). */
        fun load(dir: File): MelSpectrogram {
            val filters = readFloats(File(dir, "mel_filters.f32"), N_MELS * BINS)
            val window = readFloats(File(dir, "hann_400.f32"), N_FFT)
            return build(filters, window)
        }

        /** For tests: the reference tables are plain floats. */
        internal fun build(filters: FloatArray, window: FloatArray): MelSpectrogram {
            require(filters.size == N_MELS * BINS) { "mel filterbank size ${filters.size}" }
            require(window.size == N_FFT) { "window size ${window.size}" }
            // Bluestein: w[k] = exp(-i π k² / N), b[j] = conj(w[j]) laid out for a linear convolution.
            val chirpRe = DoubleArray(N); val chirpIm = DoubleArray(N)
            for (k in 0 until N) {
                val a = -PI * (k.toLong() * k % (2L * N)) / N
                chirpRe[k] = cos(a); chirpIm[k] = sin(a)
            }
            val fft = Fft(M)
            val kRe = DoubleArray(M); val kIm = DoubleArray(M)
            kRe[0] = 1.0
            for (j in 1 until N) {
                kRe[j] = chirpRe[j]; kIm[j] = -chirpIm[j]
                kRe[M - j] = chirpRe[j]; kIm[M - j] = -chirpIm[j]
            }
            fft.transform(kRe, kIm)
            return MelSpectrogram(filters, window, fft, chirpRe, chirpIm, kRe, kIm)
        }

        private fun readFloats(f: File, count: Int): FloatArray {
            val bytes = f.readBytes()
            require(bytes.size >= count * 4) { "${f.name} is ${bytes.size} bytes, needs ${count * 4}" }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(count) { b.float }
        }
    }
}
