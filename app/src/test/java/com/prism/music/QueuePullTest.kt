package com.prism.music

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.prism.music.ui.player.PanelPull
import com.prism.music.ui.player.QueuePullToClose
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scrolling up at the very top of the queue (with nothing above it left to show) closes it: the pull runs the player's
 * own morph back under the finger, then settles from wherever the hand left it. What the list can still scroll stays
 * the list's, and pulling back up puts the panel in its place before the list moves again.
 */
class QueuePullTest {
    private class FakePull : PanelPull {
        var began = 0
        val follows = mutableListOf<Float>()
        var released: Float? = null
        var cancels = 0
        override fun begin() { began++ }
        override fun follow(px: Float) { follows += px }
        override fun release(velocity: Float) { released = velocity }
        override fun cancel() { cancels++ }
    }

    private val pull = FakePull()
    private var atTop = true
    private val puller = QueuePullToClose(atTop = { atTop }, pull = pull)
    private fun dragBy(px: Float) = puller.onPreScroll(Offset(0f, px), NestedScrollSource.UserInput)

    @Test fun pullingDownAtTheTopTakesThePanelWithTheFinger() {
        assertEquals(Offset(0f, 120f), dragBy(120f))
        assertEquals(1, pull.began)
        assertEquals(listOf(120f), pull.follows)
        assertEquals(Offset(0f, 30f), dragBy(30f))
        assertEquals(listOf(120f, 150f), pull.follows)
        assertEquals(0, pull.cancels)
    }

    @Test fun withSongsAboveTheListScrollsInstead() {
        atTop = false
        assertEquals(Offset.Zero, dragBy(120f))
        assertEquals(0, pull.began)
        assertTrue(pull.follows.isEmpty())
    }

    @Test fun pullingBackUpReturnsThePanelBeforeTheListScrolls() {
        dragBy(120f)
        assertEquals(Offset(0f, -40f), dragBy(-40f))
        assertEquals(80f, pull.follows.last())
        assertEquals(0, pull.cancels)
        // The panel is back in its place: the rest of the drag goes to the list.
        assertEquals(Offset(0f, -80f), dragBy(-100f))
        assertEquals(0f, pull.follows.last())
        assertEquals(Offset.Zero, dragBy(-10f))
    }

    /** Back at its start before the finger lifted, the panel stops being dragged: the panel's flags drive it again. */
    @Test fun aPullBackedUpToItsStartEndsThere() = runBlocking {
        dragBy(120f)
        dragBy(-120f)
        assertEquals(1, pull.cancels)
        assertEquals(Velocity.Zero, puller.onPreFling(Velocity(0f, 900f)))
        assertNull(pull.released)
    }

    @Test fun lettingGoSettlesFromWhereThePanelIs() = runBlocking {
        dragBy(120f)
        assertEquals(Velocity(0f, 900f), puller.onPreFling(Velocity(0f, 900f)))
        assertEquals(900f, pull.released)
        assertEquals(0, pull.cancels)
    }

    @Test fun aDragThatNeverBecameAPullIsAlone() = runBlocking {
        assertEquals(Velocity.Zero, puller.onPreFling(Velocity(0f, 900f)))
        assertEquals(0, pull.began)
        assertNull(pull.released)
        assertEquals(0, pull.cancels)
    }

    /** Letting go settles the panel where it is; the next pull runs from the panel's own place again. */
    @Test fun aSecondPullStartsFromThePanelsPlace() = runBlocking {
        dragBy(120f)
        puller.onPreFling(Velocity.Zero)
        dragBy(10f)
        assertEquals(2, pull.began)
        assertEquals(10f, pull.follows.last())
    }

    @Test fun aProgrammaticScrollIsLeftAlone() {
        assertEquals(Offset.Zero, puller.onPreScroll(Offset(0f, 120f), NestedScrollSource.SideEffect))
        assertEquals(0, pull.began)
    }
}
