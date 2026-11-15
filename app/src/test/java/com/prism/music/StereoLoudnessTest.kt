package com.prism.music

import com.prism.music.playback.LoudnessMeter
import com.prism.music.playback.MixTempo
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Normalize hears every channel (1.8.5): measuring a mono fold-down (left and right averaged) and adding a flat 3 dB read
 * a wide or hard-panned mix up to 3 dB quieter than it plays, so those songs were lifted too far. The scan now measures
 * each channel as the song decodes ([LoudnessMeter]).
 */
class StereoLoudnessTest {
    private val rate = 11_025

    private fun tone(amplitude: Double, seconds: Double = 10.0, hz: Double = 997.0) =
        FloatArray((seconds * rate).toInt()) { i -> (amplitude * sin(2 * PI * hz * i / rate)).toFloat() }

    /** [left] and [right] through the meter, as a scanned song's channels are. */
    private fun stereo(left: FloatArray, right: FloatArray): Float {
        val meter = LoudnessMeter(rate, 2)
        val frame = FloatArray(2)
        for (i in left.indices) {
            frame[0] = left[i]
            frame[1] = right[i]
            meter.frame(frame)
        }
        return meter.loudness()!!
    }

    @Test fun aCentredToneIsThreeDbOverOneChannel() {
        val one = tone(0.1)
        val mono = MixTempo.integratedLoudness(one, rate)!!
        assertEquals((mono + 3.0).toDouble(), stereo(one, one).toDouble(), 0.15)
    }

    @Test fun aHardPannedToneReadsAsItPlays() {
        // A stereo measurement sums both channels: the tone in one channel is -23 LUFS, where the old mono fold-down
        // (half in each, +3 dB) read it -26.
        val left = tone(0.1)
        val right = FloatArray(left.size)
        val measured = stereo(left, right)
        assertEquals(-23.0, measured.toDouble(), 0.5)
        val fold = FloatArray(left.size) { (left[it] + right[it]) / 2 }
        assertEquals(3.0, (measured - (MixTempo.integratedLoudness(fold, rate)!! + 3f)).toDouble(), 0.3)
    }

    @Test fun aWideMixCountsBothChannels() {
        // Two uncorrelated tones at the same level: both channels' power adds up (as both are heard); a fold-down's
        // would not.
        val left = tone(0.1, hz = 997.0)
        val right = tone(0.1, hz = 1234.0)
        assertEquals(-20.0, stereo(left, right).toDouble(), 0.6)
    }
}
