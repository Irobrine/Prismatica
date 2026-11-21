package com.prism.music

import com.prism.music.data.sleep.RealSpectrum
import com.prism.music.playback.sing.Fft
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Speed work (1.9): the analyzer now gets its N-point spectrum from one N/2-point complex FFT. This pins the result to
 * the magnitudes the old N-point complex FFT produced, so the faster path measures the same audio.
 */
class RealSpectrumTest {
    private var seed = 1234567L
    private fun next(): Double {
        seed = seed * 6364136223846793005L + 1442695040888963407L
        return ((seed ushr 11).toDouble() / (1L shl 53).toDouble()) * 2 - 1
    }

    @Test fun theRealFftMagnitudesAreTheComplexOnes() {
        for (size in intArrayOf(256, 512, 1024, 2048)) {
            seed = 1234567L
            val window = DoubleArray(size) { 0.5 - 0.5 * cos(2 * Math.PI * it / (size - 1)) }
            val samples = DoubleArray(size) { next() }
            val fullRe = DoubleArray(size) { samples[it] * window[it] }
            val fullIm = DoubleArray(size)
            Fft(size).transform(fullRe, fullIm)
            val reference = DoubleArray(size / 2) { k -> sqrt(fullRe[k] * fullRe[k] + fullIm[k] * fullIm[k]) }

            val half = size / 2
            val even = DoubleArray(half) { samples[2 * it] * window[2 * it] }
            val odd = DoubleArray(half) { samples[2 * it + 1] * window[2 * it + 1] }
            val fft = Fft(half)
            fft.transform(even, odd)
            val actual = DoubleArray(half)
            RealSpectrum.magnitudes(fft, even, odd, size, actual)

            for (k in 0 until half) {
                assertEquals("N=$size bin $k", reference[k], actual[k], 1e-9 * reference[k] + 1e-12)
            }
        }
    }
}
