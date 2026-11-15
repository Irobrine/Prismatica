package com.prism.music

import android.content.Context
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.core.app.ApplicationProvider
import com.prism.music.data.SnoozeDuration
import com.prism.music.data.SnoozeStore
import com.prism.music.data.SnoozedTrack
import com.prism.music.data.foryou.RadioTopUp
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.prefs.PlayerButton
import com.prism.music.data.prefs.SettingsRepository
import com.prism.music.ui.Routes
import com.prism.music.ui.components.SelectionPlan
import com.prism.music.ui.player.SnoozeDefaultDays
import com.prism.music.ui.player.SnoozeList
import com.prism.music.ui.player.snoozeTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Snooze (1.8.5): a track kept out of the queue Prism builds by itself, and only that. Manual play, Add to the queue and
 * Play next keep it; its days run out on their own; the list edits one track's days and lets it back in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SnoozeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 1_700_000_000_000L
    private val day = SnoozeDuration.DAY_MS

    private val a = Song("a", "Alpha", listOf(ArtistRef("Artist A", "artistA")), AlbumRef("Album A", "albumA"))
    private val b = Song("b", "Bravo", listOf(ArtistRef("Artist B")))
    private val episode = Song("rss:1", "An episode", isEpisode = true)

    private fun store() = SnoozeStore(context) { now }

    private fun clearPrefs() {
        context.getSharedPreferences("snoozed_tracks", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Before fun startFresh() = clearPrefs()

    @After fun endClean() = clearPrefs()

    // ---------------------------------------------------------------- the store

    @Test fun snoozedTracksLeaveAutomaticQueuesButKeepWhatWasAskedFor() {
        val s = store()
        s.snooze(listOf(a), 30)
        assertEquals("the lists Prism plays by itself leave it out", listOf(b), s.allowed(listOf(a, b)))
        assertEquals("the song a queue starts on is the listener's", listOf(a, b), s.allowed(listOf(a, b), keep = a))
        assertTrue(s.isSnoozed(a))
        assertFalse(s.isSnoozed(b))
        assertEquals(30, SnoozeDuration.daysLeft(s.until(a.id), now))
    }

    @Test fun theDaysRunOutAndTheRecordIsSweptUp() {
        val s = store()
        s.snooze(listOf(a), 30)
        now += 29 * day
        assertTrue(s.isSnoozed(a))
        now += 2 * day
        assertFalse("31 days on, its days are up", s.isSnoozed(a))
        assertEquals("it may be added by itself again at once", listOf(a, b), s.allowed(listOf(a, b)))
        s.prune()
        assertTrue("the record is gone", s.tracks.value.isEmpty())
        assertTrue("and stays gone after a restart", store().tracks.value.isEmpty())
    }

    @Test fun aSnoozeSurvivesARestart() {
        store().snooze(listOf(a), 30)
        val afterRestart = store()
        assertTrue(afterRestart.isSnoozed(a))
        assertEquals(30, SnoozeDuration.daysLeft(afterRestart.until(a.id), now))
        assertEquals("Album A", afterRestart.running().single().song.album?.title)
    }

    @Test fun snoozingAgainMovesItsDaysAndNeverASecondRecord() {
        val s = store()
        s.snooze(listOf(a), 30)
        val first = s.until(a.id)
        now += 5 * day
        s.snooze(listOf(a), 10)
        assertEquals(1, s.tracks.value.size)
        assertNotEquals(first, s.until(a.id))
        assertEquals(10, SnoozeDuration.daysLeft(s.until(a.id), now))
    }

    @Test fun unsnoozingAndTheButtonsToggleRestoreItAtOnce() {
        val s = store()
        s.toggle(a, 30)
        assertTrue(s.isSnoozed(a))
        s.toggle(a, 30)
        assertFalse("a second tap lets it back in", s.isSnoozed(a))
        s.snooze(listOf(a), 30)
        s.unsnooze(a.id)
        assertFalse(s.isSnoozed(a))
        assertTrue(s.allowed(listOf(a)).contains(a))
    }

    @Test fun editingItsDaysMovesItsExpiry() {
        val s = store()
        s.snooze(listOf(a), 30)
        now += 10 * day
        s.setDays(a.id, 5)
        assertEquals(5, SnoozeDuration.daysLeft(s.until(a.id), now))
        s.setDays(a.id, 90)
        assertEquals(90, SnoozeDuration.daysLeft(s.until(a.id), now))
        assertEquals("one record, newest first", listOf(a.id), s.running().map { it.song.id })
    }

    @Test fun aNumberThatIsNotOneIsRefused() {
        assertNull(SnoozeDuration.parse("abc"))
        assertNull(SnoozeDuration.parse(""))
        assertNull(SnoozeDuration.parse("0"))
        assertNull(SnoozeDuration.parse("-3"))
        assertNull(SnoozeDuration.parse("3651"))
        assertEquals(30, SnoozeDuration.parse(" 30 "))
        assertEquals(3650, SnoozeDuration.parse("3650"))
        assertEquals(SnoozeDuration.DEFAULT_DAYS, SnoozeDuration.DEFAULT_DAYS)
    }

    // ---------------------------------------------------------------- settings and buttons

    private fun withSettings(block: suspend (SettingsRepository) -> Unit): Unit = runBlocking {
        val r = SettingsRepository(context, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        r.editNow { it.clear() }
        try { block(r) } finally { r.editNow { it.clear() } }
    }

    @Test fun theDefaultDaysAreKeptAndUsed() {
        val s = store()
        assertEquals("30 days unless changed", 30, s.defaultDays.value)
        s.snooze(listOf(a))
        assertEquals(30, SnoozeDuration.daysLeft(s.until(a.id), now))
        s.setDefaultDays(14)
        assertEquals(14, s.defaultDays.value)
        assertEquals("kept across a restart", 14, store().defaultDays.value)
        s.setDefaultDays(0)
        assertEquals(1, s.defaultDays.value)
        s.setDefaultDays(99_999)
        assertEquals(SnoozeDuration.MAX_DAYS, s.defaultDays.value)
        s.unsnooze(a.id)
        s.snooze(listOf(a))
        assertEquals("a new snooze takes the default", SnoozeDuration.MAX_DAYS, SnoozeDuration.daysLeft(s.until(a.id), now))
    }

    @Test fun snoozeJoinsThePlayerButtons() = withSettings { r ->
        assertTrue(PlayerButton.SNOOZE in PlayerButton.entries)
        assertFalse("the row starts as 1.7 had it", PlayerButton.SNOOZE in PlayerButton.DEFAULT)
        r.setPlayerButtons(listOf(PlayerButton.LYRICS, PlayerButton.SNOOZE, PlayerButton.QUEUE))
        val s = withTimeout(5_000) { r.stored.first { it.playerButtons.getOrNull(1) == PlayerButton.SNOOZE } }
        assertEquals(listOf(PlayerButton.LYRICS, PlayerButton.SNOOZE, PlayerButton.QUEUE), s.playerButtons)
        assertEquals("its label is the button's name", tr(R.string.snooze), PlayerButton.SNOOZE.label)
    }

    // ---------------------------------------------------------------- the queues that may pick it

    @Test fun autoplayLeavesASnoozedSuggestionOut() {
        val s = store()
        s.snooze(listOf(a), 30)
        val rules = RadioTopUp.Rules(
            inQueue = emptySet(), heardLately = emptySet(), turnedDown = { false }, allowed = { !s.isSnoozed(it) },
        )
        val picked = RadioTopUp.batch(emptyList(), listOf(a, b), songRadio = true, rules = rules)
        assertEquals(listOf(b.id), picked.map { it.id })
    }

    @Test fun selectManySnoozesTheSongsOnceAndNeverEpisodes() {
        val picked = listOf(a, a.copy(title = "Alpha (another upload)"), episode)
        assertEquals(listOf(a), SelectionPlan.snooze(picked))
    }

    @Test fun aSnoozedTrackOpensInItsAlbumElseItsArtist() {
        assertEquals(Routes.album("albumA"), snoozeTarget(a))
        assertEquals(Routes.artist("artistA"), snoozeTarget(a.copy(album = null)))
        assertNull("nothing to open: no album, no artist", snoozeTarget(b))
    }

    // ---------------------------------------------------------------- the list

    @get:Rule val compose = createComposeRule()

    private val record = SnoozedTrack(a, now + 30 * day, now)

    @Test fun theListShowsATracksNameAndDaysAndUnsnoozes() {
        var days: Pair<String, Int>? = null
        var unsnoozed: String? = null
        var opened: Song? = null
        compose.setContent {
            SnoozeList(listOf(record), now, null, { id, d -> days = id to d }, { unsnoozed = it }, { opened = it })
        }
        compose.onNodeWithText("Alpha").assertExists()
        compose.onNodeWithText("Artist A").assertExists()
        compose.onNodeWithText("30").assertExists()
        compose.onNodeWithTag("snoozeUnsnooze").performClick()
        assertEquals("a", unsnoozed)
        compose.onNodeWithTag("snoozeRow").performClick()
        assertEquals("a", opened?.id)
        compose.onNodeWithTag("snoozeDays", useUnmergedTree = true).performTextClearance()
        compose.onNodeWithTag("snoozeDays", useUnmergedTree = true).performTextInput("7")
        compose.onNodeWithTag("snoozeDays", useUnmergedTree = true).performImeAction()
        assertEquals("a" to 7, days)
    }

    @Test fun aNumberThatIsNotOneSaysSoAndGoesBackTo30() {
        var days: Pair<String, Int>? = null
        compose.setContent {
            SnoozeList(listOf(record), now, null, { id, d -> days = id to d }, {}, {})
        }
        compose.onNodeWithTag("snoozeDays", useUnmergedTree = true).performTextClearance()
        compose.onNodeWithTag("snoozeDays", useUnmergedTree = true).performTextInput("abc")
        compose.onNodeWithTag("snoozeDays", useUnmergedTree = true).assertTextContains("abc")
        compose.onNodeWithTag("snoozeDays", useUnmergedTree = true).performImeAction()
        compose.onNodeWithText(tr(R.string.invalid_number)).assertExists()
        compose.onNodeWithText("30").assertExists()
        assertEquals("a" to SnoozeDuration.DEFAULT_DAYS, days)
    }

    @Test fun theListSaysWhenNothingIsSnoozedAndMarksTheSongItOpenedAt() {
        compose.setContent {
            SnoozeList(emptyList(), now, null, { _, _ -> }, {}, {})
        }
        compose.onNodeWithText(tr(R.string.nothing_snoozed)).assertExists()
    }

    @Test fun theDefaultDaysFieldKeepsAValidNumberAndRefusesTheRest() {
        var saved: Int? = null
        compose.setContent {
            SnoozeDefaultDays(30) { saved = it }
        }
        compose.onNodeWithTag("snoozeDefaultDays", useUnmergedTree = true).performTextClearance()
        compose.onNodeWithTag("snoozeDefaultDays", useUnmergedTree = true).performTextInput("14")
        compose.onNodeWithTag("snoozeDefaultDays", useUnmergedTree = true).performImeAction()
        assertEquals(14, saved)
        compose.onNodeWithTag("snoozeDefaultDays", useUnmergedTree = true).performTextClearance()
        compose.onNodeWithTag("snoozeDefaultDays", useUnmergedTree = true).performTextInput("abc")
        compose.onNodeWithText(tr(R.string.invalid_number)).assertExists()
        compose.onNodeWithTag("snoozeDefaultDays", useUnmergedTree = true).performImeAction()
        assertEquals("refused, and 30 stored", 30, saved)
        compose.onNodeWithText("30").assertExists()
    }

    @Test fun holdingTheSnoozeGlyphOpensItsListAndATapDoesnt() {
        var taps = 0
        var holds = 0
        compose.setContent {
            com.prism.music.ui.player.BottomGlyph(
                com.prism.music.ui.theme.PrismIcons.Timer, "Snooze", false, { taps++ },
                onLongClick = { holds++ }, longPressLabel = tr(R.string.snoozed_tracks),
            )
        }
        compose.onNodeWithContentDescription("Snooze").performClick()
        assertEquals("a tap is the toggle", 1, taps)
        assertEquals(0, holds)
        compose.onNodeWithContentDescription("Snooze").performTouchInput { longClick() }
        assertEquals("holding it opens the list", 1, holds)
        assertEquals(1, taps)
    }

    @Test fun theSongTheListOpenedAtIsMarked() {
        val other = SnoozedTrack(b, now + 5 * day, now - 1)
        compose.setContent {
            SnoozeList(listOf(other, record), now, "a", { _, _ -> }, {}, {})
        }
        compose.onNode(hasTestTag("snoozeRow") and isSelected()).assertExists()
    }
}
