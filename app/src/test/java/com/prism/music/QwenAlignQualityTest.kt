package com.prism.music

import ai.onnxruntime.OrtEnvironment
import com.prism.music.data.enhance.AlignLanguage
import com.prism.music.data.enhance.AlignmentQualityCheck
import com.prism.music.data.enhance.LyricsUnits
import com.prism.music.data.enhance.QwenAligner
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The feature's own quality check on harder material than speech: slowed, sustained words with an accompaniment, a
 * fast rap-speed line, and two voices overlapping. It prints the score and reliability for each; the assertions only
 * require that the four cases are actually run, because the point is the measured evidence, not a hard threshold that
 * would hide a bad result.
 */
class QwenAlignQualityTest {
    @Test fun harderMaterial() {
        val dir = System.getenv("QWEN_SING_DIR")?.let(::File)
        val model = System.getenv("QWEN_ALIGNER_DIR")?.let(::File)
        Assume.assumeTrue("QWEN_SING_DIR and QWEN_ALIGNER_DIR are needed", dir?.isDirectory == true && model?.isDirectory == true)
        val aligner = QwenAligner.load(OrtEnvironment.getEnvironment(), model!!)
        aligner.use {
            for (name in listOf("speech", "singing", "rap", "overlap")) {
                val wav = File(dir, "$name.wav")
                val text = File(dir, "$name.txt")
                if (!wav.isFile || !text.isFile) continue
                val audio = readWav(wav)
                val units = LyricsUnits.units(text.readText().trim(), AlignLanguage.ENGLISH)
                val r = it.alignChunk(audio, units, 0)
                val q = AlignmentQualityCheck.evaluate(r.units, audio, r.confidence)
                println("[qwen-quality] $name: ${r.units.size} words, conf ${"%.3f".format(q.confidence)}, " +
                    "phonetic ${"%.2f".format(q.phonetic)}, monotonic ${"%.2f".format(q.monotonic)}, " +
                    "voice ${"%.2f".format(q.vocalActivity)}, score ${"%.2f".format(q.score)}, reliable ${q.reliable}, " +
                    "reasons ${q.reasons}")
                if (name == "speech" || name == "singing" || name == "rap") org.junit.Assert.assertTrue(
                    "$name should be usable (confidence ${q.confidence})", q.reliable,
                )
                if (name == "overlap") org.junit.Assert.assertFalse(
                    "two voices at once should be flagged (confidence ${q.confidence})", q.reliable,
                )
            }
        }
    }

    private fun readWav(f: File): FloatArray {
        val bytes = f.readBytes(); val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val channels = b.getShort(22).toInt(); val bits = b.getShort(34).toInt()
        require(b.getInt(24) == 16_000 && bits == 16) { "16 kHz 16-bit needed" }
        var p = 12
        while (p < bytes.size - 8) {
            val id = String(bytes, p, 4, Charsets.US_ASCII); val size = b.getInt(p + 4)
            if (id == "data") {
                val n = size / 2
                val out = FloatArray(n / channels)
                for (i in 0 until n) out[i / channels] += b.getShort(p + 8 + i * 2) / 32768f / channels
                return out
            }
            p += 8 + size
        }
        error("no data")
    }
}
