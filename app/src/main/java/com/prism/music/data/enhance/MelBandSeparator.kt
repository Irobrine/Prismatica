package com.prism.music.data.enhance

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.RandomAccessFile
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.min

/** Thrown when the listener stops a separation between its pieces. */
class SeparationCancelled : RuntimeException("Separation cancelled")

/**
 * Mel-Band RoFormer, the vocal separator ytm publishes as ONNX: one 8 s stereo piece at 44.1 kHz in, the complex vocal
 * mask out. Vocals are the masked spectrogram back in the time domain; the instrumental is the piece minus them, as the
 * model defines it (it separates two stems, not four).
 *
 * The graph takes a spectrogram, not audio, so the STFT is here, exactly as the export's own contract describes
 * (`torch.stft(n_fft=2048, hop_length=441, win_length=2048, hann, center=True, reflect, normalized=False)`); bin 0 is
 * zeroed before the mask is applied, as the reference pipeline does. Pieces overlap by half with 0.8 s fades and are
 * divided by their summed weights, so a seam is inaudible.
 *
 * Only one piece's spectra and one window of accumulators are held at a time: the song itself stays in a scratch file.
 */
internal class MelBandSeparator private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : AutoCloseable {
    /**
     * [source] is the whole song at 44.1 kHz stereo, little-endian 16-bit, [frames] long. [emit] gets contiguous pieces of
     * vocals and instrumental (interleaved stereo floats at 44.1 kHz); [onProgress] is called after each piece with 0..1.
     * Throws [SeparationCancelled] when [cancelled] turns true.
     */
    fun separate(
        source: File,
        frames: Long,
        cancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
        emit: (vocals: FloatArray, instrumental: FloatArray) -> Unit,
    ) {
        val total = frames.coerceAtLeast(1)
        val mix = FloatArray(CHUNK * 2)
        val accV = FloatArray(CHUNK * 2)
        val accI = FloatArray(CHUNK * 2)
        val accW = FloatArray(CHUNK)
        /** The analysed window's squares, overlap-added: the inverse STFT divides by this, as torch's does. */
        val accW2 = FloatArray(CHUNK)
        val spec = FloatArray(4 * BINS * FRAMES)
        val fftL = ComplexFft(N_FFT)
        val fftR = ComplexFft(N_FFT)
        val re = DoubleArray(N_FFT)
        val im = DoubleArray(N_FFT)
        val ir = DoubleArray(N_FFT)
        val ii = DoubleArray(N_FFT)
        val padded = FloatArray((CHUNK + N_FFT) * 2)
        val window = HANN

        var emitted = 0L
        RandomAccessFile(source, "r").use { raf ->
            var start = 0L
            var piece = 0L
            while (start < total) {
                if (cancelled()) throw SeparationCancelled()
                val len = min(CHUNK.toLong(), total - start).toInt()
                readMix(raf, start, len, mix)
                // The graph always sees 8 s; a last, short piece is padded with silence and only its own part is kept.
                if (len < CHUNK) java.util.Arrays.fill(mix, len * 2, mix.size, 0f)
                reflectPad(mix, padded)
                for (ch in 0..1) {
                    val fft = if (ch == 0) fftL else fftR
                    val realAt = ch * 2 * BINS * FRAMES
                    val imagAt = (ch * 2 + 1) * BINS * FRAMES
                    for (f in 0 until FRAMES) {
                        val at = f * HOP
                        for (i in 0 until N_FFT) {
                            re[i] = (padded[(at + i) * 2 + ch].toDouble()) * window[i]
                            im[i] = 0.0
                        }
                        fft.forward(re, im)
                        for (k in 0 until BINS) {
                            spec[realAt + k * FRAMES + f] = re[k].toFloat()
                            spec[imagAt + k * FRAMES + f] = im[k].toFloat()
                        }
                    }
                }
                // The model's mask is applied while its result is open: the spectra are still here, and only the rows
                // being used are read (the whole mask is ~13 MB of floats, which the heap doesn't need to hold).
                withMask(spec) { fb ->
                    for (f in 0 until FRAMES) {
                        val at = f * HOP
                        for (ch in 0..1) {
                            val realAt = ch * 2 * BINS * FRAMES
                            val imagAt = (ch * 2 + 1) * BINS * FRAMES
                            for (k in 0 until BINS) {
                                val ar = spec[realAt + k * FRAMES + f].toDouble()
                                val ai = spec[imagAt + k * FRAMES + f].toDouble()
                                val mr = fb.get(realAt + k * FRAMES + f).toDouble()
                                val mi = fb.get(imagAt + k * FRAMES + f).toDouble()
                                // Bin 0 is DC, which carries no voice: zeroed before the multiply, as the export's own
                                // reference pipeline does.
                                ir[k] = if (k == 0) 0.0 else ar * mr - ai * mi
                                ii[k] = if (k == 0) 0.0 else ar * mi + ai * mr
                            }
                            // A real signal's spectrum is conjugate-symmetric: the upper half follows from the lower one.
                            for (k in 1 until BINS - 1) {
                                ir[N_FFT - k] = ir[k]
                                ii[N_FFT - k] = -ii[k]
                            }
                            ii[BINS - 1] = 0.0
                            (if (ch == 0) fftL else fftR).inverse(ir, ii)
                            // The STFT was centred, so the piece starts 1024 samples into the overlap-add: the first and
                            // last half-frame are the padding, and only the piece's own 8 s are kept.
                            for (i in 0 until N_FFT) {
                                val t = at + i - N_FFT / 2
                                if (t < 0 || t >= CHUNK) continue
                                val v = ir[i] * window[i]
                                accV[t * 2 + ch] += v.toFloat()
                                // The accompaniment is weighted like the window's squares (so dividing by them gives the
                                // piece minus the vocals), not once per overlapping frame.
                                accI[t * 2 + ch] += (mix[t * 2 + ch] * (window[i] * window[i]) - v).toFloat()
                            }
                        }
                    }
                }
                // The fade weight is per output sample (not per frame: overlapping frames must not multiply it); the
                // window's squares are the inverse STFT's own per-frame sum.
                for (t in 0 until CHUNK) accW[t] += weight(t, start == 0L, start + CHUNK >= total)
                for (f in 0 until FRAMES) {
                    val at = f * HOP
                    for (i in 0 until N_FFT) {
                        val t = at + i - N_FFT / 2
                        if (t < 0 || t >= CHUNK) continue
                        accW2[t] += window[i] * window[i]
                    }
                }

                // Everything before the next piece's start is final: divide by the summed weights and emit it.
                val done = min(STEP.toLong(), total - start).toInt()
                val outV = FloatArray(done * 2)
                val outI = FloatArray(done * 2)
                for (i in 0 until done) {
                    val w = accW[i]
                    val w2 = accW2[i]
                    // The inverse STFT's own division (the summed window squares), then the piece's fade weight.
                    val inv = if (w > 1e-6f && w2 > 1e-8f) 1f / (w * w2) else 0f
                    outV[i * 2] = accV[i * 2] * inv
                    outV[i * 2 + 1] = accV[i * 2 + 1] * inv
                    outI[i * 2] = accI[i * 2] * inv
                    outI[i * 2 + 1] = accI[i * 2 + 1] * inv
                }
                emit(outV, outI)
                emitted += done
                piece++
                onProgress((emitted.toDouble() / total).toFloat().coerceIn(0f, 1f))
                // Slide the accumulators left by the step just emitted.
                if (done < CHUNK) {
                    System.arraycopy(accV, done * 2, accV, 0, (CHUNK - done) * 2)
                    System.arraycopy(accI, done * 2, accI, 0, (CHUNK - done) * 2)
                    System.arraycopy(accW, done, accW, 0, CHUNK - done)
                    System.arraycopy(accW2, done, accW2, 0, CHUNK - done)
                    java.util.Arrays.fill(accV, (CHUNK - done) * 2, accV.size, 0f)
                    java.util.Arrays.fill(accI, (CHUNK - done) * 2, accI.size, 0f)
                    java.util.Arrays.fill(accW, CHUNK - done, CHUNK, 0f)
                    java.util.Arrays.fill(accW2, CHUNK - done, CHUNK, 0f)
                }
                start += STEP
            }
        }
    }

