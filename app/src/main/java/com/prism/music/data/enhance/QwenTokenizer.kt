package com.prism.music.data.enhance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Qwen2's byte-level BPE, enough of it to turn each aligned word into the model's input ids. Words are encoded one at a
 * time (the aligner's prompt puts the timestamp tokens between them), with no leading space, which is how the reference
 * tokenizer encodes them too. The vocabulary and merges are the model's own files.
 */
class QwenTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val ranks: Map<String, Int>,
    private val byteToChar: CharArray,
    val audioStart: Int,
    val audioEnd: Int,
    val audioPad: Int,
    val timestamp: Int,
    val timestampSegmentMs: Int,
) {
    private val unknown = vocab["<unk>"] ?: 0

    /** The ids of [text], one word with no spaces around it. Empty for text with nothing encodable. */
    fun encode(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) return IntArray(0)
        var symbols = ArrayList<String>(bytes.size)
        for (b in bytes) symbols.add(byteToChar[b.toInt() and 0xFF].toString())
        while (symbols.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestAt = -1
            for (i in 0 until symbols.size - 1) {
                val rank = ranks[symbols[i] + symbols[i + 1]] ?: continue
                if (rank < bestRank) { bestRank = rank; bestAt = i }
            }
            if (bestAt < 0) break
            val pair = symbols[bestAt] + symbols[bestAt + 1]
            val next = ArrayList<String>(symbols.size)
            var i = 0
            while (i < symbols.size) {
                if (i < symbols.size - 1 && symbols[i] == symbols[bestAt] && symbols[i + 1] == symbols[bestAt + 1]) {
                    next.add(pair); i += 2
                } else { next.add(symbols[i]); i++ }
            }
            symbols = next
        }
        return IntArray(symbols.size) { vocab[symbols[it]] ?: unknown }
    }

    companion object {
        fun load(dir: File): QwenTokenizer {
            val json = Json { ignoreUnknownKeys = true }
            val vocabRoot = json.parseToJsonElement(File(dir, "vocab.json").readText()) as JsonObject
            val vocab = HashMap<String, Int>(vocabRoot.size)
            for ((k, v) in vocabRoot) vocab[k] = v.jsonPrimitive.int
            val ranks = HashMap<String, Int>(1_500_000)
            var rank = 0
            File(dir, "merges.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank() || line.startsWith("#")) continue
                    val at = line.indexOf(' ')
                    if (at <= 0 || at >= line.length - 1) continue
                    ranks[line.substring(0, at) + line.substring(at + 1)] = rank++
                }
            }
            // Special ids are stable across the Qwen3-ASR family; the files are read anyway so a future model can move them.
            var start = 151669; var end = 151670; var pad = 151676; var ts = 151705; var segment = 80
            runCatching {
                val added = json.parseToJsonElement(File(dir, "added_tokens.json").readText()) as JsonObject
                fun id(name: String, fallback: Int) = (added[name]?.jsonPrimitive?.int) ?: fallback
                start = id("<|audio_start|>", start); end = id("<|audio_end|>", end)
                pad = id("<|audio_pad|>", pad); ts = id("<timestamp>", ts)
            }
            runCatching {
                val cfg = json.parseToJsonElement(File(dir, "config.json").readText()) as JsonObject
                (cfg["timestamp_token_id"]?.jsonPrimitive?.int)?.let { ts = it }
                (cfg["timestamp_segment_time"]?.jsonPrimitive?.int)?.let { segment = it }
            }
            return QwenTokenizer(vocab, ranks, gpt2Bytes(), start, end, pad, ts, segment)
        }

        /** GPT-2's bytes-to-unicode table: every byte as one printable character the vocabulary was built over. */
        private fun gpt2Bytes(): CharArray {
            val bs = ArrayList<Int>(256)
            for (i in 33..126) bs.add(i)
            for (i in 161..172) bs.add(i)
            for (i in 174..255) bs.add(i)
            val cs = ArrayList<Int>(256).apply { addAll(bs) }
            var n = 0
            for (b in 0..255) if (b !in bs) { bs.add(b); cs.add(256 + n); n++ }
            val out = CharArray(256)
            for (i in bs.indices) out[bs[i]] = cs[i].toChar()
            return out
        }
    }
}
