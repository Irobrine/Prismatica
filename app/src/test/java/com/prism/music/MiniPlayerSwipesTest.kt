package com.prism.music

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.PlayerGesture
import com.prism.music.ui.player.miniPlayerSwipes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The mini player's swipes work on its buttons and cover too, and a swipe is never taken for a tap (nor a tap for a
 * swipe). The buttons and the card under the finger keep their own taps and long press.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp", application = android.app.Application::class)
class MiniPlayerSwipesTest {
    @get:Rule val compose = createComposeRule()

    private class Counters {
        val gestures = ArrayList<PlayerGesture>()
        var rowTaps = 0
        var longPresses = 0
        var buttonTaps = 0
        var thresholds = 0
        var endings = 0
        var stops = 0
    }

    /** A card with a play button on its right 300 dp; the left 100 dp is the card and its cover. */
    private fun harness(over: (AppSettings) -> AppSettings = { it }): Counters {
        val n = Counters()
        compose.setContent {
            val pull = remember { Animatable(0f) }
            val drag = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()
            val settings = remember { over(AppSettings()) }
            Box(
                Modifier.size(400.dp, 64.dp).background(Color.Gray)
                    .miniPlayerSwipes(
                        height = 64.dp, pull = pull, drag = drag, settings = { settings }, scope = scope,
                        threshold = { n.thresholds++ }, gestureEnd = { n.endings++ },
                        onStop = { n.stops++ }, onGesture = { n.gestures += it },
                    )
                    .testTag("bar"),
            ) {
                Row(
                    Modifier.fillMaxSize().combinedClickable(
                        onClick = { n.rowTaps++ }, onLongClick = { n.longPresses++ },
                    ),
                ) {
                    Box(Modifier.width(100.dp).fillMaxHeight().testTag("card"))
                    Box(Modifier.width(300.dp).fillMaxHeight().testTag("button").clickable { n.buttonTaps++ })
                }
            }
        }
        return n
    }

    @Test fun aSidewaysSwipeOnThePlayButtonIsASwipe() {
        val n = harness()
        compose.onNodeWithTag("button").performTouchInput { swipeLeft(startX = width - 1f, endX = 1f, durationMillis = 200) }
        compose.waitUntil(2_000) { n.gestures.isNotEmpty() }
        compose.waitForIdle()
        assertEquals(listOf(PlayerGesture.NEXT), n.gestures)
        assertEquals("a swipe is not a tap on the play button", 0, n.buttonTaps)
        assertEquals("nor a tap on the card", 0, n.rowTaps)
        assertTrue("the threshold ticks", n.thresholds > 0)
        assertEquals("the gesture ends", 1, n.endings)
    }

    @Test fun anUpwardSwipeOnThePlayButtonOpensThePlayer() {
        val n = harness()
        compose.onNodeWithTag("button").performTouchInput { swipeUp(startY = bottom - 1f, endY = 1f, durationMillis = 200) }
        compose.waitUntil(2_000) { n.gestures.isNotEmpty() }
        compose.waitForIdle()
        assertEquals(listOf(PlayerGesture.OPEN), n.gestures)
        assertEquals(0, n.buttonTaps)
        assertEquals(0, n.rowTaps)
    }

    @Test fun anUpwardSwipeOnTheCardOrCoverOpensThePlayer() {
        val n = harness()
        compose.onNodeWithTag("bar").performTouchInput {
            swipe(Offset(50f, bottom - 1f), Offset(50f, 1f), 200)
        }
        compose.waitUntil(2_000) { n.gestures.isNotEmpty() }
        compose.waitForIdle()
        assertEquals(listOf(PlayerGesture.OPEN), n.gestures)
        assertEquals(0, n.rowTaps)
        assertEquals(0, n.buttonTaps)
    }

    @Test fun aShortSwipeDoesNothingAndIsNotATap() {
        val n = harness()
        // 100 px, under the 140 px a sideways gesture needs.
        compose.onNodeWithTag("button").performTouchInput { swipeLeft(startX = width - 1f, endX = width - 101f, durationMillis = 200) }
        compose.waitForIdle()
        assertTrue(n.gestures.isEmpty())
        assertEquals(0, n.buttonTaps)
        assertEquals(0, n.rowTaps)
        assertEquals("under the threshold", 0, n.thresholds)
    }

    @Test fun aSwipeSetToNothingDoesNothingAndIsNotATap() {
        val n = harness { it.copy(miniSwipeLeft = PlayerGesture.NONE) }
        compose.onNodeWithTag("button").performTouchInput { swipeLeft(startX = width - 1f, endX = 1f, durationMillis = 200) }
        compose.waitForIdle()
        assertTrue(n.gestures.isEmpty())
        assertEquals(0, n.buttonTaps)
        assertEquals(0, n.rowTaps)
    }

    @Test fun aTapIsStillATap() {
        val n = harness()
        compose.onNodeWithTag("button").performClick()
        compose.waitForIdle()
        assertEquals("the play button takes its own tap", 1, n.buttonTaps)
        assertEquals(0, n.rowTaps)
        assertTrue(n.gestures.isEmpty())

        compose.onNodeWithTag("bar").performTouchInput { click(Offset(50f, centerY)) }
        compose.waitForIdle()
        assertEquals("the card takes its own tap", 1, n.rowTaps)
        assertEquals(1, n.buttonTaps)
        assertTrue(n.gestures.isEmpty())
    }

    @Test fun aLongPressStillOpensTheSongMenu() {
        val n = harness()
        compose.onNodeWithTag("bar").performTouchInput { longClick(Offset(50f, centerY)) }
        compose.waitForIdle()
        assertEquals(1, n.longPresses)
        assertEquals(0, n.rowTaps)
        assertEquals(0, n.buttonTaps)
        assertTrue(n.gestures.isEmpty())
    }
}