    /** The linear ramp a piece's edges are weighted by: the first piece has no fade in, the last none out. */
    private fun weight(i: Int, first: Boolean, last: Boolean): Float {
        var w = 1f
        if (!first && i < FADE) w = min(w, i.toFloat() / FADE)
        if (!last && i >= CHUNK - FADE) w = min(w, (CHUNK - 1 - i).toFloat() / FADE)
        return w
    }

    /** Runs the graph on [spec] and hands its mask to [use] while it is open, so nothing outlives the call. */
    private inline fun withMask(spec: FloatArray, use: (FloatBuffer) -> Unit) {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(spec), longArrayOf(1, 4, BINS.toLong(), FRAMES.toLong())).use { input ->
            session.run(mapOf("spec" to input)).use { result ->
                use((result[0] as OnnxTensor).floatBuffer)
            }
        }
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        const val RATE = 44_100
        const val N_FFT = 2048
        const val HOP = 441
        const val BINS = 1025
        /** 8 s at 44.1 kHz: the graph's own piece. */
        const val CHUNK = 352_800
        /**
         * A whole piece between starts. The export's own MUSDB scores put 8 s steps within 0.2 dB of half-overlapping
         * ones (11.73 against 11.91 vocals SDR) for half the runs, and without overlap there are no seams to weigh
         * either: a piece's output is final as it comes.
         */
        const val STEP = CHUNK
        /** 0.8 s of linear fade at a seam. */
        const val FADE = 35_280
        const val FRAMES = 801
        const val MODEL = "ytm-vocal-separator.onnx"

