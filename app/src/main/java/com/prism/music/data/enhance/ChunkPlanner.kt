package com.prism.music.data.enhance

import com.prism.music.data.lyrics.LyricLine
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Splits a song's lyrics into pieces the aligner can take (its own limit is five minutes; Prism keeps a piece to about
 * two, so the job's progress moves and Cancel is heard while it works, with a little lead and tail so a line's first or
 * last word isn't at the very edge). Line-timed lyrics are
 * split at their own line boundaries; plain lyrics are split in proportion to the recording and each piece's audio is
 * taken from that part of it.
 *
 * The offsets it returns are the part of the song each piece is aligned against; the aligner adds that offset to every
 * timestamp, so the words come out in song time even when a song is in several pieces.
 */
internal object ChunkPlanner {
    class Plan(val units: List<AlignUnit>, val fromMs: Long, val toMs: Long)

    /**
     * Two minutes of lyrics a piece: well inside the aligner's five-minute limit, and short enough that its progress
     * moves and a Cancel lands while a piece is being worked out, not after a four-minute forward pass. A piece is
     * still split at line boundaries.
     */
    private const val MAX_CHUNK_MS = 117_000L

    /**
     * The piece sizes an untrusted alignment is tried again at: enough to roughly double, quadruple and octuple the
     * default plan's piece count, whatever the song's length. A ladder of fixed sizes isn't fair — on a short song two
     * rungs give the same plan (the same answer twice), and on a long one the same rungs refine it differently.
     */
    fun retrySizes(durationMs: Long, pieces: Int): List<Long> {
        val span = durationMs.coerceAtLeast(1)
        return listOf(2, 4, 8)
            .map { factor -> (span / (pieces.coerceAtLeast(1) * factor)).coerceIn(MIN_RETRY_CHUNK_MS, MAX_CHUNK_MS) }
            .filter { it < MAX_CHUNK_MS }
            .distinct()
    }

    /** No piece shorter than this in a retry: a lyric line or two is as small as alignment stays meaningful. */
    private const val MIN_RETRY_CHUNK_MS = 8_000L

    private const val LEAD_MS = 1_200L
    private const val TAIL_MS = 1_500L
    private const val MAX_UNITS = 420

    fun plans(
        lines: List<LyricLine>,
        durationMs: Long,
        language: AlignLanguage,
        maxChunkMs: Long = MAX_CHUNK_MS,
    ): List<Plan> {
        val nonGap = lines.indices.filter { !lines[it].isGap && lines[it].text.isNotBlank() }
        if (nonGap.isEmpty()) return emptyList()
        val unitsByLine = nonGap.associateWith { i -> LyricsUnits.units(lines[i].text, language).onEach { it.line = i } }
        val timed = nonGap.all { lines[it].timeMs >= 0 }
        val plans = ArrayList<Plan>()
        if (timed) {
            var group = ArrayList<Int>()
            var from = 0L; var to = 0L
            fun flush() {
                if (group.isEmpty()) return
                val units = group.flatMap { unitsByLine[it].orEmpty() }
                if (units.isNotEmpty()) plans += Plan(units, max(0, from - LEAD_MS), min(durationMs, to + TAIL_MS))
                group = ArrayList()
            }
            for (i in nonGap) {
                val line = lines[i]
                val start = line.timeMs.coerceAtLeast(0)
                val end = if (line.endMs > line.timeMs) line.endMs else start + 8_000
                if (group.isNotEmpty() && (end - from > maxChunkMs || unitsByLine[i].orEmpty().size + group.sumOf { unitsByLine[it].orEmpty().size } > MAX_UNITS)) flush()
                if (group.isEmpty()) { from = start; to = end }
                group += i
                to = max(to, end)
            }
            flush()
        } else {
            val totalUnits = nonGap.sumOf { unitsByLine[it].orEmpty().size }.coerceAtLeast(1)
            val chunks = max(1, ceil(durationMs.toDouble() / maxChunkMs).toInt())
            val target = ceil(totalUnits.toDouble() / chunks).toInt().coerceIn(12, MAX_UNITS)
            var group = ArrayList<Int>()
            var count = 0
            fun flush(index: Int) {
                if (group.isEmpty()) return
                val units = group.flatMap { unitsByLine[it].orEmpty() }
                if (units.isNotEmpty()) {
                    val from = (durationMs.toDouble() * index / chunks - LEAD_MS).toLong().coerceAtLeast(0)
                    val to = (durationMs.toDouble() * (index + 1) / chunks + TAIL_MS).toLong().coerceAtMost(durationMs)
                    plans += Plan(units, from, max(from + 1_000, to))
                }
                group = ArrayList(); count = 0
            }
            for (i in nonGap) {
                group += i; count += unitsByLine[i].orEmpty().size
                if (count >= target) flush(plans.size)
            }
            flush(plans.size)
        }
        return plans.filter { it.toMs > it.fromMs && it.units.isNotEmpty() }
    }
}
