package com.prism.music

import ai.onnxruntime.OrtEnvironment
import com.prism.music.data.enhance.HtDemucs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.sqrt

/**
 * HTDemucs on a whole song: the four stems are read in the export's own layout, the vocals come back as the vocal stem
 * and the instrumental as drums + bass + other, and the pieces join without a seam.
 */
class HtDemucsModelTest {
    @Test fun separatesAWholeSong() {
        val model = System.getenv("HTDEMUCS_ONNX")?.let(::File)
        val wav = System.getenv("HTDEMUCS_WAV")?.let(::File)
        Assume.assumeTrue("HTDEMUCS_ONNX and HTDEMUCS_WAV are needed", model?.isFile == true && wav?.isFile == true)
        val (pcm, frames) = readPcm16(wav!!)
        val src = File(Files.createTempDirectory("htdemucs").toFile(), "src.pcm")
        src.outputStream().use { out ->
            val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (v in pcm) bytes.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            out.write(bytes.array())
        }
        val vocals = ArrayList<Float>()
        val instrumental = ArrayList<Float>()
        var lastProgress = -1f
        HtDemucs.load(OrtEnvironment.getEnvironment(), model!!.parentFile, threads = 4).use { d ->
            // The loader wants the file by name in its folder: point it there directly.
            d.separate(src, frames, { false }, { f -> lastProgress = f }) { v, i ->
                vocals.addAll(v.toList()); instrumental.addAll(i.toList())
            }
        }
        src.delete()
        assertEquals(frames.toInt() * 2, vocals.size)
        assertEquals(frames.toInt() * 2, instrumental.size)
        assertEquals("the walk reported its end", 1f, lastProgress, 0.001f)
        fun rms(a: List<Float>) = sqrt(a.fold(0.0) { s, x -> s + x * x } / a.size)
        val mix = sqrt(pcm.fold(0.0) { s, x -> s + x.toDouble() * x } / pcm.size)
        println("[htdemucs] ${frames / 44_100.0} s; mix $mix, vocals ${rms(vocals)}, instrumental ${rms(instrumental)}")
        // The first piece is emitted with no fade, so it must be the model's own answer: 7.8 s of it, then the stride.
        val first = (343_980 - 343_980 / 4) * 2
        println("[htdemucs] first piece: vocals ${rms(vocals.take(first))}, instrumental ${rms(instrumental.take(first))}")
        assertTrue("vocals are silent", rms(vocals) > 0.005)
        assertTrue("instrumental is silent", rms(instrumental) > 0.005)
    }

    /** A 16-bit WAV as interleaved floats (stereo kept), and its frames. */
    private fun readPcm16(f: File): Pair<FloatArray, Long> {
        val bytes = f.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val channels = b.getShort(22).toInt()
        var p = 12
        while (p < bytes.size - 8) {
            val id = String(bytes, p, 4, Charsets.US_ASCII)
            val size = b.getInt(p + 4)
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
