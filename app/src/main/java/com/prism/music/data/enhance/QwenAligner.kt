package com.prism.music.data.enhance

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Qwen3-ForcedAligner-0.6B, its single Q4 ONNX graph: Whisper log-mel in, one timestamp bucket (80 ms) per word start
 * and end out. No autoregressive loop: the graph is one forward pass, which is what makes it usable on a phone.
 *
 * The caller gives one audio piece (at most ~4 minutes, the model's own limit is five) and the units to place in it. The
 * graph's audio tokens are the number the encoder produces for this feature length; words are separated by two
 * timestamp slots, and the argmax of those slots is the boundary. Timestamps are made monotonic (the reference
 * implementation's longest-increasing-subsequence repair) before they leave here.
 */
internal class QwenAligner private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val tokenizer: QwenTokenizer,
    private val mel: MelSpectrogram,
) : AutoCloseable {
    /**
     * [units] in order, [audio] at 16 kHz from [offsetMs] in the song. Returns the same units with absolute times and
     * the slot confidence; throws when the graph doesn't answer with the expected shapes.
     */
    fun alignChunk(audio: FloatArray, units: List<AlignUnit>, offsetMs: Long): ChunkResult {
        if (units.isEmpty()) return ChunkResult(emptyList(), 0f)
        // Quiet pieces still have to produce features; one frame is enough for the graph to see the prompt.
        val mono = if (audio.isEmpty()) FloatArray(MelSpectrogram.RATE / 10) else audio
        val computed = mel.compute(mono)
        val full = computed.size / MelSpectrogram.N_MELS
        // The export's own ScatterND miscounts an exact multiple of the encoder's 100-frame block: ONNX Runtime refuses
        // it ("updates tensor should have shape equal to ..."), whatever the audio. One frame less is 10 ms of a piece,
        // well inside the tolerance the graph's buckets have anyway.
        val t = if (full % 100 == 0) full - 1 else full
        if (t <= 0) throw IllegalStateException("The audio is too short to align")
        val features = if (t == full) computed else trimFrames(computed, full, t)
        val audioTokens = outputLength(t)
        val ids = ArrayList<Long>(audioTokens + units.size * 8 + 8)
        ids.add(tokenizer.audioStart.toLong())
        for (i in 0 until audioTokens) ids.add(tokenizer.audioPad.toLong())
        ids.add(tokenizer.audioEnd.toLong())
        val slots = IntArray(units.size)
        val tokensPerUnit = IntArray(units.size)
        for ((i, u) in units.withIndex()) {
            val tok = tokenizer.encode(u.spoken)
            if (tok.isEmpty()) { tokensPerUnit[i] = 0; continue }
            for (id in tok) ids.add(id.toLong())
            slots[i] = ids.size
            ids.add(tokenizer.timestamp.toLong())
            ids.add(tokenizer.timestamp.toLong())
            tokensPerUnit[i] = tok.size
        }
        val length = ids.size
        val inputIds = LongArray(length) { ids[it] }
        val mask = LongArray(length) { 1L }
        val featureMask = IntArray(t) { 1 }
        val slotsSeen = units.indices.count { tokensPerUnit[it] > 0 }
        if (slotsSeen == 0) return ChunkResult(emptyList(), 0f)
        val stamps = IntArray(slotsSeen * 2)
        var confidence = 0.0
        OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), longArrayOf(1, length.toLong())).use { idsT ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(mask), longArrayOf(1, length.toLong())).use { maskT ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(features), longArrayOf(1, MelSpectrogram.N_MELS.toLong(), t.toLong())).use { featT ->
                    OnnxTensor.createTensor(env, IntBuffer.wrap(featureMask), longArrayOf(1, t.toLong())).use { featMaskT ->
                        session.run(
                            mapOf(
                                "input_ids" to idsT,
                                "input_features" to featT,
                                "attention_mask" to maskT,
                                "feature_attention_mask" to featMaskT,
                            ),
                        ).use { result ->
                            val tensor = result[0] as OnnxTensor
                            val shape = tensor.info.shape
                            if (shape.size != 3 || shape[0] != 1L || shape[1] != length.toLong() || shape[2] != VOCAB.toLong()) {
                                throw IllegalStateException("The aligner answered ${shape.contentToString()} for $length slots")
                            }
                            // Read one word's row at a time from the graph's own buffer: the whole matrix is ~70 MB for a
                            // few minutes of song, which a phone's Java heap can't hold next to everything else. Only the
                            // row is copied, and the best bucket and the softmax are found in the same pass.
                            val row = FloatArray(VOCAB)
                            val fb = tensor.floatBuffer
                            var seen = 0
                            for (i in units.indices) {
                                if (tokensPerUnit[i] == 0) continue
                                for (k in 0..1) {
                                    val start = (slots[i] + k) * VOCAB
                                    var best = 0
                                    var bestV = Float.NEGATIVE_INFINITY
                                    for (c in 0 until VOCAB) {
                                        val v = fb.get(start + c)
                                        row[c] = v
                                        if (v > bestV) { bestV = v; best = c }
                                    }
                                    // Softmax confidence of the chosen bucket: how clearly the model preferred it.
                                    var sum = 0.0
                                    for (c in 0 until VOCAB) sum += exp((row[c] - bestV).toDouble())
                                    confidence += 1.0 / sum
                                    stamps[seen++] = best
                                }
                            }
                        }
                    }
                }
            }
        }
        fixTimestamps(stamps)
        val aligned = ArrayList<AlignedUnit>(slotsSeen)
        var w = 0
        for ((i, u) in units.withIndex()) {
            if (tokensPerUnit[i] == 0) continue
            val start = offsetMs + stamps[w++] * tokenizer.timestampSegmentMs.toLong()
            val end = offsetMs + stamps[w++] * tokenizer.timestampSegmentMs.toLong()
            aligned += AlignedUnit(u.clean, u.start, u.end, u.group, max(0, start), max(start, end), u.line)
        }
        return ChunkResult(aligned, (confidence / (slotsSeen * 2)).toFloat())
    }

    /** The first [keep] frames of every mel band: the features are [mel][frame] with stride [full]. */
    private fun trimFrames(features: FloatArray, full: Int, keep: Int): FloatArray {
        val out = FloatArray(MelSpectrogram.N_MELS * keep)
        for (m in 0 until MelSpectrogram.N_MELS) System.arraycopy(features, m * full, out, m * keep, keep)
        return out
    }

    /** Digits after the audio encoder: the processor's own formula (chunks of 100 mel frames, three stride-2 convs). */
    private fun outputLength(input: Int): Int {
        val leave = input % 100
        val feat = (leave - 1) / 2 + 1
        return ((feat - 1) / 2 + 1 - 1) / 2 + 1 + (input / 100) * 13
    }

    override fun close() = runCatching { session.close() }.let { }

    internal data class ChunkResult(val units: List<AlignedUnit>, val confidence: Float)

    companion object {
        /** The aligner's output vocabulary: one score per timestamp bucket for every slot. */
        private const val VOCAB = 5000

        /** Loads the graph with the caller's thread budget: one core is left for playback, and the Q4 graph is re-fused. */
        fun load(env: OrtEnvironment, dir: File, threads: Int = DeviceCores.threads(3)): QwenAligner {
            val model = File(dir, "model_q4.onnx")
            require(model.isFile) { "The aligner model isn't downloaded" }
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads.coerceIn(1, 4))
                setInterOpNumThreads(2)
                // The graph has independent branches (the encoder's attention blocks): letting two run at once uses the
                // fast cores better than one thread walking every node in order.
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.PARALLEL)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            return QwenAligner(env, env.createSession(model.path, opts), QwenTokenizer.load(dir), MelSpectrogram.load(dir))
        }
    }
}

