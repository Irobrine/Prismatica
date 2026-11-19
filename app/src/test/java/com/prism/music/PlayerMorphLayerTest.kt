package com.prism.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.prism.music.data.prefs.AppSettings
import com.prism.music.ui.player.PlayerInk
import com.prism.music.ui.player.PlayerMorph
import com.prism.music.ui.player.PlayerMorphLayer
import com.prism.music.ui.theme.PrismTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The moving copies of the mini player → full player morph: drawn only while it's in flight, and gone at either end,
 * where the real title, artist and play button are the ones showing. Each follows the finger (a new number redraws
 * them), and Reduce motion leaves them out altogether.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class PlayerMorphLayerTest {
    @get:Rule val compose = createComposeRule()

    @Before fun setUp() {
        PlayerMorph.animationsOff = false
        PlayerMorph.snap(0f)
        // A mini player card near the bottom and the player's places above it, in window pixels (xhdpi: 2 px per dp).
        PlayerMorph.miniTitle = Rect(32f, 1400f, 360f, 1440f)
        PlayerMorph.playerTitle = Rect(48f, 400f, 648f, 520f)
        PlayerMorph.miniArtist = Rect(32f, 1444f, 360f, 1480f)
        PlayerMorph.playerArtist = Rect(48f, 528f, 648f, 600f)
        PlayerMorph.miniPlay = Rect(620f, 1380f, 708f, 1468f)
        PlayerMorph.playerPlay = Rect(300f, 1100f, 484f, 1284f)
    }

    private fun show() {
        compose.setContent {
            PrismTheme(AppSettings(), artworkColor = null) {
                Box(Modifier.fillMaxSize()) { PlayerMorphLayer("A song", "An artist", isPlaying = true) }
            }
        }
    }

    @After fun putItBack() {
        PlayerMorph.snap(0f)
        PlayerMorph.miniTitle = null
        PlayerMorph.miniArtist = null
        PlayerMorph.miniPlay = null
        PlayerMorph.playerTitle = null
        PlayerMorph.playerArtist = null
        PlayerMorph.playerPlay = null
        PlayerMorph.animationsOff = false
        PlayerInk.light = false
    }

    @Test fun nothingMovesAtEitherEnd() {
        show()
        compose.waitForIdle()
        compose.onNodeWithTag("playerMorphTitle").assertDoesNotExist()
        compose.runOnIdle { PlayerMorph.snap(1f) }
        compose.waitForIdle()
        compose.onNodeWithTag("playerMorphTitle").assertDoesNotExist()
        compose.onNodeWithTag("playerMorphPlay").assertDoesNotExist()
    }

    @Test fun theCopiesAreThereWhileItMoves() {
        PlayerMorph.snap(0.5f)
        show()
        compose.waitForIdle()
        compose.onNodeWithTag("playerMorphTitle").assertIsDisplayed()
        compose.onNodeWithTag("playerMorphArtist").assertIsDisplayed()
        compose.onNodeWithTag("playerMorphPlay").assertIsDisplayed()
    }

    /** A finger writing the number (a drag) redraws the copies: they aren't a fixed-duration animation. */
    @Test fun aMovingFingerRedrawsTheCopies() {
        show()
        compose.waitForIdle()
        compose.onNodeWithTag("playerMorphTitle").assertDoesNotExist()
        compose.runOnIdle { PlayerMorph.snap(0.5f) }
        compose.waitForIdle()
        compose.onNodeWithTag("playerMorphTitle").assertExists()
        compose.runOnIdle { PlayerMorph.snap(0.8f) }
        compose.waitForIdle()
        compose.onNodeWithTag("playerMorphTitle").assertExists()
        compose.runOnIdle { PlayerMorph.snap(1f) }
        compose.waitForIdle()
        compose.onNodeWithTag("playerMorphTitle").assertDoesNotExist()
    }

    /**
     * Over the White player the real play button is drawn through PlayerInk (a black disc with a white glyph). The copy
     * has to land on that, not on the white disc it uses on every other background: the hand-over there would otherwise
     * flip the control's colors in one frame.
     */
    @Test fun overTheWhitePlayerTheCopyLandsInked() {
        PlayerInk.light = true
        PlayerMorph.snap(0.999f)
        show()
        compose.waitForIdle()
        val image = compose.onNodeWithTag("playerMorphPlay").captureToImage().asAndroidBitmap()
        var dark = 0
        var bright = 0
        for (y in 0 until image.height step 2) for (x in 0 until image.width step 2) {
            val pixel = image.getPixel(x, y)
            if (android.graphics.Color.alpha(pixel) <= 200) continue
            val l = (0.2126f * ((pixel shr 16) and 0xFF) + 0.7152f * ((pixel shr 8) and 0xFF) + 0.0722f * (pixel and 0xFF)) / 255f
            if (l < 0.2f) dark++
            if (l > 0.8f) bright++
        }
        assertTrue("the disc reads dark over White ($dark dark, $bright bright)", dark > 2_000)
        assertTrue("and its glyph light ($bright bright)", bright > 20)
    }

    @Test fun reduceMotionLeavesThemOut() {
        PlayerMorph.animationsOff = true
        PlayerMorph.snap(0.5f)
        show()
        compose.waitForIdle()
        compose.onNodeWithTag("playerMorphTitle").assertDoesNotExist()
        assertTrue(PlayerMorph.elementReveal == 1f)
    }
}
