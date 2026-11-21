package com.prism.music

import com.prism.music.data.sleep.SleepAnalysis
import com.prism.music.data.sleep.SleepWarningKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Sleep suitability (1.9): what the formula makes of signals whose character is known — a calm pad, a loud compressed
 * beat, a calm intro into a loud chorus, a crescendo, one slam, quiet-but-erratic, silence — and how its confidence,
 * warnings and determinism behave. The synthetic signals are built to stand in for the human judgements the formula is
 * calibrated against; precision matters more than recall, so the disruptive ones must stay low even when they are quiet.
 */
class SleepSuitabilityTest {
    private val rate = 22_050

    /** A deterministic pseudo-random source (no Random: the same samples every run, on every machine). */
    private class Noise(seed: Long = 1234567) {
        private var s = seed
        fun next(): Double {
            s = (s * 6364136223846793005L + 1442695040888963407L)
            return ((s ushr 11).toDouble() / (1L shl 53).toDouble()) * 2 - 1
        }
    }

    private fun signal(seconds: Int, f: (t: Double, i: Int) -> Double): FloatArray {
        val n = seconds * rate
        return FloatArray(n) { i -> f(i.toDouble() / rate, i).toFloat().coerceIn(-1f, 1f) }
    }

    /** A slow, quiet pad: three low tones, gently breathing, with a little air. */
    private fun calm(seconds: Int = 120, level: Double = 0.045): FloatArray {
        val noise = Noise()
        return signal(seconds) { t, _ ->
            val breathe = 0.85 + 0.15 * sin(2 * PI * 0.055 * t)
            level * breathe * (
                sin(2 * PI * 110.0 * t) + 0.6 * sin(2 * PI * 165.0 * t) + 0.35 * sin(2 * PI * 220.0 * t)
                ) / 1.95 + 0.0015 * noise.next()
        }
    }

    /** A loud, compressed, four-to-the-floor beat with a snare and a bright hat: everything a sleeper doesn't want. */
    private fun loudRock(seconds: Int = 120, from: Double = 0.0, level: Double = 0.55): FloatArray {
        val noise = Noise(999)
        return signal(seconds) { t, _ ->
            if (t < from) return@signal 0.0
            val beat = t * 2.4 // 144 BPM
            val phase = beat - kotlin.math.floor(beat)
            val kick = exp(-phase * 14.0) * sin(2 * PI * 55.0 * t)
            val half = (beat + 0.5) - kotlin.math.floor(beat + 0.5)
            val snare = exp(-half * 18.0) * noise.next() * 0.6
            val hatPhase = (t * 9.6) - kotlin.math.floor(t * 9.6)
            val hat = exp(-hatPhase * 40.0) * noise.next() * 0.25
            val guitar = 0.25 * sin(2 * PI * 196.0 * t) + 0.15 * sin(2 * PI * 293.7 * t) + 0.1 * noise.next()
            // A hard limiter's flat top: loud and squashed, as a modern master sounds.
            (level * (kick + snare + hat + guitar)).coerceIn(-0.72, 0.72)
        }
    }

    /** A quiet, slow, steady hum with no onsets at all. */
    private fun steadyAmbient(seconds: Int = 120): FloatArray =
        signal(seconds) { t, _ -> 0.02 * sin(2 * PI * 87.0 * t) + 0.012 * sin(2 * PI * 130.0 * t) }

    private fun silence(seconds: Int = 60): FloatArray = FloatArray(seconds * rate)

    private fun quietButErratic(seconds: Int = 120): FloatArray {
        val noise = Noise(4242)
        return signal(seconds) { t, _ ->
            val at = (t * 0.5) - kotlin.math.floor(t * 0.5) // a hit every half second
            val hit = if (at < 0.05) exp(-at * 60.0) * noise.next() * 3.0 else 0.0
            0.008 * sin(2 * PI * 150.0 * t) + 0.06 * hit
        }
    }

    private fun score(pcm: FloatArray, expectedSeconds: Int = pcm.size / rate) =
        SleepAnalysis.analyze(pcm, pcm.size, rate, analyzedAtMs = 1_000L, expectedMs = expectedSeconds * 1000L)