/**
 * The reference aligner's repair of non-monotonic timestamps: keep the longest increasing subsequence as it is, and put
 * every other run back between its normal neighbours (evenly when the run is long). Applied to bucket numbers, before
 * they become milliseconds.
 */
internal fun fixTimestamps(data: IntArray) {
    val n = data.size
    if (n < 2) return
    val dp = IntArray(n) { 1 }
    val parent = IntArray(n) { -1 }
    for (i in 1 until n) for (j in 0 until i) {
        if (data[j] <= data[i] && dp[j] + 1 > dp[i]) { dp[i] = dp[j] + 1; parent[i] = j }
    }
    var maxAt = 0
    for (i in 1 until n) if (dp[i] > dp[maxAt]) maxAt = i
    val normal = BooleanArray(n)
    var idx = maxAt
    while (idx != -1) { normal[idx] = true; idx = parent[idx] }
    val result = data.copyOf()
    var i = 0
    while (i < n) {
        if (normal[i]) { i++; continue }
        var j = i
        while (j < n && !normal[j]) j++
        val count = j - i
        val left = (i - 1 downTo 0).firstOrNull { normal[it] }?.let { result[it] }
        val right = (j until n).firstOrNull { normal[it] }?.let { result[it] }
        if (count <= 2) {
            for (k in i until j) result[k] = when {
                left == null -> right ?: result[k]
                right == null -> left
                (k - (i - 1)) <= (j - k) -> left
                else -> right
            }
        } else if (left != null && right != null) {
            val step = (right - left).toDouble() / (count + 1)
            for (k in i until j) result[k] = (left + step * (k - i + 1)).toInt()
        } else if (left != null) {
            for (k in i until j) result[k] = left
        } else if (right != null) {
            for (k in i until j) result[k] = right
        }
        i = j
    }
    data.indices.forEach { data[it] = result[it] }
}

