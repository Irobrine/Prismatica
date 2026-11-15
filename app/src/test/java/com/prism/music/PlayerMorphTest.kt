package com.prism.music

import com.prism.music.data.prefs.PlayerGesture
import com.prism.music.ui.player.PanelShift
import com.prism.music.ui.player.PlayerMorph
import com.prism.music.ui.player.PlayerPage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The numbers the player's morph is built on: one number from the mini player (0) to the full player (1), written by a
 * finger, finished at a quarter of the window, and the pages a gesture may name on the way in.
 */
class PlayerMorphTest {
    @After fun putItBack() {
        PlayerMorph.snap(0f)
        PlayerMorph.request(null)
        PlayerMorph.animationsOff = false
        PlayerMorph.miniTitle = null
        PlayerMorph.miniArtist = null
        PlayerMorph.miniPlay = null
        PlayerMorph.playerTitle = null
        PlayerMorph.playerArtist = null
        PlayerMorph.playerPlay = null
    }

    /** A drag writes the number straight through, and can go either way; beyond either end it stays at that end. */
    @Test fun aGestureWritesTheNumberAndCanTurnBack() {
        PlayerMorph.beginDrag(towardPlayer = true)
        PlayerMorph.dragTo(0.3f)
        assertEquals(0.3f, PlayerMorph.progress, 0.0001f)
        assertTrue(PlayerMorph.dragging)
        assertTrue(PlayerMorph.isMorphing)
        PlayerMorph.dragTo(0.1f)
        assertEquals(0.1f, PlayerMorph.progress, 0.0001f)
        PlayerMorph.dragTo(1.4f)
        assertEquals(1f, PlayerMorph.progress, 0.0001f)
        assertFalse(PlayerMorph.isMorphing)
        PlayerMorph.dragTo(-0.4f)
        assertEquals(0f, PlayerMorph.progress, 0.0001f)
    }

    /** A quarter of the window is where a swipe finishes: the same distance in, and the same distance out. */
    @Test fun aQuarterOfTheWindowFinishesItBothWays() {
        assertEquals(0.25f, PlayerMorph.THRESHOLD, 0.0001f)
        assertEquals(0.40f, PlayerMorph.TRAVEL, 0.0001f)
        assertEquals(0.625f, PlayerMorph.COMPLETE_AT, 0.0001f)
        // Closing runs the same number backwards: a quarter of the window down is the same share in from the top.
        assertEquals(1f - PlayerMorph.COMPLETE_AT, 1f - PlayerMorph.THRESHOLD / PlayerMorph.TRAVEL, 0.0001f)
        val span = 1000f
        assertEquals(0f, PlayerMorph.progressForTravel(0f, span, towardPlayer = true), 0.0001f)
        assertEquals(0.2f, PlayerMorph.progressForTravel(200f, span, towardPlayer = true), 0.0001f)
        assertEquals(0.8f, PlayerMorph.progressForTravel(200f, span, towardPlayer = false), 0.0001f)
        assertEquals(0f, PlayerMorph.progressForTravel(-30f, span, towardPlayer = true), 0.0001f)
        assertEquals(1f, PlayerMorph.progressForTravel(2000f, span, towardPlayer = true), 0.0001f)
    }

    /** Letting go past the quarter finishes each way; short of it falls back to where it came from. */
    @Test fun lettingGoPastsTheQuarterFinishes() {
        assertFalse(PlayerMorph.completes(0.62f, towardPlayer = true))
        assertTrue(PlayerMorph.completes(0.63f, towardPlayer = true))
        assertFalse(PlayerMorph.completes(0.38f, towardPlayer = false))
        assertTrue(PlayerMorph.completes(0.37f, towardPlayer = false))
        // Turning back before letting go leaves it where it started.
        assertFalse("a pull that came back", PlayerMorph.completes(0.9f, towardPlayer = false))
        assertFalse("an open that came back", PlayerMorph.completes(0.2f, towardPlayer = true))
    }

