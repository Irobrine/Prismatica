package com.prism.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.prism.music.ui.player.deckRoom
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A deck part folding away over a panel keeps its bottom edge where the part below it sits, so what's under it doesn't
 * move at all — the jump the deck used to make (up, then down again on opening; the same the other way on closing) came
 * from the parts being cut out in one frame while the row's height eased after them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
class DeckFoldRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun show(room: androidx.compose.runtime.MutableState<Float>) {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    // The tag is on a box around the part: the room it takes is the part's own bounds in the deck.
                    Box(Modifier.testTag("strip")) {
                        Box(Modifier.deckRoom(room.value)) { Spacer(Modifier.fillMaxWidth().height(30.dp)) }
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.testTag("buttons")) { Spacer(Modifier.fillMaxWidth().height(40.dp)) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    @Test fun thePartUnderAFoldingOneNeverMoves() {
        val room = mutableStateOf(1f)
        show(room)
        val buttons = bounds("buttons").top
        val strip = bounds("strip")
        assertEquals(30.dp, strip.height)

        // Half folded: half the room, the same bottom edge, and nothing under it moved.
        compose.runOnIdle { room.value = 0.5f }
        assertEquals(15.dp, bounds("strip").height)
        assertEquals("its bottom stays where the part below it sits", strip.bottom, bounds("strip").bottom)
        assertEquals("the part under it holds still", buttons, bounds("buttons").top)

        // Gone: no room at all, and still nothing under it moved.
        compose.runOnIdle { room.value = 0f }
        assertEquals(0.dp, bounds("strip").height)
        assertEquals(buttons, bounds("buttons").top)
    }
}