/**
 * How much to trust an alignment, from things the model can't say about itself: how sure its timestamp picks were, how
 * well word lengths fit the words (phonetic consistency), whether the times move forward, and whether there is voice
 * where a word is supposed to be. Singing with accompaniment, sustained notes, rap's dense lines and overlapping voices
 * all fail in different ways; no one figure catches them, so all four are kept and shown.
 */
object AlignmentQualityCheck {
    fun evaluate(aligned: List<AlignedUnit>, audio16k: FloatArray, averageConfidence: Float): AlignmentQuality {
        if (aligned.isEmpty()) return AlignmentQuality(reliable = false, reasons = listOf("no_words"))
        val reasons = ArrayList<String>()
        // Monotonicity and zero-length words.
        var ordered = 0
        var pairs = 0
        var empty = 0
        for (i in aligned.indices) {
            val a = aligned[i]
            if (a.endMs <= a.startMs) empty++
            if (i > 0) {
                pairs++
                val b = aligned[i - 1]
                if (a.startMs + 60 >= b.startMs && a.endMs + 60 >= b.endMs) ordered++
            }
        }
        val monotonic = if (pairs == 0) 1f else ordered.toFloat() / pairs
        val emptyShare = empty.toFloat() / aligned.size
        if (monotonic < 0.85f) reasons += "timing_out_of_order"
        if (emptyShare > 0.08f) reasons += "empty_words"
        // Phonetic consistency: duration per syllable-ish unit, robustly compared with the song's own median.
        val perUnit = aligned.map { max(1e-3f, (it.endMs - it.startMs).toFloat() / syllables(it.clean)) }
        val median = median(perUnit)
        var consistent = 0
        for (v in perUnit) if (v >= median * 0.2f && v <= median * 5f) consistent++
        val phonetic = consistent.toFloat() / perUnit.size
        if (phonetic < 0.7f) reasons += "word_lengths_irregular"
        // Voice where the words are: 20 ms RMS frames over the audio this alignment saw.
        val frame = 320
        val rms = FloatArray(max(1, audio16k.size / frame))
        var total = 0.0
        for (i in rms.indices) {
            var s = 0.0
            for (k in i * frame until min(audio16k.size, (i + 1) * frame)) s += audio16k[k] * audio16k[k]
            rms[i] = sqrt(s / frame).toFloat()
            total += rms[i]
        }
        val threshold = max(0.004f, (total / rms.size).toFloat() * 0.25f)
        var activeWords = 0
        var coverage = 0.0
        for (u in aligned) {
            // 20 ms frames: a millisecond is 1/20 of one.
            val from = (u.startMs / 20).toInt().coerceIn(0, rms.size - 1)
            val to = (u.endMs / 20).toInt().coerceIn(from + 1, rms.size)
            var on = 0
            for (i in from until to) if (rms[i] > threshold) on++
            val share = on.toFloat() / (to - from)
            coverage += share
            if (share >= 0.25f) activeWords++
        }
        val activity = (coverage / aligned.size).toFloat()
        if (activity < 0.5f) reasons += "words_in_silence"
        val score = (0.40f * averageConfidence + 0.18f * phonetic + 0.22f * monotonic + 0.20f * activity).coerceIn(0f, 1f)
        // A clear model preference, ordered times, word lengths that look like words, and voice under them. Thresholds
        // are deliberately conservative: a doubtful karaoke line is worse than a line that only highlights by line.
        // 0.55 separates the clear cases (speech ~0.92, sustained singing ~0.77, rap ~0.90) from two voices at once
        // (~0.51), where a word's timestamp is genuinely ambiguous; a doubtful line stays on its line timing.
        val reliable = averageConfidence >= 0.55f && phonetic >= 0.6f && monotonic >= 0.8f && activity >= 0.45f && emptyShare <= 0.15f
        if (!reliable && reasons.isEmpty()) reasons += "low_confidence"
        return AlignmentQuality(
            confidence = averageConfidence,
            phonetic = phonetic,
            monotonic = monotonic,
            vocalActivity = activity,
            score = score,
            reliable = reliable,
            reasons = reasons,
        )
    }

    /** Roughly the syllables in a word: Latin vowel groups, one per CJK/kana/Hangul syllable, one per digit. */
    internal fun syllables(text: String): Int {
        var units = 0
        var latinVowel = false
        for (ch in text) {
            val cp = ch.code
            when {
                LyricsUnits.isIdeograph(cp) || LyricsUnits.isKana(cp) || LyricsUnits.isHangul(cp) -> { units++; latinVowel = false }
                Character.isDigit(ch) -> { units++; latinVowel = false }
                Character.isLetter(ch) -> {
                    val vowel = ch.lowercaseChar() in "aeiouyàáâãäåèéêëìíîïòóôõöùúûüāăąǖǘǚǜ"
                    if (vowel && !latinVowel) units++
                    latinVowel = vowel
                }
                else -> latinVowel = false
            }
        }
        return units.coerceAtLeast(1)
    }

    private fun median(values: List<Float>): Float {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
