package com.prism.music

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.PanelSwipes
import com.prism.music.data.prefs.SettingsRepository
import com.prism.music.data.prefs.lyricsElsewhere
import com.prism.music.ui.player.PlayerPage
import com.prism.music.ui.player.page
import com.prism.music.ui.player.panelRise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 1.9: "Swipe up for the queue" becomes "Swipe up on the player", picked per side — the queue or the lyrics from
 * either side, or both the same, or off as every mode was before. Both panels are opened by the one gesture and the
 * one morph, so the sides only pick the page ([PanelSwipes.page]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PanelSwipesTest {
    private fun withStore(block: suspend (SettingsRepository) -> Unit): Unit = runBlocking {
        val r = SettingsRepository(RuntimeEnvironment.getApplication(), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        r.editNow { it.clear() }
        try { block(r) } finally { r.editNow { it.clear() } }
    }

    private suspend fun SettingsRepository.storedWhen(until: (AppSettings) -> Boolean): AppSettings =
        withTimeout(5_000) { stored.first(until) }

    // ------------------------------------------------------------------ What each side opens

    @Test fun theRightAndLeftOfThePlayerOpenTheirOwnPanel() {
        assertEquals(PlayerPage.QUEUE, PanelSwipes.QUEUE_RIGHT.page(right = true))
        assertEquals(PlayerPage.LYRICS, PanelSwipes.QUEUE_RIGHT.page(right = false))
        assertEquals(PlayerPage.LYRICS, PanelSwipes.QUEUE_LEFT.page(right = true))
        assertEquals(PlayerPage.QUEUE, PanelSwipes.QUEUE_LEFT.page(right = false))
        assertEquals(PlayerPage.LYRICS, PanelSwipes.LYRICS_BOTH.page(right = true))
        assertEquals(PlayerPage.LYRICS, PanelSwipes.LYRICS_BOTH.page(right = false))
        assertEquals(PlayerPage.QUEUE, PanelSwipes.QUEUE_BOTH.page(right = true))
        assertEquals(PlayerPage.QUEUE, PanelSwipes.QUEUE_BOTH.page(right = false))
        assertNull("off opens nothing", PanelSwipes.OFF.page(right = true))
        assertNull("off opens nothing", PanelSwipes.OFF.page(right = false))
    }

    @Test fun onlyTheModesThatBringTheLyricsOpenThem() {
        assertTrue(PanelSwipes.QUEUE_RIGHT.opensLyrics)
        assertTrue(PanelSwipes.QUEUE_LEFT.opensLyrics)
        assertTrue(PanelSwipes.LYRICS_BOTH.opensLyrics)
        assertFalse("the queue from both sides leaves the lyrics to the buttons", PanelSwipes.QUEUE_BOTH.opensLyrics)
        assertFalse(PanelSwipes.OFF.opensLyrics)
    }

    /** The ⋯ menu may fold "Show lyrics" away only while something else opens them (the swipe, in these modes). */
    @Test fun theMenuFoldsShowLyricsOnlyWhileASideBringsThem() {
        val noLyricLine = AppSettings().copy(lyricsOnPlayer = false)
        assertFalse(noLyricLine.lyricsElsewhere())
        assertTrue(noLyricLine.copy(behavior = noLyricLine.behavior.copy(panelSwipes = PanelSwipes.QUEUE_RIGHT)).lyricsElsewhere())
        assertTrue(noLyricLine.copy(behavior = noLyricLine.behavior.copy(panelSwipes = PanelSwipes.QUEUE_LEFT)).lyricsElsewhere())
        assertTrue(noLyricLine.copy(behavior = noLyricLine.behavior.copy(panelSwipes = PanelSwipes.LYRICS_BOTH)).lyricsElsewhere())
        assertFalse(noLyricLine.copy(behavior = noLyricLine.behavior.copy(panelSwipes = PanelSwipes.QUEUE_BOTH)).lyricsElsewhere())
    }

    /** 1.9: whichever side brings which panel, both come in the same way: the one rise every panel's layer takes. */
    @Test fun thePanelsRiseAlike() {
        assertEquals(26f, panelRise(0f).value, 0f)
        assertEquals(13f, panelRise(0.5f).value, 0f)
        assertEquals(0f, panelRise(1f).value, 0f)
        // A hair past either end (a frame's rounding) is still the fraction the layer can draw.
        assertEquals(26f, panelRise(-0.001f).value, 0f)
        assertEquals(0f, panelRise(1.001f).value, 0f)
    }

    // ------------------------------------------------------------------ What's stored
    @Test fun offAsPrismComes() {
        assertEquals(PanelSwipes.OFF, AppSettings().behavior.panelSwipes)
    }

    @Test fun eachModeComesBackFromTheStore() = withStore { r ->
        assertEquals(PanelSwipes.OFF, r.storedWhen { true }.behavior.panelSwipes)
        for (mode in PanelSwipes.entries) {
            r.setPanelSwipes(mode)
            // The setter's write lands before the read (a backup asks the store itself).
            r.editNow { }
            assertEquals("$mode stored", mode, r.storedWhen { it.behavior.panelSwipes == mode }.behavior.panelSwipes)
            assertEquals("$mode in a backup", mode.name, r.forBackup(settings = true, accounts = false)["panel_swipes_sides"]?.removePrefix("s:"))
        }
    }

    /** The switch it was: on becomes the sides it had (the queue right, the lyrics left), off stays off. */
    @Test fun theOldSwitchKeepsItsSides() = withStore { r ->
        r.editNow { it[booleanPreferencesKey("panel_swipes")] = true }
        assertEquals(PanelSwipes.QUEUE_RIGHT, r.storedWhen { it.behavior.panelSwipes != PanelSwipes.OFF }.behavior.panelSwipes)
        // Picking a mode is what's read from then on; the old switch is let go with the write.
        r.setPanelSwipes(PanelSwipes.LYRICS_BOTH)
        assertEquals(PanelSwipes.LYRICS_BOTH, r.storedWhen { it.behavior.panelSwipes == PanelSwipes.LYRICS_BOTH }.behavior.panelSwipes)
        assertTrue("the old key went with the write", "panel_swipes" !in r.forBackup(settings = true, accounts = false))
        // Off, picked.
        r.setPanelSwipes(PanelSwipes.OFF)
        assertEquals(PanelSwipes.OFF, r.storedWhen { it.behavior.panelSwipes == PanelSwipes.OFF }.behavior.panelSwipes)
        // A mode years in is the mode, though an old switch says otherwise; a switch off with no sides is off.
        r.editNow {
            it[booleanPreferencesKey("panel_swipes")] = true
            it[stringPreferencesKey("panel_swipes_sides")] = PanelSwipes.QUEUE_LEFT.name
        }
        assertEquals(PanelSwipes.QUEUE_LEFT, r.storedWhen { it.behavior.panelSwipes == PanelSwipes.QUEUE_LEFT }.behavior.panelSwipes)
        r.editNow { it.remove(stringPreferencesKey("panel_swipes_sides")); it[booleanPreferencesKey("panel_swipes")] = false }
        assertEquals(PanelSwipes.OFF, r.storedWhen { it.behavior.panelSwipes == PanelSwipes.OFF }.behavior.panelSwipes)
    }

    /** Neither the sides nor the old switch is part of a Look; both travel in a backup. */
    @Test fun neitherSidesNorSwitchIsALookAndBothAreInABackup() = withStore { r ->
        val names = setOf("panel_swipes_sides", "panel_swipes")
        assertTrue(r.allKeyNames.containsAll(names))
        assertTrue(r.notLookNames.containsAll(names))
        assertTrue((r.lookKinds.keys intersect names).isEmpty())
        r.setPanelSwipes(PanelSwipes.QUEUE_BOTH)
        r.storedWhen { it.behavior.panelSwipes == PanelSwipes.QUEUE_BOTH }
        assertEquals("s:QUEUE_BOTH", r.forBackup(settings = true, accounts = false)["panel_swipes_sides"])
    }
}
