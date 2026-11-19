package com.prism.music.data.enhance

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.RandomAccessFile
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Thrown when the listener stops a separation between its pieces. */
class SeparationCancelled : RuntimeException("Separation cancelled")

/**
 * HTDemucs (the single-model FP16-weights ONNX export): one session for all four stems, the model the reference inference
 * uses. A song is fed in 7.8 s pieces with a quarter-piece overlap; each piece's four stems are weighted with a ramp at
 * their edges and overlap-added, divided by the summed weights. The vocal stem is kept as it is and the instrumental is
 * the sum of the other three (drums, bass, other), never "the song minus the vocals": the non-vocal stems are the model's
 * own reconstruction.
 *
 * The graph takes the waveform itself — `mix` [1, 2, 343980], one piece at 44.1 kHz — and answers `stems`
 * [4, 2, 343980] in its own order (drums, bass, other, vocals). The emitted pieces are 44.1 kHz stereo, contiguous in
 * song time, so the caller can resample them once to the song's own rate. Only a piece's output and one window of
 * accumulators are held at a time; the song itself stays in a scratch file.
 */
class HtDemucs private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : AutoCloseable {
    /**
     * [source] is the whole song at 44.1 kHz stereo, little-endian 16-bit, [frames] long. [emit] gets contiguous pieces
     * of vocals and instrumental (interleaved stereo floats at 44.1 kHz); [onProgress] is called after each piece with
     * 0..1. Throws [SeparationCancelled] when [cancelled] turns true.
     */
    fun separate(
        source: File,
        frames: Long,
        cancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
        emit: (vocals: FloatArray, instrumental: FloatArray) -> Unit,
    ) {
        val total = frames.coerceAtLeast(1)
        val overlap = N_SAMPLES / 4
        val stride = N_SAMPLES - overlap
        val window = FloatArray(N_SAMPLES) { 1f }
        for (i in 0 until overlap) {
            val w = i.toFloat() / overlap
            window[i] = w
            window[N_SAMPLES - 1 - i] = w
        }
        val input = FloatArray(N_SAMPLES * 2)
        val accV = FloatArray(N_SAMPLES * 2)
        val accI = FloatArray(N_SAMPLES * 2)
        val weight = FloatArray(N_SAMPLES)
        var emitted = 0L
        RandomAccessFile(source, "r").use { raf ->
            var start = 0L
            while (start < total) {
                if (cancelled()) throw SeparationCancelled()
                val len = min(N_SAMPLES.toLong(), total - start).toInt()
                readSource(raf, start, len, input)
                withStems(input) { fb ->
                    // A piece's edge is faded unless it's the song's own edge: two pieces overlap by exactly one ramp, so
                    // their weights add up to one in the crossfade and dividing by the sum restores the level.
                    val first = start == 0L
                    val last = start + N_SAMPLES >= total
                    for (j in 0 until len) {
                        val w = when {
                            !first && j < overlap -> j.toFloat() / overlap
                            !last && j >= N_SAMPLES - overlap -> (N_SAMPLES - 1 - j).toFloat() / overlap
                            else -> 1f
                        }
                        weight[j] += w
                        val base = j * 2
                        for (ch in 0 until 2) {
                            val at = ch * N_SAMPLES + j
                            val v = fb.get(3 * 2 * N_SAMPLES + at)
                            // Drums, bass and other: the instrumental the model itself builds.
                            val ins = fb.get(at) + fb.get(2 * N_SAMPLES + at) + fb.get(4 * N_SAMPLES + at)
                            accV[base + ch] += v * w
                            accI[base + ch] += ins * w
                        }
                    }
                }
                // Everything before the next piece's start is final: divide by the summed weights and emit it.
                val done = min(stride.toLong(), total - start).toInt()
                val outV = FloatArray(done * 2)
                val outI = FloatArray(done * 2)
                for (j in 0 until done) {
                    val w = weight[j]
                    val inv = if (w > 1e-6f) 1f / w else 0f
                    outV[j * 2] = accV[j * 2] * inv
                    outV[j * 2 + 1] = accV[j * 2 + 1] * inv
                    outI[j * 2] = accI[j * 2] * inv
                    outI[j * 2 + 1] = accI[j * 2 + 1] * inv
                }
                emit(outV, outI)
                emitted += done
                onProgress((emitted.toDouble() / total).toFloat().coerceIn(0f, 1f))
                if (done < N_SAMPLES) {
                    System.arraycopy(accV, done * 2, accV, 0, (N_SAMPLES - done) * 2)
                    System.arraycopy(accI, done * 2, accI, 0, (N_SAMPLES - done) * 2)
                    System.arraycopy(weight, done, weight, 0, N_SAMPLES - done)
                    java.util.Arrays.fill(accV, (N_SAMPLES - done) * 2, accV.size, 0f)
                    java.util.Arrays.fill(accI, (N_SAMPLES - done) * 2, accI.size, 0f)
                    java.util.Arrays.fill(weight, N_SAMPLES - done, N_SAMPLES, 0f)
                }
                start += stride
            }
        }
    }

    /** Runs the graph on one piece and hands its stems to [use] while they are open (nothing is copied). */
    private inline fun withStems(input: FloatArray, use: (FloatBuffer) -> Unit) {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 2, N_SAMPLES.toLong())).use { tensor ->
            session.run(mapOf("mix" to tensor)).use { result ->
                use((result[0] as OnnxTensor).floatBuffer)
            }
        }
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        const val RATE = 44_100
        const val SEGMENT_SECONDS = 7.8
        const val N_SAMPLES = 343_980

        /** What each file is called on the phone; the fp32 export is a fallback for a build that can't load halves. */
        const val MODEL = "htdemucs_fp16weights.onnx"

        fun load(env: OrtEnvironment, dir: File, threads: Int = DeviceCores.threads(4)): HtDemucs {
            val model = File(dir, MODEL).takeIf { it.isFile } ?: File(dir, "htdemucs.onnx")
            require(model.isFile) { "The vocal separation model isn't downloaded" }
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads.coerceIn(1, 4))
                setInterOpNumThreads(2)
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.PARALLEL)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            return HtDemucs(env, env.createSession(model.path, opts))
        }

        /**
         * Reads [len] frames at [start] into [out] as the graph takes them: each channel's samples in their own plane
         * ([out] is [2][N_SAMPLES], not interleaved), zeroing whatever the file doesn't reach.
         */
        private fun readSource(raf: RandomAccessFile, start: Long, len: Int, out: FloatArray) {
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
            while (i + 3 < got) {
                out[at] = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort() / 32768f
                out[N_SAMPLES + at] = ((bytes[i + 3].toInt() shl 8) or (bytes[i + 2].toInt() and 0xFF)).toShort() / 32768f
                at++
                i += 4
            }
            if (at < len) {
                java.util.Arrays.fill(out, at, len, 0f)
                java.util.Arrays.fill(out, N_SAMPLES + at, N_SAMPLES + len, 0f)
            }
        }
    }
}
