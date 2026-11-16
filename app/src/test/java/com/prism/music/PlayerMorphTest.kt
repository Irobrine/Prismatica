package com.prism.music

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import com.prism.music.data.prefs.PlayerGesture
import com.prism.music.ui.player.MorphSettle
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
 * finger, finished at a tenth of the window, and the pages a gesture may name on the way in.
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

    /** A tenth of the window is where a swipe finishes: the same distance in, and the same distance out. */
    @Test fun aTenthOfTheWindowFinishesItBothWays() {
        assertEquals(0.10f, PlayerMorph.THRESHOLD, 0.0001f)
        assertEquals(0.40f, PlayerMorph.TRAVEL, 0.0001f)
        assertEquals(0.25f, PlayerMorph.COMPLETE_AT, 0.0001f)
        // Closing runs the same number backwards: a tenth of the window down is the same share in from the top.
        assertEquals(1f - PlayerMorph.COMPLETE_AT, 1f - PlayerMorph.THRESHOLD / PlayerMorph.TRAVEL, 0.0001f)
        val span = 1000f
        assertEquals(0f, PlayerMorph.progressForTravel(0f, span, towardPlayer = true), 0.0001f)
        assertEquals(0.2f, PlayerMorph.progressForTravel(200f, span, towardPlayer = true), 0.0001f)
        assertEquals(0.8f, PlayerMorph.progressForTravel(200f, span, towardPlayer = false), 0.0001f)
        assertEquals(0f, PlayerMorph.progressForTravel(-30f, span, towardPlayer = true), 0.0001f)
        assertEquals(1f, PlayerMorph.progressForTravel(2000f, span, towardPlayer = true), 0.0001f)
    }

    /** Letting go past the tenth finishes each way; short of it falls back to where it came from. */
    @Test fun lettingGoPastsTheTenthFinishes() {
        assertFalse(PlayerMorph.completes(0.24f, towardPlayer = true))
        assertTrue(PlayerMorph.completes(0.25f, towardPlayer = true))
        assertFalse(PlayerMorph.completes(0.76f, towardPlayer = false))
        assertTrue(PlayerMorph.completes(0.75f, towardPlayer = false))
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
        assertEquals("still the moving copy, on its way up", 0f, PlayerMorph.elementReveal, 0.0001f)
        PlayerMorph.snap(1f)
        assertEquals(1f, PlayerMorph.elementReveal, 0.0001f)
        assertEquals(1f, PlayerMorph.playReveal, 0.0001f)
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

    /** The moving copies are the only ones until the very end; at rest the player's own take over, with no overlap. */
    @Test fun theHandOverHappensInOneStep() {
        PlayerMorph.miniTitle = androidx.compose.ui.geometry.Rect(0f, 0f, 120f, 20f)
        PlayerMorph.playerTitle = androidx.compose.ui.geometry.Rect(0f, 100f, 320f, 60f)
        PlayerMorph.snap(0.9999f)
        assertTrue("still the moving copy", PlayerMorph.drawsOverlay())
        assertEquals("the player's own is still away", 0f, PlayerMorph.elementReveal, 0.0001f)
        assertTrue(PlayerMorph.hidesTitle)
        PlayerMorph.snap(1f)
        assertFalse("the copy is gone", PlayerMorph.drawsOverlay())
        assertEquals("the player's own is there", 1f, PlayerMorph.elementReveal, 0.0001f)
        assertFalse(PlayerMorph.hidesTitle)
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

    /** The moving line gets the wider row's room throughout: its ending isn't lost to a width between the two. */
    @Test fun theMovingLineKeepsThePlayersRoom() {
        val mini = androidx.compose.ui.geometry.Rect(0f, 0f, 300f, 40f)
        val player = androidx.compose.ui.geometry.Rect(0f, 0f, 640f, 60f)
        assertEquals(640, com.prism.music.ui.player.morphRoom(mini, player))
        assertEquals(640, com.prism.music.ui.player.morphRoom(player, mini))
    }

    // ---- How a release is finished: at the hand's speed, into the place, in less time when there's less way.

    private fun settle(from: Float, to: Float, velocity: Float, ms: Int = 340) =
        target(from, to, MorphSettle.spec(from, to, velocity, ms = ms, reduceMs = 140, reduceMotion = false))

    private fun target(from: Float, to: Float, spec: androidx.compose.animation.core.AnimationSpec<Float>) =
        TargetBasedAnimation(
            animationSpec = spec,
            typeConverter = Float.VectorConverter,
            initialValue = from,
            targetValue = to,
        )

    /** The first frames move at the speed the hand let go at: no stop dead, no setting off again. */
    @Test fun aReleaseCarriesTheHandsSpeed() {
        val anim = settle(0.3f, 1f, velocity = 2f)
        val speed = (anim.getValueFromNanos(1_000_000L) - anim.getValueFromNanos(0L)) / 0.001f
        assertEquals("progress a second", 2f, speed, 0.05f)
        // And it arrives at the place, not still moving past it (nothing to stop dead at the other end).
        assertEquals(1f, anim.getValueFromNanos(anim.durationNanos), 0.0001f)
    }

    /** A hand let go the other way is finished from where it is: it doesn't wind up backwards first. */
    @Test fun aHandGoingTheOtherWayIsNotCarried() {
        val anim = settle(0.3f, 1f, velocity = -2f)
        val speed = (anim.getValueFromNanos(1_000_000L) - anim.getValueFromNanos(0L)) / 0.001f
        assertEquals("starts from where it is", 0f, speed, 0.05f)
    }

    /** Less way to go, less time: the last tenth doesn't take as long as the whole way did. */
    @Test fun aShorterWayTakesLessTime() {
        assertTrue(settle(0.9f, 1f, 0f).durationNanos < settle(0f, 1f, 0f).durationNanos)
    }

    /** A hard flick is over in a moment rather than slowed down first to be shown. */
    @Test fun aFlickFinishesSooner() {
        assertTrue(settle(0.3f, 1f, velocity = 12f).durationNanos < settle(0.3f, 1f, 0f).durationNanos)
    }

    /** With Reduce motion there is no carry: the same plain fade, whatever the hand was doing. */
    @Test fun reduceMotionIsAPlainFade() {
        val spec = MorphSettle.spec(0.3f, 1f, velocity = 4f, ms = 340, reduceMs = 140, reduceMotion = true)
        assertEquals(140_000_000L, target(0.3f, 1f, spec).durationNanos)
    }
}
