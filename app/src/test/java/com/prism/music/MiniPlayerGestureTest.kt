package com.prism.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.media3.exoplayer.ExoPlayer
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.player.MiniPlayer
import com.prism.music.ui.player.PlayerMorph
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 1.1.3, the mini player's own swipe: a swipe up from anywhere on the card is a swipe up, however the hand started.
 *
 * The card had a detector for each way, racing for the same touch: a fast swipe whose thumb-arc went sideways first
 * lost to the sideways one, the card slid a little and the player never opened (found by hand on a phone, at the
 * player-covering swipe it was most wanted for). One gesture now picks its way from the whole movement, and up (or
 * down) wins as soon as the hand has gone that way, however much it drifted sideways getting there.
 *
 * Taps are unchanged: the card's own opens the player, and the buttons do what they say — including a swipe that
 * begins on one of them, which must never also press it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi", application = android.app.Application::class)
class MiniPlayerGestureTest {
    @get:Rule val compose = createComposeRule()

    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    private val container by made
    private var exo: ExoPlayer? = null
    private var opened = 0
    private var prevButtonWas = false

    private val songs = listOf("One", "Two", "Three").mapIndexed { i, n ->
        Song("s$i", n, artists = listOf(ArtistRef("Artist")), durationSec = 200)
    }

    @Before fun setUp() {
        val s = container.settings
        prevButtonWas = s.current.miniPrevButton
        // The mini player's Previous button is off as Prism comes (swiping right goes back): it's asked for here, so
        // a swipe that starts on it is one of the places tried.
        kotlinx.coroutines.runBlocking { s.setMiniPrevButton(true) }
        val p = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        exo = p
        container.player.testPlayer = p
        p.addListener(container.player)
        container.player.playQueue(songs, 0, QueueSource(QueueKind.OTHER, "mini player gestures"))
        val until = System.currentTimeMillis() + 10_000
        while (container.player.currentSong.value == null && System.currentTimeMillis() < until) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("a song is playing", container.player.currentSong.value != null)
    }

    @After fun close() {
        PlayerMorph.snap(0f)
        runCatching { container.player.stop() }
        runCatching { exo?.release() }
        container.player.testPlayer = null
        if (!prevButtonWas) kotlinx.coroutines.runBlocking { container.settings.setMiniPrevButton(false) }
        made.closeAfterTest()
    }

    private fun mini(): SemanticsNodeInteraction =
        compose.onAllNodes(hasTestTag("miniPlayer"), useUnmergedTree = true).onFirst()

    private fun show() {
        compose.setContent {
            val s by container.settings.stored.collectAsState()
            CompositionLocalProvider(LocalContainer provides container) {
                PrismTheme(s, artworkColor = null) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        MiniPlayer(Modifier.align(Alignment.BottomCenter)) { opened++ }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** A gesture from [x], in [moves] of (dx, dy) px, the finger lifted at the end. */
    private fun dragAt(x: Float, moves: List<Offset>) {
        mini().performTouchInput {
            down(Offset(x, centerY))
            moves.forEach { moveBy(it) }
            up()
        }
    }

    /** The five places a hand may start: the cover, the title, and the three buttons (density is 2 on xhdpi). */
    private fun places(): List<Pair<String, Float>> {
        val w = mini().fetchSemanticsNode().boundsInRoot
        return listOf(
            "cover" to (w.left + 16f * 2f + 22f * 2f),
            "title" to (w.left + 250f * 2f),
            "previous" to (w.right - 8f * 2f - 40f * 2f - 44f * 2f - 20f * 2f),
            "play" to (w.right - 8f * 2f - 40f * 2f - 22f * 2f),
            "next" to (w.right - 8f * 2f - 20f * 2f),
        )
    }

    @Test fun aSwipeUpFromAnywhereOnTheCardIsASwipe() {
        show()
        val gestures = mapOf(
            "straight" to List(8) { Offset(0f, -40f) },
            "a flick" to List(2) { Offset(0f, -120f) },
            // A thumb's arc: the hand goes sideways first, faster than it goes up (the case that used to do nothing).
            "drifting right" to listOf(Offset(34f, -12f), Offset(-4f, -60f), Offset(0f, -180f), Offset(0f, -180f)),
            "drifting left" to listOf(Offset(-34f, -12f), Offset(4f, -60f), Offset(0f, -180f), Offset(0f, -180f)),
            // The same, slowly: the sideways way is never the longer one for long.
            "drifting right slowly" to listOf(Offset(10f, -6f), Offset(14f, -9f), Offset(-6f, -160f), Offset(0f, -160f)),
            "drifting left slowly" to listOf(Offset(-10f, -6f), Offset(-14f, -9f), Offset(6f, -160f), Offset(0f, -160f)),
        )
        for ((place, x) in places()) {
            for ((gesture, moves) in gestures) {
                PlayerMorph.snap(0f)
                compose.waitForIdle()
                val songBefore = exo!!.currentMediaItemIndex
                val playingBefore = exo!!.playWhenReady
                dragAt(x, moves)
                val progress = PlayerMorph.progress
                assertTrue("$place, $gesture: the swipe follows the hand (progress $progress)", progress > 0.01f)
                assertEquals("$place, $gesture: a swipe is not a button", songBefore, exo!!.currentMediaItemIndex)
                assertEquals("$place, $gesture: nor play / pause", playingBefore, exo!!.playWhenReady)
            }
        }
    }

    @Test fun sidewaysSwipesAndPullingDownStillDoWhatTheyDid() {
        show()
        val w = mini().fetchSemanticsNode().boundsInRoot
        val song = exo!!.currentMediaItemIndex
        // Left: Next, and Right: Previous, as Prism comes.
        dragAt(w.center.x, listOf(Offset(-40f, 2f), Offset(-120f, 0f), Offset(-120f, 0f)))
        compose.waitForIdle()
        assertEquals("a sideways swipe left still goes to the next song", song + 1, exo!!.currentMediaItemIndex)
        dragAt(w.center.x, listOf(Offset(40f, -2f), Offset(120f, 0f), Offset(120f, 0f)))
        compose.waitForIdle()
        assertEquals("a sideways swipe right still goes back", song, exo!!.currentMediaItemIndex)
        assertEquals("and neither opens the player", 0, opened)
        // Down: Stop (the card falls away, then the music stops).
        dragAt(w.center.x, listOf(Offset(0f, 60f), Offset(0f, 100f), Offset(0f, 100f)))
        compose.waitUntil(5_000) { container.player.currentSong.value == null }
    }

    @Test fun tapsStillLand() {
        show()
        val w = mini().fetchSemanticsNode().boundsInRoot
        val song = exo!!.currentMediaItemIndex
        // The cover: the card's own tap opens the player.
        mini().performTouchInput { down(Offset(w.left + 16f * 2f + 22f * 2f, centerY)); up() }
        compose.waitForIdle()
        assertEquals("a tap on the cover opens the player", 1, opened)
        // A button does its own thing, and doesn't open the player as well.
        compose.onAllNodes(hasContentDescription(tr(R.string.widget_next))).onFirst().performClick()
        compose.waitForIdle()
        assertEquals("Next plays the next song", song + 1, exo!!.currentMediaItemIndex)
        assertEquals("and doesn't open the player", 1, opened)
    }
}
