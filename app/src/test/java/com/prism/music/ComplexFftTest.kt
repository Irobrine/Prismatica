package com.prism.music

import com.prism.music.data.enhance.ComplexFft
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The 2048-point transform the separator's STFT is built on: a delta, a pure tone, and a round trip. */
class ComplexFftTest {
    private val n = 2048

    @Test fun aDeltaComesOutFlat() {
        val re = DoubleArray(n); val im = DoubleArray(n)
        re[0] = 1.0
        ComplexFft(n).forward(re, im)
        for (k in 0 until n) {
            assertEquals("bin $k real", 1.0, re[k], 1e-9)
            assertEquals("bin $k imaginary", 0.0, im[k], 1e-9)
        }
    }

    @Test fun aToneLandsOnItsOwnBins() {
        val k0 = 37
        val re = DoubleArray(n) { cos(2 * PI * k0 * it / n) }
        val im = DoubleArray(n) { sin(2 * PI * k0 * it / n) }
        ComplexFft(n).forward(re, im)
        assertEquals("the tone's bin", n.toDouble(), re[k0], 1e-6)
        assertEquals("nowhere else", 0.0, re[(k0 + 100) % n], 1e-6)
        assertEquals("and nothing at the negative frequency", 0.0, re[n - k0], 1e-6)
    }

    @Test fun inverseUndoesForward() {
        val re = DoubleArray(n) { sin(it * 0.37) + cos(it * 0.011) }
        val im = DoubleArray(n) { cos(it * 0.19) * 0.25 }
        val wantRe = re.copyOf(); val wantIm = im.copyOf()
        val fft = ComplexFft(n)
        fft.forward(re, im)
        fft.inverse(re, im)
        for (i in 0 until n) {
            assertEquals("sample $i", wantRe[i], re[i], 1e-9)
            assertEquals("sample $i imaginary", wantIm[i], im[i], 1e-9)
        }
    }
}
