package com.prism.music

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
 * swipe). The buttons and the card under the finger keep their own taps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp", application = android.app.Application::class)
class MiniPlayerSwipesTest {
    @get:Rule val compose = createComposeRule()

    private class Counters {
        val gestures = ArrayList<PlayerGesture>()
        var rowTaps = 0
        var buttonTaps = 0
        var stops = 0
    }

    private fun harness(): Counters {
        val n = Counters()
        compose.setContent {
            val pull = remember { Animatable(0f) }
            val drag = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()
            val settings = remember { AppSettings() }
            Box(
                Modifier.size(400.dp, 64.dp).background(Color.Gray)
                    .miniPlayerSwipes(
                        height = 64.dp, pull = pull, drag = drag, settings = { settings }, scope = scope,
                        threshold = {}, gestureEnd = {}, onStop = { n.stops++ }, onGesture = { n.gestures += it },
                    )
                    .testTag("bar"),
            ) {
                Row(Modifier.fillMaxSize().combinedClickable(onClick = { n.rowTaps++ })) {
                    Box(Modifier.fillMaxSize().testTag("button").clickable { n.buttonTaps++ })
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

    @Test fun aTapIsStillATap() {
        val n = harness()
        compose.onNodeWithTag("button").performClick()
        compose.waitForIdle()
        assertEquals("the play button takes its own tap", 1, n.buttonTaps)
        assertEquals(0, n.rowTaps)
        assertTrue(n.gestures.isEmpty())
    }
}
