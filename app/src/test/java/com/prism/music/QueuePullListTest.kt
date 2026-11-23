package com.prism.music

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.prism.music.ui.player.PanelPull
import com.prism.music.ui.player.QueuePullToClose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pull in a real list, not only the connection's own arithmetic: a drag down at the top of the queue reaches the
 * panel, and one the list can still scroll does not.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w480dp-h800dp-xhdpi")
class QueuePullListTest {
    @get:Rule val compose = createComposeRule()

    private class FakePull : PanelPull {
        var began = 0
        val follows = mutableListOf<Float>()
        var released = false
        override fun begin() { began++ }
        override fun follow(px: Float) { follows += px }
        override fun release(velocity: Float) { released = true }
        override fun cancel() {}
    }

    private fun show(pull: FakePull): () -> LazyListState {
        var state: LazyListState? = null
        compose.setContent {
            val s = rememberLazyListState()
            state = s
            val connection = remember { QueuePullToClose(atTop = { !s.canScrollBackward }, pull = pull) }
            LazyColumn(state = s, modifier = Modifier.fillMaxSize().nestedScroll(connection).testTag("list")) {
                items(100) { i -> Text("Row $i", Modifier.height(56.dp)) }
            }
        }
        compose.waitForIdle()
        return { state!! }
    }

    private fun dragDown(by: Float) = compose.onNodeWithTag("list").performTouchInput {
        swipe(Offset(center.x, center.y), Offset(center.x, center.y + by), durationMillis = 200)
    }

    @Test fun aDragDownAtTheTopPullsThePanel() {
        val pull = FakePull()
        show(pull)
        dragDown(300f)
        compose.waitForIdle()
        assertEquals(1, pull.began)
        assertTrue("the panel followed the finger", pull.follows.any { it > 0f })
        assertTrue("letting go settled it", pull.released)
    }

    @Test fun aDragTheListCanScrollStaysWithTheList() {
        val pull = FakePull()
        val state = show(pull)
        compose.runOnIdle { kotlinx.coroutines.runBlocking { state().scrollToItem(20) } }
        compose.waitForIdle()
        dragDown(120f)
        compose.waitForIdle()
        assertEquals(0, pull.began)
        assertTrue(pull.follows.isEmpty())
    }
}
