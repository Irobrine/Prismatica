package com.prism.music

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.compose.rememberNavController
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.ColorSource
import com.prism.music.data.prefs.DesignLanguage
import com.prism.music.data.prefs.MaterialPalette
import com.prism.music.data.prefs.PlayerBackground
import com.prism.music.data.prefs.ScrubberColor
import com.prism.music.data.prefs.ThemeMode
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Navigator
import com.prism.music.ui.player.NowPlayingScreen
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Material You's player drawn on the PC: the whole phone player in Material 3's light tonal surface (its type and
 * controls in dark ink) and its dark one, with the theme's primary as the progress. PNGs to app/build/material-you/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w412dp-h900dp-xhdpi")
class MaterialYouPlayerRenderTest {
    @get:Rule val compose = createComposeRule()

    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    private val container by made
    private var exo: ExoPlayer? = null

    private val songs = listOf(
        Song("s0", "Northern Lights", artists = listOf(ArtistRef("Ava Lune")), album = null, durationSec = 223),
        Song("s1", "Paper Planes", artists = listOf(ArtistRef("Ava Lune")), album = null, durationSec = 201),
    )

    @Before fun setUp() {
        kotlinx.coroutines.runBlocking { container.settings.editNow { it.clear() } }
        val p = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        exo = p
        container.player.testPlayer = p
        p.addListener(container.player)
        container.player.playQueue(songs, 0, QueueSource(QueueKind.OTHER, "material you"))
        val until = System.currentTimeMillis() + 10_000
        while (container.player.currentSong.value == null && System.currentTimeMillis() < until) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    @After fun close() {
        com.prism.music.ui.player.PlayerMorph.snap(0f)
        runCatching { container.player.stop() }
        runCatching { exo?.release() }
        container.player.testPlayer = null
        kotlinx.coroutines.runBlocking { container.settings.editNow { it.clear() } }
        made.closeAfterTest()
    }

    private fun shoot(name: String, dark: Boolean) {
        val prefs = container.settings
        prefs.setDesignLanguage(DesignLanguage.MATERIAL_YOU)
        prefs.setMaterialPalette(MaterialPalette.TONAL_SPOT)
        prefs.setThemeMode(if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        prefs.setColorSource(ColorSource.CUSTOM)
        prefs.setAccent(0xFF6750A4.toInt())
        prefs.setPlayerBackground(PlayerBackground.THEME)
        prefs.setScrubberColor(ScrubberColor.ACCENT)
        prefs.setFrostedPages(false)
        prefs.setPlayRing(false)
        compose.waitUntil(10_000) {
            val v = prefs.stored.value
            v.shapes.design == DesignLanguage.MATERIAL_YOU && v.themeMode == (if (dark) ThemeMode.DARK else ThemeMode.LIGHT) && v.playerBackground == PlayerBackground.THEME
        }
        com.prism.music.ui.player.PlayerMorph.snap(1f)
        compose.setContent {
            val settings by container.settings.stored.collectAsState()
            val nav = Navigator(rememberNavController(), openPlayer = {})
            CompositionLocalProvider(LocalContainer provides container, LocalNavigator provides nav) {
                PrismTheme(settings, artworkColor = null) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        NowPlayingScreen {}
                    }
                }
            }
        }
        compose.waitForIdle()
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/material-you").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun playerLight() = shoot("player-light", dark = false)
    @Test fun playerDark() = shoot("player-dark", dark = true)
}