        private val HANN = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2.0 * Math.PI * it / N_FFT)).toFloat() }

        fun load(env: OrtEnvironment, dir: File, threads: Int = DeviceCores.threads(4)): MelBandSeparator =
            loadModel(env, File(dir, MODEL), threads)

        /** The graph itself, for tests and callers that already hold the file. */
        internal fun loadModel(env: OrtEnvironment, model: File, threads: Int): MelBandSeparator {
            require(model.isFile) { "The vocal separation model isn't downloaded" }
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads.coerceIn(1, 4))
                setInterOpNumThreads(2)
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.PARALLEL)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            return MelBandSeparator(env, env.createSession(model.path, opts))
        }

        /** Reads [len] interleaved frames at [start] into [out], at the start of it. */
        private fun readMix(raf: RandomAccessFile, start: Long, len: Int, out: FloatArray) {
            val bytes = ByteArray(len * 4)
            raf.seek(start * 4)
            var got = 0
            while (got < bytes.size) {
                val n = raf.read(bytes, got, bytes.size - got)
                if (n < 0) break
                got += n
            }
            var i = 0
            var at = 0
            while (i + 1 < got) {
                out[at++] = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort() / 32768f
                i += 2
            }
        }

        /** [sig]'s [CHUNK] frames with the 1024 frames of reflection the STFT's centre needs on each side. */
        private fun reflectPad(sig: FloatArray, out: FloatArray) {
            for (i in 0 until N_FFT / 2) {
                out[i * 2] = sig[(N_FFT / 2 - i) * 2]
                out[i * 2 + 1] = sig[(N_FFT / 2 - i) * 2 + 1]
            }
            System.arraycopy(sig, 0, out, N_FFT, CHUNK * 2)
            for (j in 0 until N_FFT / 2) {
                val at = (CHUNK - 2 - j) * 2
                out[(N_FFT / 2 + CHUNK + j) * 2] = sig[at]
                out[(N_FFT / 2 + CHUNK + j) * 2 + 1] = sig[at + 1]
            }
        }
    }
}