    /** Opening and closing move the same number: the moving words only hand back to the player's own at the very end. */
    @Test fun theMovingWordsHandBackAtTheEnd() {
        PlayerMorph.miniTitle = androidx.compose.ui.geometry.Rect(0f, 0f, 120f, 20f)
        PlayerMorph.playerTitle = androidx.compose.ui.geometry.Rect(0f, 100f, 320f, 60f)
        PlayerMorph.miniPlay = androidx.compose.ui.geometry.Rect(0f, 0f, 44f, 44f)
        PlayerMorph.playerPlay = androidx.compose.ui.geometry.Rect(0f, 500f, 92f, 92f)
        PlayerMorph.snap(0.5f)
        assertTrue(PlayerMorph.hidesTitle)
        assertTrue(PlayerMorph.hidesPlay)
        assertEquals(0f, PlayerMorph.elementReveal, 0.0001f)
        PlayerMorph.snap(0.93f)
        assertTrue("on its way back", PlayerMorph.elementReveal > 0f && PlayerMorph.elementReveal < 1f)
        PlayerMorph.snap(1f)
        assertEquals(1f, PlayerMorph.elementReveal, 0.0001f)
        PlayerMorph.snap(0f)
        assertFalse(PlayerMorph.hidesTitle)
    }

    /** A page named by a gesture is waited for once and taken by the player as it opens. */
    @Test fun aPageIsTakenOnce() {
        PlayerMorph.request(PlayerPage.QUEUE)
        assertEquals(PlayerPage.QUEUE, PlayerMorph.takeRequestedPage())
        assertNull(PlayerMorph.takeRequestedPage())
        PlayerMorph.request(null)
        assertNull(PlayerMorph.takeRequestedPage())
    }

    /** What a gesture on the mini player or the cover opens, and what it leaves alone. */
    @Test fun onlyTheActionsThatNeedThePlayerOpenIt() {
        for (g in listOf(PlayerGesture.OPEN, PlayerGesture.QUEUE, PlayerGesture.LYRICS, PlayerGesture.VIDEO, PlayerGesture.SING)) {
            assertTrue("$g opens the player", PlayerMorph.opensPlayer(g))
        }
        assertEquals(PlayerPage.COVER, PlayerMorph.pageOf(PlayerGesture.OPEN))
        assertEquals(PlayerPage.QUEUE, PlayerMorph.pageOf(PlayerGesture.QUEUE))
        assertEquals(PlayerPage.LYRICS, PlayerMorph.pageOf(PlayerGesture.LYRICS))
        assertNull(PlayerMorph.pageOf(PlayerGesture.VIDEO))
        assertNull(PlayerMorph.pageOf(PlayerGesture.SING))
        for (g in listOf(PlayerGesture.NEXT, PlayerGesture.PREVIOUS, PlayerGesture.PLAY_PAUSE, PlayerGesture.LIKE, PlayerGesture.MENU, PlayerGesture.STOP, PlayerGesture.SLEEP, PlayerGesture.NONE)) {
            assertFalse("$g doesn't open the player", PlayerMorph.opensPlayer(g))
        }
    }

    /** With Reduce motion there is no element morph: the player cross-fades and its own title and play button stay. */
    @Test fun reduceMotionHasNoMovingCopies() {
        PlayerMorph.animationsOff = true
        PlayerMorph.snap(0.5f)
        assertFalse(PlayerMorph.drawsOverlay())
        assertFalse(PlayerMorph.hidesTitle)
        assertFalse(PlayerMorph.hidesPlay)
        assertEquals(1f, PlayerMorph.elementReveal, 0.0001f)
        PlayerMorph.animationsOff = false
        PlayerMorph.snap(0.5f)
        assertTrue(PlayerMorph.drawsOverlay())
    }

    /** A panel follows the finger from either end and stays within the two. */
    @Test fun aPanelFollowsTheFinger() {
        val shift = PanelShift()
        shift.begin()
        shift.follow(0.4f)
        assertTrue(shift.dragging)
        assertEquals(0.4f, shift.value, 0.0001f)
        shift.follow(2f)
        assertEquals(1f, shift.value, 0.0001f)
        shift.follow(-1f)
        assertEquals(0f, shift.value, 0.0001f)
        shift.end()
        assertFalse(shift.dragging)
    }

    /** Every moving part is placed between the two players' places: the ends are exactly where each really is. */
    @Test fun thePlacesAreTheTwoEnds() {
        val mini = androidx.compose.ui.geometry.Rect(20f, 40f, 64f, 84f)
        val player = androidx.compose.ui.geometry.Rect(48f, 200f, 672f, 824f)
        val atMini = com.prism.music.ui.player.lerpRect(mini, player, 0f)
        val atPlayer = com.prism.music.ui.player.lerpRect(mini, player, 1f)
        assertEquals(mini, atMini)
        assertEquals(player, atPlayer)
        val middle = com.prism.music.ui.player.lerpRect(mini, player, 0.5f)
        assertEquals(34f, middle.left, 0.0001f)
        assertEquals(120f, middle.top, 0.0001f)
        assertEquals(368f, middle.right, 0.0001f)
        assertEquals(454f, middle.bottom, 0.0001f)
    }
}
