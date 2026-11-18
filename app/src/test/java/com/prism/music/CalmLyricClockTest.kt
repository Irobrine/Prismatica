package com.prism.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.8.7: the one lyric line on the player moves about 30 times a second, not on every frame of the screen. Each move
 * redraws the whole player (its blurred background too); on the A16 the line over the progress bar kept the player at
 * 87 frames a second and a full core for the whole song.
 */
class CalmLyricClockTest {
    @Test fun theCalmClockKeepsWithTheSong() {
        // The first reading, and a seek: taken as they are.
        assertEquals(5_000.0, com.prism.music.ui.player.calmClock(0.0, 5_000, -1.0, 1f), 0.0)
        assertEquals(90_000.0, com.prism.music.ui.player.calmClock(5_000.0, 90_000, 33.0, 1f), 0.0)
        // The player's own place comes in uneven steps (here: stuck for 100 ms, then caught up): the clock glides on,
        // never further than a few ms from where the song is, and never backwards.
        var shown = 10_000.0; var real = 10_000.0; var worst = 0.0
        repeat(300) { i ->
            real += 33.0
            val reported = if (i % 6 < 3) (real - (i % 3) * 33.0).toLong() else real.toLong()
            val next = com.prism.music.ui.player.calmClock(shown, reported, 33.0, 1f)
            assertTrue("never backwards", next >= shown)
            shown = next
            worst = maxOf(worst, kotlin.math.abs(shown - real))
        }
        assertTrue("within 40 ms of the song, was $worst", worst < 40.0)
        // At 1.5x it moves 1.5 times as fast.
        assertEquals(1_049.5, com.prism.music.ui.player.calmClock(1_000.0, 1_050, 33.0, 1.5f), 0.2)
        assertEquals(33L, com.prism.music.ui.player.CALM_LYRIC_MS)
    }
}