    @Test
    fun aCalmQuietTrackScoresHighAndADrivingLoudOneLow() {
        val calm = score(calm())
        val loud = score(loudRock())
        println("calm=${calm.score} conf=${calm.confidence} components=${calm.components} warnings=${calm.warnings.map { it.kind }}")
        println("loud=${loud.score} conf=${loud.confidence} components=${loud.components} warnings=${loud.warnings.map { it.kind }}")
        assertTrue("a calm pad should be suitable: ${calm.score}", calm.score >= 65)
        assertTrue("a loud compressed beat must not be: ${loud.score}", loud.score <= 35)
        assertTrue(calm.confidence >= 70)
        assertTrue(loud.confidence >= 70)
    }

    @Test
    fun aLoudChorusCannotBeHiddenByACalmIntro() {
        val mixed = score(loudRock(seconds = 120, from = 60.0))
        println("calm intro + loud chorus=${mixed.score} components=${mixed.components}")
        assertTrue("the chorus must pull the whole song down: ${mixed.score}", mixed.score <= 45)
        assertTrue("the loud part is reported", mixed.warnings.any { it.kind == SleepWarningKind.LOUD || it.kind == SleepWarningKind.ENERGETIC || it.kind == SleepWarningKind.SUDDEN_LOUD })
    }

    @Test
    fun aCrescendoIntoLoudIsPenalised() {
        // A pad that grows for forty seconds into a loud, dense passage, then stays loud.
        val noise = Noise(7)
        val pcm = FloatArray(120 * rate) { i ->
            val t = i.toDouble() / rate
            val level = if (t < 40) 0.02 + 0.5 * (t / 40.0) else 0.5
            val v = level * (sin(2 * PI * 110 * t) + 0.5 * sin(2 * PI * 220 * t) + 0.2 * noise.next())
            (v * (1 + 2 * (t / 120.0))).coerceIn(-0.8, 0.8).toFloat()
        }
        val s = score(pcm)
        println("crescendo=${s.score} components=${s.components} warnings=${s.warnings}")
        assertTrue("a rise into a loud passage must be capped: ${s.score}", s.score <= 55)
        assertTrue("the rise is reported", s.warnings.any { it.kind == SleepWarningKind.CRESCENDO } || s.warnings.any { it.kind == SleepWarningKind.LOUD })
    }

    @Test
    fun oneSuddenSlamSpoilsAnOtherwiseCalmTrack() {
        val base = calm(120)
        val withSlam = base.copyOf()
        val at = 60 * rate
        for (i in 0 until rate / 2) withSlam[at + i] = (0.9 * exp(-i.toDouble() / rate * 6.0) * sin(2 * PI * 70.0 * i / rate)).toFloat()
        val s = score(withSlam)
        println("slam=${s.score} components=${s.components} warnings=${s.warnings}")
        assertTrue("a slam must cap an otherwise calm song: ${s.score}", s.score <= 55)
        assertTrue("the slam is reported with its time", s.warnings.any { it.kind == SleepWarningKind.SUDDEN_LOUD && abs(it.startMs - 60_000) < 3_000 })
    }

    @Test
    fun quietAndSlowIsNotAutomaticPass() {
        val erratic = score(quietButErratic())
        println("quiet but erratic=${erratic.score} components=${erratic.components} warnings=${erratic.warnings.map { it.kind }}")
        assertTrue("quiet erratic hits are still stimulation: ${erratic.score}", erratic.score <= 60)
    }

    @Test
    fun steadyAmbientIsSuitableButNotPerfectAndSilenceIsNotScoredHigh() {
        val ambient = score(steadyAmbient())
        println("ambient=${ambient.score} components=${ambient.components}")
        val silent = score(silence())
        println("silence=${silent.score} conf=${silent.confidence} warnings=${silent.warnings}")
        assertTrue("a steady hum is suitable: ${ambient.score}", ambient.score >= 60)
        assertTrue("silence has no evidence and is never a high score: ${silent.score}", silent.score <= 20)
        assertTrue("silence is judged with little confidence", silent.confidence <= 20)
        assertTrue(silent.warnings.any { it.kind == SleepWarningKind.UNSTABLE })
    }

    @Test
    fun aSteadyLoudWallIsNotASleepTrack() {
        // Loud, steady and tonal: nothing sudden, nothing dense, just too loud to fall asleep to.
        val wall = signal(90) { t, _ -> 0.45 * sin(2 * PI * 110 * t) + 0.3 * sin(2 * PI * 220 * t) + 0.15 * sin(2 * PI * 330 * t) }
        val s = score(wall)
        println("loud wall=${s.score} components=${s.components} warnings=${s.warnings.map { it.kind }}")
        assertTrue("a loud steady passage is still too loud: ${s.score}", s.score <= 45)
        assertTrue("and the loud sections are named", s.warnings.any { it.kind == SleepWarningKind.LOUD })
    }

