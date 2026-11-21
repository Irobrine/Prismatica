package com.prism.music.data.sleep

import com.prism.music.playback.sing.Fft
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The magnitudes of a real FFT, worked out from one complex FFT half its length (1.9's speed work).
 *
 * For N real samples x[0..N-1], pack the even ones into the real part and the odd ones into the imaginary part of M=N/2
 * complex samples, transform those with an M-point complex FFT, then combine each bin with its mirror:
 *
 * ```
 * X[k] = (Z[k] + conj(Z[M-k]))/2 + W_N^k (Z[k] - conj(Z[M-k]))/(2i)
 * ```
 *
 * This gives exactly the magnitudes the old N-point complex FFT gave, for a little over half the butterflies. The
 * twiddle table for the combination depends only on N, so it is shared between songs.
 */
internal object RealSpectrum {
    private class Tables(val cos: DoubleArray, val sin: DoubleArray)

    private val tables = ConcurrentHashMap<Int, Tables>()

    private fun tables(size: Int): Tables = tables.getOrPut(size) {
        val half = size / 2
        // W_N^k = exp(-2πik/N) = exp(-πik/M).
        Tables(
            DoubleArray(half) { cos(Math.PI * it / half) },
            DoubleArray(half) { sin(Math.PI * it / half) },
        )
    }

    /**
     * [re] and [im] hold the even and odd windowed samples (M=N/2 each), transformed in place by [fft] (size M).
     * Fills [out] (length M) with |X[k]|, k = 0 until M.
     */
    fun magnitudes(fft: Fft, re: DoubleArray, im: DoubleArray, size: Int, out: DoubleArray) {
        val half = size / 2
        val table = tables(size)
        val mask = half - 1 // half is a power of two
        for (k in 0 until half) {
            val mirror = (half - k) and mask
            val ar = re[k]
            val ai = im[k]
            val br = re[mirror]
            val bi = -im[mirror] // conj(Z[mirror])
            // (Z[k] + conj(Z[mirror])) / 2
            val evenR = (ar + br) * 0.5
            val evenI = (ai + bi) * 0.5
            // (Z[k] - conj(Z[mirror])) / (2i)
            val oddR = (ai - bi) * 0.5
            val oddI = (br - ar) * 0.5
            val c = table.cos[k]
            val s = table.sin[k]
            // W_N^k * odd
            val xR = evenR + c * oddR + s * oddI
            val xI = evenI + c * oddI - s * oddR
            out[k] = sqrt(xR * xR + xI * xI)
        }
    }
}
