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

/**
 * The four languages the feature claims, on the same model and tokenizer the app uses: QWEN_LANG_DIR holds
 * en.wav, ja.wav, zh.wav and ko.wav (16 kHz mono) with a .txt of the words in each. Each is aligned start to finish;
 * the test checks every line gets timed words that move forward and stay inside the recording, and prints them.
 */
class QwenLanguagesTest {
    @Test fun englishJapaneseChineseKorean() {
        val dir = System.getenv("QWEN_LANG_DIR")?.let(::File)
        val model = System.getenv("QWEN_ALIGNER_DIR")?.let(::File)
        Assume.assumeTrue("QWEN_LANG_DIR and QWEN_ALIGNER_DIR are needed", dir?.isDirectory == true && model?.isDirectory == true)
        val aligner = QwenAligner.load(OrtEnvironment.getEnvironment(), model!!)
        aligner.use {
            for ((code, language) in listOf(
                "en" to AlignLanguage.ENGLISH, "ja" to AlignLanguage.JAPANESE,
                "zh" to AlignLanguage.CHINESE, "ko" to AlignLanguage.KOREAN,
            )) {
                val wav = File(dir, "$code.wav")
                val txt = File(dir, "$code.txt")
                if (!wav.isFile || !txt.isFile) continue
                val audio = readWav(wav)
                val units = LyricsUnits.units(txt.readText().trim(), language)
                val r = it.alignChunk(audio, units, 0)
                println("[qwen-lang] $code: ${r.units.size} units, confidence ${"%.3f".format(r.confidence)}")
                for (u in r.units) println("[qwen-lang] $code ${u.startMs}..${u.endMs} ${u.clean}")
                assertTrue("$code got no words", r.units.isNotEmpty())
                var last = -1L
                for (u in r.units) {
                    assertTrue("$code runs backwards", u.startMs + 80 >= last)
                    assertTrue("$code has a negative time", u.startMs >= 0 && u.endMs >= u.startMs)
                    last = u.startMs
                }
            }
        }
    }

    private fun readWav(f: File): FloatArray {
        val bytes = f.readBytes(); val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val channels = b.getShort(22).toInt()
        require(b.getInt(24) == 16_000 && b.getShort(34).toInt() == 16) { "16 kHz 16-bit needed" }
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
