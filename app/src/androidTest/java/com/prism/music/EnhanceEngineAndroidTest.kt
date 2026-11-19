package com.prism.music

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.enhance.AlignLanguage
import com.prism.music.data.enhance.AlignmentQualityCheck
import com.prism.music.data.enhance.MelBandSeparator
import com.prism.music.data.enhance.LyricsUnits
import com.prism.music.data.enhance.QwenAligner
import com.prism.music.playback.sing.OrtLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * The two engines on the phone itself, with the same ONNX Runtime build the app downloads: a real Qwen3 aligner pass
 * (a 16 kHz WAV) and a real HTDemucs pass (a 44.1 kHz stereo WAV). The files are read from the app's external files
 * directory under enhance-test (pushed there for this run), and the ONNX engine is copied to internal storage because
 * Android 14 won't execute a library from the shared/external area. Skips when the files aren't there.
 */
@RunWith(AndroidJUnit4::class)
class EnhanceEngineAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val root get() = File(context.filesDir, "enhance-test").takeIf { it.isDirectory }
        ?: File(context.getExternalFilesDir(null), "enhance-test")

    /** Android 16's (and this phone's) app freezer freezes an instrumentation process with no window on screen. */
    private fun keepOnScreen() {
        val intent = android.content.Intent(context, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { context.startActivity(intent) }
        Thread.sleep(2_000)
    }

    private fun engine(): ai.onnxruntime.OrtEnvironment {
        val from = File(root, "runtime/libonnxruntime.so")
        android.util.Log.i("PrismEnhanceTest", "external=${context.getExternalFilesDir(null)} root=$root from=$from exists=${from.exists()}")
        Assume.assumeTrue("enhance-test/runtime/libonnxruntime.so is needed", from.isFile)
        val internal = File(context.filesDir, "enhance-test-ort").apply { mkdirs() }
        val so = File(internal, "libonnxruntime.so")
        android.util.Log.i("PrismEnhanceTest", "engine: copying=${!so.isFile || so.length() != from.length()} from=${from.length()} so=${so.length()}")
        if (!so.isFile || so.length() != from.length()) { from.copyTo(so, overwrite = true); so.setWritable(false, false) }
        android.util.Log.i("PrismEnhanceTest", "engine: copied, loading")
        val env = OrtLoader.environment(internal)
        android.util.Log.i("PrismEnhanceTest", "engine: loaded")
        return env
    }

    @Test fun aTinyModelLoads() {
        val model = File(root, "tiny.onnx")
        Assume.assumeTrue("enhance-test/tiny.onnx is needed", model.isFile)
        val env = engine()
        android.util.Log.i("PrismEnhanceTest", "tiny: creating")
        val session = env.createSession(model.path, ai.onnxruntime.OrtSession.SessionOptions())
        android.util.Log.i("PrismEnhanceTest", "tiny: created")
        session.close()
    }

    @Test fun theQwenAlignerRunsOnThePhone() {
        keepOnScreen()
        val dir = File(root, "qwen")
        val wav = File(root, "audio/en.wav")
        android.util.Log.i("PrismEnhanceTest", "qwen root=$root external=${context.getExternalFilesDir(null)} dir=${dir.isDirectory} wav=${wav.isFile} list=${root.list()?.toList()}")
        Assume.assumeTrue("enhance-test/qwen and audio/en.wav are needed", dir.isDirectory && wav.isFile)
        val audio = readPcm16(wav).first
        val units = LyricsUnits.units(
            "The quick brown fox jumps over the lazy dog. This is a test of forced alignment.",
            AlignLanguage.ENGLISH,
        )
        val start = SystemClock.elapsedRealtime()
        val aligner = QwenAligner.load(engine(), dir)
        val result = aligner.use { it.alignChunk(audio, units, 0L) }
        val ms = SystemClock.elapsedRealtime() - start
        val q = AlignmentQualityCheck.evaluate(result.units, audio, result.confidence)
        println("[android-qwen] ${result.units.size} words in $ms ms, confidence ${result.confidence}, reliable ${q.reliable}; first ${result.units.firstOrNull()}")
        android.util.Log.i("PrismEnhanceTest", "[android-qwen] ${result.units.size} words in $ms ms, confidence ${result.confidence}, reliable ${q.reliable}")
        assertTrue("no words", result.units.isNotEmpty())
        assertTrue("confidence ${result.confidence}", result.confidence > 0.5f)
        assertTrue("the result should be usable: ${q.reasons}", q.reliable)
        var last = -1L
        for (u in result.units) { assertTrue("times run backwards", u.startMs + 80 >= last); last = u.startMs }
    }

    @Test fun vocalSeparationRunsOnThePhone() {
        keepOnScreen()
        val dir = File(root, "ytm")
        val wav = File(root, "audio/mix.wav")
        Assume.assumeTrue("enhance-test/ytm and audio/mix.wav are needed", dir.isDirectory && wav.isFile)
        val (pcm, frames) = readPcm16(wav)
        val src = File(context.cacheDir, "android-sep.src.pcm")
        RandomAccessFile(src, "rw").use { raf ->
            val b = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (v in pcm) b.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            raf.write(b.array())
        }
        val v = ArrayList<Float>(); val i = ArrayList<Float>()
        val start = SystemClock.elapsedRealtime()
        android.util.Log.i("PrismEnhanceTest", "before separator load")
        MelBandSeparator.load(engine(), dir, threads = 4).use { d ->
            android.util.Log.i("PrismEnhanceTest", "after separator load")
            d.separate(src, frames, { false }, { }) { vocals, instrumental -> vocals.forEach { v.add(it) }; instrumental.forEach { i.add(it) } }
        }
        val ms = SystemClock.elapsedRealtime() - start
        src.delete()
        assertEquals(frames.toInt() * 2, v.size)
        assertEquals(frames.toInt() * 2, i.size)
        fun rms(a: List<Float>) = sqrt(a.fold(0.0) { s, x -> s + x * x } / a.size)
        val line = "[android-sep] ${frames / 44_100.0} s in $ms ms; vocals rms ${rms(v)}, instrumental rms ${rms(i)}"
        println(line)
        android.util.Log.i("PrismEnhanceTest", line)
        assertTrue("vocals are silent", rms(v) > 0.005)
        assertTrue("instrumental is silent", rms(i) > 0.005)
    }

    /** A 16-bit WAV as interleaved floats (stereo kept), and its frames. */
    private fun readPcm16(f: File): Pair<FloatArray, Long> {
        val bytes = f.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val channels = b.getShort(22).toInt()
        var p = 12
        while (p < bytes.size - 8) {
            val id = String(bytes, p, 4, Charsets.US_ASCII); val size = b.getInt(p + 4)
            if (id == "data") {
                val frames = size / (2 * channels)
                val out = FloatArray(frames * channels)
                for (k in out.indices) out[k] = b.getShort(p + 8 + k * 2) / 32768f
                return out to frames.toLong()
            }
            p += 8 + size
        }
        error("no data chunk")
    }
}
