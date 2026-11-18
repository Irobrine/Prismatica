package com.prism.music

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.ColorSource
import com.prism.music.data.prefs.DesignLanguage
import com.prism.music.data.prefs.MaterialPalette
import com.prism.music.data.prefs.ThemeMode
import com.prism.music.ui.screens.LibraryScreen
import com.prism.music.ui.screens.SearchScreen
import com.prism.music.ui.screens.settings.LooksPage
import com.prism.music.ui.screens.settings.SettingsPageContent
import com.prism.music.ui.screens.settings.SettingsPageId
import com.prism.music.ui.screens.settings.ThemeColorsPage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Material You (1.9) drawn on the PC: Library, Search, Looks, the colors page and Settings' root in Material 3's light
 * and dark palettes, with a tonal-spot and a vibrant palette, onto app/build/material-you/. Look at them when changing
 * the theme.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h2400dp-xhdpi")
class MaterialYouRenderTest : ThemeLooksPageHarness() {
    private fun material(dark: Boolean, palette: MaterialPalette = MaterialPalette.TONAL_SPOT) {
        val prefs = container.settings
        prefs.setDesignLanguage(DesignLanguage.MATERIAL_YOU)
        prefs.setMaterialPalette(palette)
        prefs.setThemeMode(if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        prefs.setColorSource(ColorSource.CUSTOM)
        prefs.setAccent(0xFF6750A4.toInt())
        prefs.setCornerScale(1f)
        await("Material You") { stored.shapes.design == DesignLanguage.MATERIAL_YOU && stored.shapes.palette == palette }
    }

    private fun save(name: String) {
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/material-you").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun libraryLight() {
        material(false); show { LibraryScreen(0.dp) }
        save("library-light")
    }

    @Test fun libraryDark() {
        material(true); show { LibraryScreen(0.dp) }
        save("library-dark")
    }

    @Test fun searchLight() {
        material(false); show { SearchScreen(0.dp) }
        save("search-light")
    }

    @Test fun looksLight() {
        material(false); show { LooksPage(0.dp, onSearch = {}) }
        save("looks-light")
    }

    @Test fun looksDark() {
        material(true); show { LooksPage(0.dp, onSearch = {}) }
        save("looks-dark")
    }

    @Test fun colorsPageLight() {
        material(false); show { ThemeColorsPage(0.dp, onSearch = {}) }
        save("colors-light")
    }

    @Test fun colorsPageDarkVibrant() {
        material(true, MaterialPalette.VIBRANT); show { ThemeColorsPage(0.dp, onSearch = {}) }
        save("colors-dark-vibrant")
    }

    @Test fun tabsAndMiniPlayerLight() {
        material(false); show { SettingsPageContent(SettingsPageId.TABS_MINI_PLAYER, 0.dp, onSearch = {}) }
        save("tabs-mini-light")
    }

    @Test fun playerSettingsLight() {
        material(false); show { SettingsPageContent(SettingsPageId.PLAYER, 0.dp, onSearch = {}) }
        save("player-settings-light")
    }

    @Test fun settingsRootLight() {
        material(false)
        show {
            com.prism.music.ui.screens.settings.SettingsRootContent(
                0.dp, com.prism.music.ui.theme.LocalAppSettings.current, com.prism.music.ui.theme.LocalIsDark.current,
                showBack = false, selected = null, account = {}, onPerformance = {}, onOpen = { _, _ -> }, onRoute = {},
            )
        }
        save("settings-root-light")
    }

    @Test fun settingsRootDark() {
        material(true)
        show {
            com.prism.music.ui.screens.settings.SettingsRootContent(
                0.dp, com.prism.music.ui.theme.LocalAppSettings.current, com.prism.music.ui.theme.LocalIsDark.current,
                showBack = false, selected = null, account = {}, onPerformance = {}, onOpen = { _, _ -> }, onRoute = {},
            )
        }
        save("settings-root-dark")
    }
}
