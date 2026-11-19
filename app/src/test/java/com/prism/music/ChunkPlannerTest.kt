package com.prism.music

import com.prism.music.data.enhance.AlignLanguage
import com.prism.music.data.enhance.ChunkPlanner
import com.prism.music.data.lyrics.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Long songs are split into pieces the aligner can take, with every line in exactly one piece and useful offsets. */
class ChunkPlannerTest {
    @Test fun aTenMinuteSongIsSplitAndEveryLineIsCoveredOnce() {
        // 200 lines, three seconds apart, ten minutes long: more than the aligner's four-minute pieces.
        val lines = (0 until 200).map { i -> LyricLine(i * 3_000L, "line $i words here", i * 3_000L + 2_500L) }
        val plans = ChunkPlanner.plans(lines, 600_000L, AlignLanguage.ENGLISH)
        assertTrue("a ten-minute song should take more than one piece", plans.size >= 2)
        val covered = plans.flatMap { p -> p.units.map { it.line } }
        assertEquals("every timed line is in exactly one piece", lines.indices.toSet(), covered.toSet())
        for (line in lines.indices) {
            assertEquals("line $line is in one piece", 1, plans.count { p -> p.units.any { it.line == line } })
        }
        for (p in plans) {
            assertTrue("a piece has a positive span", p.toMs > p.fromMs)
            assertTrue("a piece stays inside the model's five minutes: ${p.fromMs}..${p.toMs}", p.toMs - p.fromMs <= 300_000L)
        }
    }

    @Test fun plainLyricsSplitInProportionAndStillCoverEveryLine() {
        val lines = (0 until 120).map { i -> LyricLine(-1, "plain line $i") }
        val plans = ChunkPlanner.plans(lines, 480_000L, AlignLanguage.ENGLISH)
        assertTrue(plans.size >= 2)
        val covered = plans.flatMap { p -> p.units.map { it.line } }
        assertEquals(lines.indices.toSet(), covered.toSet())
        var last = -1L
        for (p in plans.sortedBy { it.fromMs }) {
            assertTrue("pieces move forward", p.fromMs >= last - 1_000)
            last = p.fromMs
        }
    }
}
