package com.prism.music

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.sleep.SleepAnalysis
import com.prism.music.data.sleep.SleepScanner
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin

/**
 * The scoring pass on the phone itself (1.9): how long three minutes of 22.05 kHz audio take to analyse, and what it
 * costs in heap. It is the measurement behind the model choice — a bundled neural tagger (CLAP2, EfficientAT) would be
 * timed the same way — and a guard against a change that makes scoring a library take hours. Deliberately generous: a
 * slow phone may be several times slower than the development one, and the figure is printed for the release notes.
 */
@RunWith(AndroidJUnit4::class)
class SleepAnalysisDeviceTest {
    private val rate = 22_050

    /** Three minutes of a busy mix: a beat, a bass line, a bright and a dark passage, all changing. */
    private fun mix(seconds: Int, sampleRate: Int = rate): FloatArray {
        val n = seconds * sampleRate
        var seed = 20261120L
        fun noise(): Double {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 11).toDouble() / (1L shl 53).toDouble()) * 2 - 1
        }
        return FloatArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val beat = t * 2.0
            val phase = beat - kotlin.math.floor(beat)
            val kick = if (phase < 0.12) sin(2 * PI * 52 * t) * (1 - phase / 0.12) else 0.0
            val bright = if ((t.toInt() / 20) % 2 == 0) 0.08 else 0.02
            (0.25 * kick + 0.1 * sin(2 * PI * 110 * t) + bright * noise() + 0.05 * sin(2 * PI * 440 * t)).coerceIn(-1.0, 1.0).toFloat()
        }
    }

    @Test
    fun threeMinutesAreScoredInSeconds() {
        val seconds = 180
        val pcm = mix(seconds)
        val runtime = Runtime.getRuntime()
        System.gc()
        val before = runtime.totalMemory() - runtime.freeMemory()
        // A short warm-up so the JIT has seen the FFT and the frame loop before the measured pass.
        SleepAnalysis.analyze(mix(10), 10 * rate, rate, analyzedAtMs = 0L)
        val started = System.nanoTime()
        val score = SleepAnalysis.analyze(pcm, pcm.size, rate, analyzedAtMs = 1L, expectedMs = seconds * 1000L)
        val ms = (System.nanoTime() - started) / 1_000_000
        val after = runtime.totalMemory() - runtime.freeMemory()
        println(
            "PrismSleepBench: ${seconds}s at ${rate}Hz scored in $ms ms (${"%.1f".format(seconds * 1_000.0 / ms)}× faster than it plays), " +
                "score ${score.score}, confidence ${score.confidence}, heap +${(after - before) / 1_000_000} MB",
        )
        assertTrue("scoring must not take longer than the song plays: $ms ms for $seconds s", ms < seconds * 1_000L)
        assertTrue(score.score in 0..100)
    }

    @Test
    fun threeMinutesAt44100ShowWhereTheTimeGoes() {
        // Real files are 44.1/48 kHz: twice the frame size and twice the frames per second of the older benchmark's
        // 22.05 kHz. Feed and scoring are timed apart so the next speed change targets the right stage.
        val sampleRate = 44_100
        val seconds = 180
        val pcm = mix(seconds, sampleRate)
        val analyzer = SleepAnalysis.Analyzer(sampleRate, seconds * 1_000L)
        System.gc()
        val feedStart = System.nanoTime()
        var at = 0
        while (at < pcm.size) {
            val step = minOf(16_384, pcm.size - at)
            analyzer.feed(pcm, at, step)
            at += step
        }
        val feedMs = (System.nanoTime() - feedStart) / 1_000_000
        val scoreStart = System.nanoTime()
        val score = analyzer.finish(1L)
        val scoreMs = (System.nanoTime() - scoreStart) / 1_000_000
        println(
            "PrismSleepBench 44.1k: ${seconds}s, feed+FFT $feedMs ms, scoring $scoreMs ms, total ${feedMs + scoreMs} ms " +
                "(${"%.1f".format(seconds * 1_000.0 / (feedMs + scoreMs).coerceAtLeast(1))}× real time), score ${score.score}",
        )
        assertTrue("scoring must not take longer than the song plays", feedMs + scoreMs < seconds * 1_000L)
    }

    @Test
    fun aRealFileIsDecodedAndScoredEndToEnd() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "sleep-device-test.wav")
        writeWav(file, mix(60), rate, channels = 2)
        try {
            val scanner = SleepScanner(context, OkHttpClient(), local = { _, _, _ -> Uri.fromFile(file) })
            val started = System.nanoTime()
            val score = runBlocking { scanner.analyze("device-test", null, network = false) }
            val ms = (System.nanoTime() - started) / 1_000_000
            println("PrismSleepBench e2e: 60s WAV decoded and scored in $ms ms: $score")
            assertNotNull("a readable file is scored", score)
            score!!
            assertEquals(SleepAnalysis.MODEL_VERSION, score.modelVersion)
            assertTrue("the decoded length is about the file's: ${score.durationMs}", score.durationMs in 58_000L..62_000L)
            assertTrue(score.score in 0..100)
            assertTrue(score.confidence > 0)
        } finally {
            file.delete()
        }
    }

    /** A 16-bit PCM WAV of [pcm] (mono samples), [channels] wide: what the scanner reads through the phone's decoder. */
    private fun writeWav(file: File, pcm: FloatArray, rate: Int, channels: Int) {
        val data = pcm.size * channels * 2
        DataOutputStream(BufferedOutputStream(FileOutputStream(file), 1 shl 16)).use { out ->
            fun le32(v: Int) {
                out.write(v and 0xFF); out.write((v ushr 8) and 0xFF); out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
            }
            fun le16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
            out.writeBytes("RIFF"); le32(36 + data); out.writeBytes("WAVE")
            out.writeBytes("fmt "); le32(16); le16(1); le16(channels); le32(rate); le32(rate * channels * 2); le16(channels * 2); le16(16)
            out.writeBytes("data"); le32(data)
            for (v in pcm) {
                val s = (v.coerceIn(-1f, 1f) * 32767f).toInt()
                repeat(channels) { le16(s) }
            }
        }
    }
}
