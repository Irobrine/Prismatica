package com.prism.music.analysis

import com.prism.music.playback.KeyDetector
import com.prism.music.playback.LoudnessMeter
import com.prism.music.playback.Tempo
import com.prism.music.playback.TruePeak
import com.prism.music.playback.VideoSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * The sound analysis' descriptors on signals whose answers are known: a soft sustained chord against a loud drum loop,
 * a quiet piece with one crash, two levels for the loudness range, and an inter-sample peak.
 */
class DescriptorsTest {
    private val rate = 11025

    /** The whole scan's path for [pcm]: bands, tempo, chroma and loudness, as MixTempo hands them over. */
    private fun features(pcm: FloatArray): SongFeatures {
        val bands = VideoSync.bandFeatures(pcm, rate, pcm.size)
        val frameMs = (rate / 100) * 1000.0 / rate
        val tempo = Tempo.analyze(bands, frameMs, VideoSync.WINDOW / 2 * 1000.0 / rate)
        val chroma = KeyDetector.chroma(pcm, rate, pcm.size)
        val meter = LoudnessMeter(rate, 1)
        for (v in pcm) meter.sample(v)
        return Descriptors.extract(
            Descriptors.Input(pcm, pcm.size, rate, bands, frameMs, tempo, chroma?.let(KeyDetector::best), chroma, meter.stats(), null, null),
        )
    }

    /** A soft A-minor chord swelling slowly, no attacks. */
    private fun calm(seconds: Int = 60): FloatArray = FloatArray(seconds * rate) { i ->
        val t = i.toDouble() / rate
        val swell = 0.6 + 0.4 * sin(2 * PI * t / 12)
        (0.05 * swell * (sin(2 * PI * 220.0 * t) + sin(2 * PI * 261.63 * t) + sin(2 * PI * 329.63 * t)) / 3).toFloat()
    }

    /** A loud 128 BPM loop: a kick on every beat, noise hats between, a bright buzz under it. */
    private fun busy(seconds: Int = 60): FloatArray {
        val r = Random(7)
        val beat = 60.0 / 128
        return FloatArray(seconds * rate) { i ->
            val t = i.toDouble() / rate
            val sinceBeat = t % beat
            val sinceHat = t % (beat / 2)
            val kick = 0.8 * exp(-sinceBeat * 30) * sin(2 * PI * 60 * sinceBeat)
            val hat = 0.3 * exp(-sinceHat * 80) * (r.nextDouble() * 2 - 1)
            val buzz = 0.15 * sin(2 * PI * 1760 * t)
            (kick + hat + buzz).toFloat().coerceIn(-1f, 1f)
        }
    }

    @Test fun aDrumLoopIsBusierBrighterAndMorePercussiveThanASoftChord() {
        val soft = features(calm())
        val loop = features(busy())
        assertTrue("onsets ${loop.onsetRate} vs ${soft.onsetRate}", loop.onsetRate > soft.onsetRate + 1)
        assertTrue("percussive ${loop.percussive} vs ${soft.percussive}", loop.percussive > soft.percussive)
        assertTrue("pulse ${loop.pulseClarity} vs ${soft.pulseClarity}", loop.pulseClarity > soft.pulseClarity)
        assertTrue("centroid ${loop.centroidHz} vs ${soft.centroidHz}", loop.centroidHz > soft.centroidHz)
        assertTrue("loudness ${loop.lufs} vs ${soft.lufs}", loop.lufs!! > soft.lufs!! + 10)
        assertTrue("danceability ${loop.danceability}", loop.danceability > soft.danceability)
        // The loop's tempo, octave-free.
        val bpm = loop.bpm!!
        assertTrue("bpm $bpm", listOf(64.0, 128.0, 256.0).any { kotlin.math.abs(bpm - it) / it < 0.03 })
        assertEquals(13, loop.mfcc.size)
        assertTrue(loop.segLoud.size >= 5)
    }

