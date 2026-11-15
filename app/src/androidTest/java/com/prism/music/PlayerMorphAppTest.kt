package com.prism.music

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.PlayerGesture
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.player.PlayerMorph
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The morph, by hand on a phone: the mini player follows a swipe up (and only finishes past a quarter of the window),
 * a tap takes the same way, the player comes back down with the finger, and the queue comes and goes on the controls'
 * and the cover's swipes.
 */
@RunWith(AndroidJUnit4::class)
class PlayerMorphAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val tones = ArrayList<android.net.Uri>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val player get() = Fixture.container.player
    private val settings get() = Fixture.container.settings

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { player.stop() } }
        tones.forEach { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
        PlayerMorph.snap(0f)
    }

    /** [names] tones queued, the first playing. */
    private fun songs(vararg names: String) {
        Fixture.ready()
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        names.forEach { tones += Fixture.tone(it, 120) }
        Fixture.launch()
        val local = Fixture.container.localMusic
        runBlocking { local.scan() }
        val list = names.map { n -> local.tracks.value.first { it.song.title == n }.song }
        instrumentation.runOnMainSync { player.playQueue(list, 0, QueueSource(QueueKind.OTHER, "Player morph test")) }
        compose.waitUntil(20_000) { player.currentSong.value?.id == list[0].id }
    }

    private fun shown(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun leftOf(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot.left
    private fun mini(): SemanticsNodeInteraction = compose.onAllNodes(hasTestTag("miniPlayer"), useUnmergedTree = true).onFirst()
    private fun rootHeight() = compose.onRoot().fetchSemanticsNode().boundsInRoot.height

    private fun openPlayer() {
        Fixture.context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("prism://player"))
                .setClass(Fixture.context, PrismActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        compose.waitUntil(15_000) { shown("playerDeck") }
        instrumentation.runOnMainSync { player.setPlaying(false) }
        compose.waitForIdle()
    }

    /** The mini player's own swipe: a quarter of the window finishes it, and it follows the finger before that. */
    @Test fun aSwipeUpOnTheMiniPlayerMorphsAndFinishes() {
        songs("Prism morph swipe")
        compose.waitUntil(15_000) { shown("miniPlayer") }
        val h = rootHeight()
        mini().performTouchInput {
            down(center)
            moveBy(Offset(0f, -h * 0.14f))
        }
        compose.waitForIdle()
        val middle = PlayerMorph.progress
        assertTrue("it follows the finger: $middle", middle > 0f && middle < 1f)
        mini().performTouchInput {
            moveBy(Offset(0f, -h * 0.16f))
            up()
        }
        compose.waitUntil(15_000) { shown("playerDeck") && PlayerMorph.progress > 0.999f }
    }

    /** Short of the quarter, it comes back to the mini player. */
    @Test fun aShortSwipeUpFallsBack() {
        songs("Prism morph short")
        compose.waitUntil(15_000) { shown("miniPlayer") }
        val h = rootHeight()
        mini().performTouchInput { swipeUpBy(h * 0.10f) }
        compose.waitUntil(5_000) { PlayerMorph.progress <= 0.001f }
        assertFalse(shown("playerDeck"))
        assertTrue("the mini player's still there", shown("miniPlayer"))
    }

    /** Up, then down past where it started: the same gesture in reverse, and it falls back. */
    @Test fun aSwipeThatTurnsBackFallsBack() {
        songs("Prism morph back")
        compose.waitUntil(15_000) { shown("miniPlayer") }
        val h = rootHeight()
        mini().performTouchInput {
            down(center)
            moveBy(Offset(0f, -h * 0.30f))
            moveBy(Offset(0f, h * 0.36f))
            up()
        }
        compose.waitUntil(5_000) { PlayerMorph.progress <= 0.001f }
        assertFalse(shown("playerDeck"))
    }

    @Test fun aTapOnTheMiniPlayerTakesTheSameWay() {
        songs("Prism morph tap")
        compose.waitUntil(15_000) { shown("miniPlayer") }
        mini().performClick()
        compose.waitUntil(15_000) { shown("playerDeck") && PlayerMorph.progress > 0.999f }
    }

    /** Closing: pulled down with the finger, the same morph runs backwards. */
    @Test fun pullingThePlayerDownGoesBackToTheMini() {
        songs("Prism morph down")
        compose.waitUntil(15_000) { shown("miniPlayer") }
        openPlayer()
        val h = rootHeight()
        compose.onAllNodes(hasTestTag("playerArtBox"), useUnmergedTree = true).onFirst().performTouchInput {
            swipe(Offset(centerX, height * 0.2f), Offset(centerX, height * 0.2f + h * 0.30f), 300)
        }
        compose.waitUntil(10_000) { !shown("playerDeck") && shown("miniPlayer") }
    }

    /** "Swipe up for the queue": up on the right of the controls opens it (following the finger); down closes it. */
    @Test fun theQueueOpensAndClosesWithTheControlsSwipe() {
        settings.setPanelSwipes(true)
        compose.waitUntil(5_000) { settings.current.behavior.panelSwipes }
        songs("Prism morph queue")
        compose.waitUntil(15_000) { shown("miniPlayer") }
        openPlayer()
        val h = rootHeight()
        compose.onAllNodes(hasTestTag("playerDeck"), useUnmergedTree = true).onFirst().performTouchInput {
            swipe(Offset(width * 0.95f, height * 0.05f), Offset(width * 0.95f, height * 0.05f - h * 0.30f), 300)
        }
        compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(tr(R.string.queue)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // And back down.
        compose.onAllNodes(hasTestTag("playerDeck"), useUnmergedTree = true).onFirst().performTouchInput {
            swipe(Offset(width * 0.95f, height * 0.05f), Offset(width * 0.95f, height * 0.05f + h * 0.30f), 300)
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(tr(R.string.queue)), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }

    /** The cover's own swipe up (the queue, by default) opens it through the same morph. */
    @Test fun theCoverSwipeStillOpensTheQueue() {
        songs("Prism morph cover")
        compose.waitUntil(15_000) { shown("miniPlayer") }
        openPlayer()
        val h = rootHeight()
        compose.onAllNodes(hasTestTag("playerArtBox"), useUnmergedTree = true).onFirst().performTouchInput {
            swipe(Offset(centerX, height * 0.7f), Offset(centerX, height * 0.7f - h * 0.30f), 300)
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(tr(R.string.queue)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** The cover's swipe up set to the lyrics opens them the same way the controls' swipe does. */
    @Test fun theCoverSwipeCanOpenTheLyrics() {
        runBlocking { settings.setGesture("cover_swipe_up", PlayerGesture.LYRICS) }
        compose.waitUntil(5_000) { settings.current.coverSwipeUp == PlayerGesture.LYRICS }
        songs("Prism morph cover lyrics")
        compose.waitUntil(15_000) { shown("miniPlayer") }
        openPlayer()
        val h = rootHeight()
        val before = leftOf("playerTitle")
        compose.onAllNodes(hasTestTag("playerArtBox"), useUnmergedTree = true).onFirst().performTouchInput {
            down(center)
            moveBy(Offset(0f, -h * 0.15f))
        }
        compose.waitForIdle()
        val middle = leftOf("playerTitle")
        assertTrue("the sleeve follows the finger: before $before, at $middle", middle > before + 10f)
        compose.onAllNodes(hasTestTag("playerArtBox"), useUnmergedTree = true).onFirst().performTouchInput {
            moveBy(Offset(0f, -h * 0.15f))
            up()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasContentDescription(tr(R.string.close_lyrics)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        runBlocking { settings.setGesture("cover_swipe_up", PlayerGesture.QUEUE) }
    }

    /** Set to the song menu, it makes the same move under the sheet, which opens when the swipe has gone far enough. */
    @Test fun theCoverSwipeCanOpenTheSongMenu() {
        runBlocking { settings.setGesture("cover_swipe_up", PlayerGesture.MENU) }
        compose.waitUntil(5_000) { settings.current.coverSwipeUp == PlayerGesture.MENU }
        songs("Prism morph cover menu")
        compose.waitUntil(15_000) { shown("miniPlayer") }
        openPlayer()
        val h = rootHeight()
        compose.onAllNodes(hasTestTag("playerArtBox"), useUnmergedTree = true).onFirst().performTouchInput {
            swipe(Offset(centerX, height * 0.7f), Offset(centerX, height * 0.7f - h * 0.30f), 300)
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(tr(R.string.player_settings)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        runBlocking { settings.setGesture("cover_swipe_up", PlayerGesture.QUEUE) }
    }

    /** Playback itself is untouched by the morph: the same song plays on, before and after. */
    @Test fun theSongAndItsPlaceStayThroughTheMorph() {
        songs("Prism morph keep")
        compose.waitUntil(15_000) { shown("miniPlayer") }
        openPlayer()
        val position = player.position
        assertTrue("still the same song", player.currentSong.value?.title == "Prism morph keep")
        assertTrue("the playhead was kept", position >= 0)
        val h = rootHeight()
        compose.onAllNodes(hasTestTag("playerArtBox"), useUnmergedTree = true).onFirst().performTouchInput {
            swipe(Offset(centerX, height * 0.2f), Offset(centerX, height * 0.2f + h * 0.30f), 300)
        }
        compose.waitUntil(10_000) { shown("miniPlayer") && !shown("playerDeck") }
        assertTrue("still playing after it closes", player.currentSong.value?.title == "Prism morph keep")
    }

    private fun androidx.compose.ui.test.TouchInjectionScope.swipeUpBy(distance: Float) {
        swipe(Offset(centerX, centerY + distance / 2f), Offset(centerX, centerY - distance / 2f), 250)
    }
}
