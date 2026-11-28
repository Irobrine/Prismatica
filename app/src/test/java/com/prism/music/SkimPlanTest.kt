package com.prism.music

import com.prism.music.ui.screens.SkimPlan
import com.prism.music.ui.screens.previewClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a preview starts and what a skim plays: four windows across the song, in order, never past its end, shorter for a
 * short song. Pure figures, so they're checked without a player.
 */
class SkimPlanTest {
    @Test fun aSkimPlaysFourWindowsInOrderAcrossTheSong() {
        val duration = 240_000L
        val window = SkimPlan.window(duration)
        assertEquals(SkimPlan.WINDOW_MS, window)
        val points = SkimPlan.points(duration)
        assertEquals(4, points.size)
        assertEquals(points.sorted(), points)
        assertTrue("starts near the beginning: ${points.first()}", points.first() < duration * 0.1)
        assertTrue("reaches near the end: ${points.last()}", points.last() >= duration * 0.8)
        // Every window fits: its end is inside the song.
        points.forEach { assertTrue("$it + $window", it + window <= duration) }
    }

    @Test fun aShortSongGetsShorterWindowsAndPointsThatStillFit() {
        val duration = 12_000L
        val window = SkimPlan.window(duration)
        assertTrue("window $window", window < SkimPlan.WINDOW_MS && window >= 2_000L)
        val points = SkimPlan.points(duration, window)
        points.forEach { p -> assertTrue("$p in $duration", p in 0 until duration) }
        points.forEach { p -> assertTrue("$p + $window <= $duration", p + window <= duration) }
    }

    @Test fun aPreviewStartsPastTheIntro() {
        assertEquals(0L, SkimPlan.start(0))
        val start = SkimPlan.start(200_000)
        assertTrue(start in 60_000..80_000)
        assertTrue(SkimPlan.points(0).isEmpty())
    }

    @Test fun aPositionReadsAsMinutesAndSeconds() {
        assertEquals("0:00", previewClock(0))
        assertEquals("0:07", previewClock(7_400))
        assertEquals("3:07", previewClock(187_000))
    }
}
