package com.prism.music

import ai.onnxruntime.OrtEnvironment
import com.prism.music.data.enhance.AlignLanguage
import com.prism.music.data.enhance.LyricsUnits
import com.prism.music.data.enhance.QwenAligner
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * The aligner's mel lengths around the encoder's 100-frame blocks. The export's ScatterND refuses an exact multiple (it
 * answers "updates tensor should have shape equal to ..."), which a piece of exactly the wrong length would hit; every
 * length must answer.
 */
class QwenAlignShapeTest {
    @Test fun everyMelLengthAnswers() {
        val dir = System.getenv("QWEN_ALIGNER_DIR")?.let(::File)
        Assume.assumeTrue("QWEN_ALIGNER_DIR is needed", dir?.isDirectory == true)
        val units = LyricsUnits.units("Hello world", AlignLanguage.ENGLISH)
        QwenAligner.load(OrtEnvironment.getEnvironment(), dir!!, threads = 4).use { aligner ->
            for (t in listOf(99, 100, 101, 199, 200, 201, 599, 600, 601)) {
                val audio = FloatArray(t * 160) { (kotlin.math.sin(it * 0.05) * 0.1).toFloat() }
                val result = runCatching { aligner.alignChunk(audio, units, 0L) }
                println("[qwen-shape] t=$t -> ${result.exceptionOrNull()?.message ?: "ok"}")
                assertTrue("t=$t failed: ${result.exceptionOrNull()}", result.isSuccess)
            }
        }
    }
}