    @Test fun oneCrashInAQuietPieceIsASuddenRiseAndCapsTheSleepScore() {
        val r = Random(3)
        val pcm = calm(60)
        // A crash at 30 s: half a second of loud noise.
        for (i in 30 * rate until (30.5 * rate).toInt()) pcm[i] = (0.8 * (r.nextDouble() * 2 - 1)).toFloat()
        val f = features(pcm)
        assertTrue("rises ${f.suddenPerMinute}/min, worst ${f.worstRise}", f.suddenPerMinute > 0 && f.worstRise >= SleepScore.CAP_RISE)
        val plain = features(calm(60))
        assertEquals(0f, plain.suddenPerMinute, 0f)
        // The crash holds the score down (to the cap, or below it by its own share).
        val s = SleepScore.of(f)
        assertTrue("score ${s.score}", s.score <= SleepScore.CAP)
        assertTrue(s.against().any { it.factor == SleepFactor.SUDDEN })
        assertTrue(SleepScore.of(plain).score > s.score)
    }

    /**
     * A quiet plucked piece with rests between its phrases: the note after a rest is at the song's own level, so it's no
     * jolt. Measured against the rest alone (v2) each one read as a +20 LU rise; real exports had a harp lullaby at
     * seven a minute. A crash after a quiet stretch is still one ([oneCrashInAQuietPieceIsASuddenRiseAndCapsTheSleepScore]).
     */
    @Test fun aNoteAfterARestIsNoSuddenRise() {
        val pcm = FloatArray(60 * rate) { i ->
            val t = i.toDouble() / rate
            // A 1.5 s note, then 0.7 s of silence, again and again.
            val inNote = t % 2.2
            if (inNote >= 1.5) 0f
            else (0.05 * exp(-inNote * 1.5) * sin(2 * PI * 330 * t)).toFloat()
        }
        val f = features(pcm)
        assertEquals("rises ${f.suddenPerMinute}/min, worst ${f.worstRise}", 0f, f.suddenPerMinute, 0f)
        assertTrue("worst ${f.worstRise}", f.worstRise < Descriptors.SUDDEN_LU)
    }

    @Test fun theLoudnessRangeOfTwoLevelsTwentyDecibelsApart() {
        val pcm = FloatArray(120 * rate) { i ->
            val t = i.toDouble() / rate
            val loud = ((i / (20 * rate)) % 2) == 1
            ((if (loud) 0.3 else 0.03) * sin(2 * PI * 1000 * t)).toFloat()
        }
        val meter = LoudnessMeter(rate, 1)
        for (v in pcm) meter.sample(v)
        val st = meter.stats()
        assertNotNull(st.lra)
        assertEquals(20f, st.lra!!, 3f)
        assertTrue(st.shortTerm.size in 110..120)
        assertEquals(st.momentary.size.toFloat(), 1196f, 10f)
        assertTrue(st.maxMomentary!! > st.integrated!!)
    }

    @Test fun theTruePeakIsFoundBetweenSamples() {
        // A tone at a quarter of the sample rate, a quarter cycle off: every sample is ±0.707 and the peaks fall between.
        val n = 4000
        val x = FloatArray(n) { sin(PI / 2 * it + PI / 4).toFloat() }
        val samplePeak = x.maxOf { kotlin.math.abs(it) }
        assertEquals(0.707f, samplePeak, 0.01f)
        val tp = TruePeak.scan(x, n, 1, 0f, samplePeak)
        assertTrue("true peak $tp", tp > 0.95f && tp < 1.1f)
    }

    @Test fun danceabilityNeedsABeatAtADancingTempo() {
        assertEquals(0f, Descriptors.danceability(null, 0.9f, 1f, 0.5f), 0f)
        val dance = Descriptors.danceability(120.0, 0.9f, 1f, 0.6f)
        val slow = Descriptors.danceability(50.0, 0.9f, 1f, 0.6f)
        assertTrue(dance > 0.7f)
        // 50 BPM is read at 100 (octave-free): the octave a dancer would move to.
        assertTrue(slow > 0.7f)
        assertTrue(Descriptors.danceability(120.0, 0.1f, 1f, 0.6f) < 0.2f)
    }
}
