package com.prism.music.analysis

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/** A tagger's answer for one song: each class's pooled probability, and the song's embedding (mean over frames). */
class TaggerOutput(val scores: FloatArray, val embedding: FloatArray, val frames: Int)

/**
 * A model that hears a song's [TaggerTap] windows and says what's in them. Replaceable: [id] and [version] go into
 * every row it writes, so a different model (EfficientAT, CLAP) analyses songs again instead of mixing its tags with
 * another's. [TagVocabulary] reads its classes.
 */
interface AudioTagger : AutoCloseable {
    val id: String
    val version: Int
    fun run(windows: List<FloatArray>, step: () -> Unit = {}): TaggerOutput?
}

/**
 * Google's YAMNet (Apache-2.0): AudioSet's 521 classes and a 1024-value embedding, every 0.96 s (0.48 s apart) of mono
 * 16 kHz audio, run with ONNX Runtime ([com.prism.music.data.ModelStore.Model.YAMNET]). ~69 M multiply-adds a frame: a
 * song's 8 windows (≈ 80 frames) are well under a second on one phone core.
 *
 * Frame scores are pooled per class as the mean of the top quarter of frames: a choir that sings in a third of the song
 * still counts, a cough in one frame doesn't.
 */
class YamnetTagger(private val env: OrtEnvironment, private val session: OrtSession) : AudioTagger {
    override val id = ID
    override val version = VERSION
    private val input = session.inputNames.first()

    override fun run(windows: List<FloatArray>, step: () -> Unit): TaggerOutput? {
        val frames = ArrayList<FloatArray>()
        val embedding = DoubleArray(EMBEDDING)
        var embedded = 0
        for (w in windows) {
            step()
            if (w.size < MIN_SAMPLES) continue
            OnnxTensor.createTensor(env, FloatBuffer.wrap(w), longArrayOf(w.size.toLong())).use { t ->
                session.run(mapOf(input to t)).use { r ->
                    @Suppress("UNCHECKED_CAST") val scores = r[0].value as Array<FloatArray>
                    @Suppress("UNCHECKED_CAST") val emb = r[1].value as Array<FloatArray>
                    frames += scores
                    for (e in emb) { for (i in 0 until minOf(EMBEDDING, e.size)) embedding[i] += e[i].toDouble(); embedded++ }
                }
            }
        }
        if (frames.isEmpty()) return null
        return TaggerOutput(pool(frames), FloatArray(EMBEDDING) { (embedding[it] / embedded.coerceAtLeast(1)).toFloat() }, frames.size)
    }

    override fun close() = session.close()

    companion object {
        const val ID = "yamnet"
        /** Bumped when the model file or its pooling changes: songs are tagged again. */
        const val VERSION = 1
        const val CLASSES = 521
        const val EMBEDDING = 1024
        /** One YAMNet frame (0.96 s at 16 kHz). */
        private const val MIN_SAMPLES = 15_600

        /** Each class's mean over the top quarter of [frames] (at least one frame). */
        fun pool(frames: List<FloatArray>): FloatArray {
            val classes = frames.first().size
            val top = (frames.size / 4).coerceAtLeast(1)
            val column = FloatArray(frames.size)
            return FloatArray(classes) { c ->
                for (f in frames.indices) column[f] = frames[f][c]
                column.sortDescending()
                var s = 0f
                for (i in 0 until top) s += column[i]
                s / top
            }
        }

        /** How the networks run: one thread (a background job beside playback), no spinning, memory handed back. */
        fun options(): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            addConfigEntry("session.intra_op.allow_spinning", "0")
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
        }
    }
}
