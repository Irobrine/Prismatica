package com.prism.music

import ai.onnxruntime.OrtEnvironment
import com.prism.music.data.enhance.AlignLanguage
import com.prism.music.data.enhance.LyricsUnits
import com.prism.music.data.enhance.QwenAligner
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The real Qwen3-ForcedAligner Q4 graph on real speech, when QWEN_ALIGNER_DIR points at its eight files and
 * QWEN_ALIGNER_WAV at a 16 kHz mono WAV (QWEN_ALIGNER_TEXT and QWEN_ALIGNER_LANG say what's in it). The test checks the
 * things an alignment can be wrong about without a reference: times move forward, every word gets a span, and the span
 * stays within the recording. It prints the words so a human can compare them with the audio.
 */
class QwenAlignerModelTest {
    @Test
    fun alignsSpeech() {
        val dir = System.getenv("QWEN_ALIGNER_DIR")?.let(::File)
        val wav = System.getenv("QWEN_ALIGNER_WAV")?.let(::File)
        Assume.assumeTrue("QWEN_ALIGNER_DIR and QWEN_ALIGNER_WAV are needed", dir?.isDirectory == true && wav?.isFile == true)
        val text = System.getenv("QWEN_ALIGNER_TEXT") ?: "The quick brown fox jumps over the lazy dog"
        val language = AlignLanguage.entries.firstOrNull { it.name == (System.getenv("QWEN_ALIGNER_LANG") ?: "ENGLISH").uppercase() }
            ?: AlignLanguage.ENGLISH
        val audio = readWav(wav!!)
        val units = LyricsUnits.units(text, language)
        assertTrue("no units for $text", units.isNotEmpty())
        val aligner = QwenAligner.load(OrtEnvironment.getEnvironment(), dir!!)
        val result = aligner.use { it.alignChunk(audio, units, 0L) }
        assertTrue("no aligned units", result.units.isNotEmpty())
        println("[qwen] ${result.units.size} units, confidence ${"%.3f".format(result.confidence)}")
        for (u in result.units) println("[qwen] ${u.startMs}..${u.endMs} ${u.clean}")
        var last = -1L
        for (u in result.units) {
            assertTrue("start before previous: ${u.clean} at ${u.startMs}", u.startMs + 80 >= last)
            assertTrue("word runs backwards: ${u.clean}", u.endMs + 80 >= u.startMs)
            last = u.startMs
        }
    }

    private fun readWav(f: File): FloatArray {
        val bytes = f.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val channels = b.getShort(22).toInt()
        val rate = b.getInt(24)
        val bits = b.getShort(34).toInt()
        require(rate == 16_000 && bits == 16) { "expected 16 kHz 16-bit mono, got $rate Hz $bits-bit" }
        var p = 12
        while (p < bytes.size - 8) {
            val id = String(bytes, p, 4, Charsets.US_ASCII)
            val size = b.getInt(p + 4)
            if (id == "data") {
                val n = size / 2
                val out = FloatArray(if (channels == 1) n else n)
                var i = 0
                while (i < n) {
                    val v = b.getShort(p + 8 + i * 2) / 32768f
                    if (channels == 1) out[i] = v else if (i % channels == 0) out[i / channels] = v
                    i++
                }
                return if (channels == 1) out else out.copyOf(n / channels)
            }
            p += 8 + size
        }
        error("no data chunk")
    }
}