    @Test
    fun shortSongsAreLowConfidenceAndCapped() {
        val short = score(calm(20))
        val full = score(calm(120))
        println("short=${short.score} conf=${short.confidence} full=${full.score} conf=${full.confidence}")
        assertTrue("a 20 s clip is judged with less confidence", short.confidence < full.confidence)
        assertTrue("and no higher than what the evidence supports: ${short.score} vs ${full.score}", short.score <= full.score + 5)
    }

    @Test
    fun theScoreIsDeterministicAndCarriesItsVersionAndParts() {
        val pcm = loudRock(30)
        val one = sleep(pcm)
        val two = sleep(pcm)
        assertEquals(one, two)
        assertEquals(SleepAnalysis.MODEL_VERSION, one.modelVersion)
        assertNotNull(one.components)
        // Same samples at a different time: the figure is the audio's, only the timestamp moves.
        assertEquals(one.score, SleepAnalysis.analyze(pcm, pcm.size, rate, analyzedAtMs = 99L).score)
    }

    @Test
    fun anUnreadableRecordingGetsNoCredit() {
        val empty = SleepAnalysis.analyze(FloatArray(0), 0, rate, analyzedAtMs = 1L)
        assertEquals(0, empty.score)
        assertTrue(empty.confidence <= 10)
        assertTrue(empty.warnings.any { it.kind == SleepWarningKind.UNSTABLE })
        val tiny = SleepAnalysis.analyze(FloatArray(100) { 0.1f }, 100, rate, analyzedAtMs = 1L)
        assertTrue("a hundred samples prove nothing: ${tiny.score}", tiny.score <= 10)
    }

    @Test
    fun theFigureDoesNotDependOnHowTheSamplesWereFed() {
        val pcm = loudRock(45)
        val whole = SleepAnalysis.analyze(pcm, pcm.size, rate, analyzedAtMs = 1_000L, expectedMs = 45_000L)
        // The scanner feeds whatever the decoder gives it: odd piece sizes must not change the figure at all.
        val analyzer = SleepAnalysis.Analyzer(rate, expectedMs = 45_000L)
        val chunks = intArrayOf(1, 777, 4096, 13, 22_050)
        var at = 0
        var i = 0
        while (at < pcm.size) {
            val n = minOf(chunks[i++ % chunks.size], pcm.size - at)
            analyzer.feed(pcm, at, n)
            at += n
        }
        val fed = analyzer.finish(1_000L)
        assertEquals(whole, fed)
    }

    @Test
    fun aDecodeThatStoppedShortOfTheSongIsJudgedWithLessConfidenceAndNoMoreScore() {
        val pcm = calm(45)
        val whole = SleepAnalysis.analyze(pcm, pcm.size, rate, analyzedAtMs = 1L, expectedMs = 45_000L)
        val part = SleepAnalysis.analyze(pcm, pcm.size, rate, analyzedAtMs = 1L, expectedMs = 240_000L)
        assertTrue("a third of the song proves less: ${part.confidence} vs ${whole.confidence}", part.confidence < whole.confidence)
        assertTrue("and never scores higher for it: ${part.score} vs ${whole.score}", part.score <= whole.score)
    }

    @Test
    fun threeMinutesAreAnalysedFasterThanTheyPlay() {
        // The regression guard for scoring a library: the analysis must never be slower than the song itself (a phone is
        // several times slower than this JVM; the device test prints its own figure).
        val pcm = loudRock(180)
        SleepAnalysis.analyze(loudRock(10), 10 * rate, rate, analyzedAtMs = 0L) // warm the JIT
        val started = System.nanoTime()
        val s = SleepAnalysis.analyze(pcm, pcm.size, rate, analyzedAtMs = 1L, expectedMs = 180_000L)
        val ms = (System.nanoTime() - started) / 1_000_000
        println("bench: 180 s at $rate Hz in $ms ms (score ${s.score})")
        assertTrue("scoring must be quicker than playing: $ms ms for 180 s", ms < 180_000L)
    }

    private fun sleep(pcm: FloatArray) = SleepAnalysis.analyze(pcm, pcm.size, rate, analyzedAtMs = 1_000L)
}
