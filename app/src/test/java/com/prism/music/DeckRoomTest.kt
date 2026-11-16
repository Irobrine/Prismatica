package com.prism.music

import com.prism.music.ui.player.deckStripGone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How far the deck gives up its current lyric over a panel: with the panel's own number (so it goes with the finger),
 * and with the lyrics' or Small's, so the deck still eases when what it answers to changes with the panel standing
 * still. What's left is what the parts under it are laid out from — the deck's height is the sum of their rooms, never a
 * second animation of its own.
 */
class DeckRoomTest {
    /** At the player nothing is folded, whatever else is asked of it. */
    @Test fun nothingIsFoldedAtThePlayer() {
        assertEquals(0f, deckStripGone(panel = 0f, lyrics = 0f, small = 0f, lyricsOnPlayer = true), 0.0001f)
        assertEquals(0f, deckStripGone(panel = 0f, lyrics = 1f, small = 1f, lyricsOnPlayer = true), 0.0001f)
    }

    /** Over the lyrics it goes altogether (its words are on the screen); over the queue it stays, unless Small asks. */
    @Test fun theLyricGoesWithTheLyricsAndWithSmall() {
        assertEquals(1f, deckStripGone(panel = 1f, lyrics = 1f, small = 0f, lyricsOnPlayer = true), 0.0001f)
        assertEquals(0f, deckStripGone(panel = 1f, lyrics = 0f, small = 0f, lyricsOnPlayer = true), 0.0001f)
        assertEquals(1f, deckStripGone(panel = 1f, lyrics = 0f, small = 1f, lyricsOnPlayer = true), 0.0001f)
    }

    /** Between the two it's the panel's own share: half way in, half gone, with no cut and no second clock. */
    @Test fun aPanelHalfwayTakesHalfTheLyric() {
        assertEquals(0.5f, deckStripGone(panel = 0.5f, lyrics = 1f, small = 0f, lyricsOnPlayer = true), 0.0001f)
        assertEquals(0.25f, deckStripGone(panel = 1f, lyrics = 0.25f, small = 0f, lyricsOnPlayer = true), 0.0001f)
    }

    /** From the lyrics to the queue the panel doesn't move: the lyric comes back on the lyrics' own number, eased. */
    @Test fun theLyricComesBackWhenTheQueueTakesTheLyricsPlace() {
        assertEquals(0.5f, deckStripGone(panel = 1f, lyrics = 0.5f, small = 0f, lyricsOnPlayer = true), 0.0001f)
        // Either of the two is enough to fold it away.
        assertEquals(1f, deckStripGone(panel = 1f, lyrics = 0.5f, small = 1f, lyricsOnPlayer = true), 0.0001f)
    }

    /** Switched off, the current lyric takes no room at all (and leaves no gap behind it). */
    @Test fun noLyricOnTheDeckLeavesNoRoom() {
        assertEquals(1f, deckStripGone(panel = 0f, lyrics = 0f, small = 0f, lyricsOnPlayer = false), 0.0001f)
        assertEquals(1f, deckStripGone(panel = 0.3f, lyrics = 0.3f, small = 0f, lyricsOnPlayer = false), 0.0001f)
    }

    /** Whatever a finger or an easing hands it, the share stays between none and all. */
    @Test fun theShareStaysBetweenNoneAndAll() {
        assertEquals(0f, deckStripGone(panel = -0.4f, lyrics = 1f, small = 0f, lyricsOnPlayer = true), 0.0001f)
        assertEquals(1f, deckStripGone(panel = 1.4f, lyrics = 1f, small = 0f, lyricsOnPlayer = true), 0.0001f)
    }
}
