package com.prism.music.data.enhance

import kotlin.math.cos
import kotlin.math.sin

/**
 * An iterative radix-2 complex FFT of a fixed power-of-two size, on caller-owned arrays: the STFT of a 2048-sample frame
 * and its inverse. Twiddles are computed once; nothing is allocated per call.
 */
internal class ComplexFft(private val n: Int) {
    init {
        require(n > 1 && n and (n - 1) == 0) { "the size must be a power of two" }
    }

    private val cosT = DoubleArray(n / 2) { cos(2.0 * Math.PI * it / n) }
    private val sinT = DoubleArray(n / 2) { sin(2.0 * Math.PI * it / n) }

    fun forward(re: DoubleArray, im: DoubleArray) = transform(re, im, inverse = false)

    fun inverse(re: DoubleArray, im: DoubleArray) {
        transform(re, im, inverse = true)
        val inv = 1.0 / n
        for (i in 0 until n) { re[i] *= inv; im[i] *= inv }
    }

    private fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (i in start until start + half) {
                    val wr = cosT[k * step]
                    val wi = if (inverse) sinT[k * step] else -sinT[k * step]
                    val xr = re[i + half] * wr - im[i + half] * wi
                    val xi = re[i + half] * wi + im[i + half] * wr
                    re[i + half] = re[i] - xr
                    im[i + half] = im[i] - xi
                    re[i] += xr
                    im[i] += xi
                    k++
                }
                start += size
            }
            size = size shl 1
        }
    }
}
