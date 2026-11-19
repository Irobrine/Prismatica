package com.prism.music

import ai.onnxruntime.OrtEnvironment
import com.prism.music.data.enhance.MelBandSeparator
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.sqrt

/**
 * The separator's own output against the export's published reference: an 8 s stereo piece in, its vocal estimate out.
 * The STFT, the mask, the inverse and the piece handling are all Prism's, so this is the check that they follow the
 * graph's contract (and not just that the graph runs).
 */
class MelBandSeparatorTest {
    private fun f32(file: File): FloatArray =
        ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { fb ->
            FloatArray(fb.remaining()).also { fb.get(it) }
        }

    @Test fun aPieceComesOutAsTheReferenceHasIt() {
        val model = System.getenv("YTM_SEP_ONNX")?.let(::File)
        val input = System.getenv("YTM_REF_INPUT")?.let(::File)
        val expected = System.getenv("YTM_REF_VOCALS")?.let(::File)
        Assume.assumeTrue(
            "YTM_SEP_ONNX, YTM_REF_INPUT and YTM_REF_VOCALS are needed",
            model?.isFile == true && input?.isFile == true && expected?.isFile == true,
        )
        val x = f32(input!!)
        val frames = x.size / 2
        val ref = f32(expected!!)
        // The separator reads the song's own 16-bit PCM: the reference input goes through the same conversion the app does.
        val pcm = File(Files.createTempDirectory("ytm-sep").toFile(), "in.pcm")
        pcm.outputStream().use { out ->
            val bytes = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in x) bytes.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            out.write(bytes.array())
        }

        val vocals = FloatArray(frames * 2)
        val instrumental = FloatArray(frames * 2)
        var written = 0
        MelBandSeparator.loadModel(OrtEnvironment.getEnvironment(), model!!, threads = 4).use { sep ->
            sep.separate(pcm, frames.toLong(), { false }, { }) { v, i ->
                System.arraycopy(v, 0, vocals, written, v.size)
                System.arraycopy(i, 0, instrumental, written, i.size)
                written += v.size
            }
        }
        pcm.delete()

        fun rms(a: FloatArray) = sqrt(a.fold(0.0) { s, v -> s + v.toDouble() * v }.toFloat() / a.size)
        val err = FloatArray(vocals.size) { vocals[it] - ref[it] }
        val relative = rms(err) / rms(ref)
        println("[ytm-sep] vocals rms ${rms(vocals)}, reference ${rms(ref)}, relative error $relative")
        assertTrue("nothing was written", written == vocals.size)
        assertTrue("the vocals don't follow the reference: $relative", relative < 0.01f)
        // The instrumental is the piece minus the vocals, as the two-stem model defines it.
        var worst = 0f
        for (i in x.indices) worst = maxOf(worst, kotlin.math.abs(x[i] - vocals[i] - instrumental[i]))
        println("[ytm-sep] instrumental vs mix minus vocals: worst sample $worst")
        assertTrue("the instrumental isn't the mix minus the vocals: $worst", worst < 0.01f)
    }
}
