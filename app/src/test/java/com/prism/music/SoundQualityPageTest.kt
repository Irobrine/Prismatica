package com.prism.music

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.prism.music.data.db.toEntity
import com.prism.music.data.local.LocalLossless
import com.prism.music.data.model.Song
import com.prism.music.data.model.songCount
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.AudioQuality
import com.prism.music.data.prefs.NORMALIZE_TARGETS
import com.prism.music.data.prefs.ThemeMode
import com.prism.music.ui.Routes
import com.prism.music.ui.screens.SettingsIndex
import com.prism.music.ui.screens.TrimSilence
import com.prism.music.ui.screens.settings.SettingsMap
import com.prism.music.ui.screens.settings.SettingsPageId
import com.prism.music.ui.screens.settings.SettingsTags
import com.prism.music.ui.screens.settings.SettingsTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * 1.8.4, Settings → Sound & quality rebuilt with the kit (plan §2.3, the ledger's Sound & quality table): the groups
 * Quality and Sound within the budgets, a control for every stored setting the ledger gives the page (worked here, and
 * the store follows), Wi-Fi and mobile quality as one row (§2.5), "Measure songs now" (1.8.5: the scan on a set of the
 * listener's songs, a tap stopping it), Reset this page, the player's Equalizer button still landing on Sound, and the
 * page drawn in light, dark, Performance mode and at large text.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h4000dp-xhdpi", application = android.app.Application::class)
class SoundQualityPageTest : ListeningPageHarness(SettingsPageId.SOUND_QUALITY) {
    private fun t(id: Int) = tr(id)

    private val lossless = t(R.string.lossless_playback)
    private val allowFiles = t(R.string.allow_access_to_lossless_files)
    private val scanAgain = t(R.string.scan_for_lossless_files_again)
    private val streaming = t(R.string.sound_streaming_quality)
    private val wifi = t(R.string.sound_wifi)
    private val mobile = t(R.string.mobile_data)
    private val mobileOnly = t(R.string.streaming_quality_on_mobile_data)
    private val premium = t(R.string.premium_quality)
    private val video = t(R.string.music_video_quality)
    private val loudness = t(R.string.same_loudness_for_every_song)
    private val albums = t(R.string.albums_as_they_are)
    private val measureSongs = t(R.string.measure_songs_now)
    private val spatial = t(R.string.prism_spatial)
    private val intensity = t(R.string.intensity)
    private val equalizer = t(R.string.equalizer)
    private val skipSilence = t(R.string.skip_silence)

    private val signedIn: (AppSettings) -> AppSettings = { it.copy(cookie = "SAPISID=test") }

    private fun groupTitles() = compose.onAllNodes(hasTestTag(SettingsTags.GROUP)).fetchSemanticsNodes()
        .map { g -> g.children.first().config[SemanticsProperties.Text].first().text }

    @Test fun twoGroupsWithinTheBudgets() {
        show()
        assertBudgets("defaults")
        assertEquals(listOf(t(R.string.quality), t(R.string.sound)), groupTitles())
        // A fresh install: lossless on (so no quality to pick), not signed in; Normalize on.
        assertEquals(listOf(3, 6), groupSizes())
        assertEquals(0, changedRows())
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(lossless))
        assertEquals(SettingsTags.ROW_LINK, typeOf(allowFiles))
        assertEquals(SettingsTags.ROW_CHOICE, typeOf(video))
        assertEquals("1080p", valueOf(video))
        assertEquals(SettingsTags.ROW_PICK, typeOf(loudness))
        assertEquals(NORMALIZE_TARGETS.first { it.first == -14 }.second, valueOf(loudness))
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(albums))
        // 1.8.5: an action, so a link without a value line until it runs.
        assertEquals(SettingsTags.ROW_LINK, typeOf(measureSongs))
        assertEquals("", valueOf(measureSongs))
        assertEquals(SettingsTags.ROW_SWITCH, typeOf(spatial))
        assertEquals(SettingsTags.ROW_LINK, typeOf(equalizer))
        assertEquals(t(R.string.off), valueOf(equalizer))
        assertEquals(SettingsTags.ROW_CHOICE, typeOf(skipSilence))
        assertFalse("with lossless on there is no quality to pick", hasRow(streaming) || hasRow(mobileOnly))
        assertFalse("Premium quality needs the account", hasRow(premium))
        assertFalse(hasRow(intensity))
        // Two groups: no jump chips.
        assertEquals(0, compose.onAllNodes(hasTestTag(SettingsTags.JUMP_CHIPS)).fetchSemanticsNodes().size)
    }

    @Test fun withEverythingOnItStillFits() {
        container.settings.setSpatial(true)
        until("spatial") { it.spatial }
        // Lossless with Data saver, signed in: lossless, the files row, mobile quality, Premium, video quality.
        show { signedIn(it).copy(dataSaver = true) }
        assertBudgets("lossless, Data saver, signed in, Spatial")
        assertEquals(listOf(5, 7), groupSizes())
        assertTrue(hasRow(mobileOnly) && hasRow(premium) && hasRow(intensity))
        // Without lossless: one row for both qualities.
        container.settings.setLossless(false)
        until("lossless off") { !it.lossless }
        assertEquals(listOf(4, 7), groupSizes())
        assertTrue(hasRow(streaming))
        assertFalse(hasRow(mobileOnly) || hasRow(allowFiles) || hasRow(scanAgain))
    }

    /** Principle 8: every stored setting the ledger gives Sound & quality has its control here, and the control stores it. */
    @Test fun everyStoredSettingHasItsControl() {
        show(over = signedIn)
        val d = AppSettings()

        // video_max_height
        choose(video, "720p")
        until("video_max_height") { it.videoMaxHeight == 720 }
        // premium_stream
        tap(premium)
        until("premium_stream") { it.premiumStream }

        // lossless: off, the quality can be picked.
        tap(lossless)
        until("lossless") { !it.lossless && !it.losslessLocal }
        // audio_quality, cellular_quality: one row (§2.5), its sheet holds both; each change is stored at once.
        assertEquals(SettingsTags.ROW_PICK, typeOf(streaming))
        assertEquals(tr(R.string.sound_streaming_quality_value,AudioQuality.HIGH.label, AudioQuality.HIGH.label), valueOf(streaming))
        tap(streaming)
        assertEquals(1, sheets())
        choose(wifi, AudioQuality.LOW.label)
        until("audio_quality") { it.audioQuality == AudioQuality.LOW && it.cellularQuality == AudioQuality.HIGH }
        choose(mobile, AudioQuality.NORMAL.label)
        until("cellular_quality") { it.audioQuality == AudioQuality.LOW && it.cellularQuality == AudioQuality.NORMAL }
        swipeSheetAway()
        assertEquals(tr(R.string.sound_streaming_quality_value,AudioQuality.LOW.label, AudioQuality.NORMAL.label), valueOf(streaming))
        assertEquals("swiping the sheet away keeps what was picked", AudioQuality.LOW, stored.audioQuality)

        // lossless_local: with the files permission, the switch turns both on, and the row to scan again is there.
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(LocalLossless.permission)
        tap(lossless)
        until("lossless_local") { it.lossless && it.losslessLocal }
        assertTrue(hasRow(scanAgain))
        assertFalse(hasRow(allowFiles) || hasRow(streaming))
        // With lossless, mobile data's quality is only asked for with Data saver: Normal or Low.
        over = { signedIn(it).copy(dataSaver = true) }
        compose.waitForIdle()
        choose(mobileOnly, AudioQuality.LOW.label)
        until("cellular_quality with Data saver") { it.cellularQuality == AudioQuality.LOW }

        // normalize, normalize_target: "Same loudness for every song", Off or a level, in its pick sheet.
        tap(loudness)
        assertEquals(1 + NORMALIZE_TARGETS.size, compose.onAllNodes(hasTestTag(SettingsTags.SHEET_OPTION)).fetchSemanticsNodes().size)
        swipeSheetAway()
        pick(loudness, t(R.string.off))
        until("normalize") { !it.normalize }
        assertEquals(t(R.string.off), valueOf(loudness))
        assertFalse("Albums as they are only shows with it on", hasRow(albums))
        assertFalse("and so does measuring songs ahead of time", hasRow(measureSongs))
        pick(loudness, NORMALIZE_TARGETS.first { it.first == -8 }.second)
        until("normalize_target") { it.normalize && it.sound.normalizeTarget == -8 }
        // normalize_albums
        tap(albums)
        until("normalize_albums") { it.sound.normalizeAlbums }
        // soft_spatial, soft_spatial_amount
        tap(spatial)
        until("soft_spatial") { it.spatial }
        slide(intensity, 0.5f)
        until("soft_spatial_amount") { abs(it.spatialAmount - 0.5f) < 0.01f }
        assertEquals("50%", valueOf(intensity))
        // skip_silence, skip_silence_episodes: one choice.
        choose(skipSilence, TrimSilence.SONGS.label)
        until("skip_silence") { it.skipSilence && !it.sound.skipSilenceEpisodes }
        choose(skipSilence, TrimSilence.PODCASTS.label)
        until("skip_silence_episodes") { !it.skipSilence && it.sound.skipSilenceEpisodes }

        assertBudgets("everything changed")
        assertEquals(listOf(5, 7), groupSizes())

        // Reset this page: what it lists goes back; lossless stays on (it is how Prism comes) with its files.
        val lines = resetLines(confirm = true)
        assertTrue(lines.joinToString("\n"), lines.size >= 8)
        until("the reset") {
            it.videoMaxHeight == d.videoMaxHeight && it.premiumStream == d.premiumStream && it.audioQuality == d.audioQuality && it.cellularQuality == d.cellularQuality &&
                it.normalize == d.normalize && it.sound == d.sound && it.spatial == d.spatial && it.spatialAmount == d.spatialAmount && it.skipSilence == d.skipSilence
        }
        assertTrue(stored.lossless)
        assertEquals(0, changedRows())
    }

    /**
     * 1.1.3: "Measure songs now" walks a set of the listener's songs through the whole-song scan, one at a time. A tap
     * opens the sheet of the four sets, each with how many songs of it are left; a pick starts the walk and the sheet goes;
     * while it runs the row says how far it is, and a tap stops it rather than opening the sheet — keeping its count, so
     * the tap after that carries on from it instead of starting the set over.
     */
    @Test fun measureSongsWalksASetAndStops() {
        // One of the listener's own songs, a file on the phone (its scan needs no network, so its walk is over at once): a
        // phone's liked songs are never empty, and a test's database starts as one.
        val own = Song("${Song.LOCAL_PREFIX}9001", "Own song")
        runBlocking { container.db.songs().upsertAll(listOf(own.toEntity().copy(liked = true, likedAt = 1L))) }
        show()
        // The liked set is the database's own list, kept by a flow: let it land before the sheet counts its songs.
        compose.waitUntil(5_000) { container.library.likedIds.value == setOf(own.id) }
        // While a walk runs the row says how far it is (as the scan does on a smart playlist), and a tap stops it instead.
        container.measureSongs.progress.value = 2 to 5
        container.measureSongs.running.value = true
        compose.waitForIdle()
        assertEquals(tr(R.string.measuring_n_of_n, 2, 5), valueOf(measureSongs))
        tap(measureSongs)
        assertEquals("tapping the row while it runs stops the walk", 0, sheets())
        container.measureSongs.running.value = false
        compose.waitForIdle()
        assertEquals("a stopped walk keeps its count for the row to carry on from", tr(R.string.measure_songs_stopped_at, 2, 5), valueOf(measureSongs))
        // And the tap after that carries on with it (the walk itself is MeasureSongsTest's; here its empty set means done).
        tap(measureSongs)
        assertEquals("not the sheet", 0, sheets())
        compose.waitUntil(5_000) { valueOf(measureSongs) == "" }

        tap(measureSongs)
        assertEquals(1, sheets())
        // The sets Prism has to hand say their counts at once; the sets with nothing left can't be picked.
        assertEquals(songCount(0), valueOf(t(R.string.downloaded)))
        assertEquals(songCount(0), valueOf(t(R.string.on_this_phone)))
        assertEquals(songCount(1), valueOf(t(R.string.liked_songs)))
        compose.onNode(rowIs(t(R.string.downloaded))).assertIsNotEnabled()
        // The liked set is picked, and the walk starts on its one song, measured as itself.
        tap(t(R.string.liked_songs))
        compose.waitUntil(5_000) { sheets() == 0 }
        // (The walk itself — one song at a time, its counts, stopping it — is MeasureSongsTest's: a song on the phone is
        // scanned in less time than a frame here, so the row's own "x of y" is what this test works out above.)
    }

    /** A song measured with the current scan is left out of the count (and so of the walk): nothing to do is said as 0. */
    @Test fun alreadyMeasuredSongsAreCountedOut() {
        val id = "${Song.LOCAL_PREFIX}9002"
        runBlocking { container.db.songs().upsertAll(listOf(Song(id, "Measured already").toEntity().copy(liked = true, likedAt = 1L))) }
        RuntimeEnvironment.getApplication().getSharedPreferences("song_loudness_version", android.content.Context.MODE_PRIVATE)
            .edit().putInt(id, com.prism.music.playback.MixTempo.LUFS_VERSION).commit()
        show()
        compose.waitUntil(5_000) { container.library.likedIds.value == setOf(id) }
        assertEquals("measured, so it has no work left", 0, container.measureSongs.pending(listOf(id)).size)
        tap(measureSongs)
        assertEquals(songCount(0), valueOf(t(R.string.liked_songs)))
        compose.onNode(rowIs(t(R.string.liked_songs))).assertIsNotEnabled()
    }

    /**
     * A song a walk walked but couldn't measure (a stream that can't play, say) is said in the row and the sheet, with its
     * title; it can be kept from being scanned — which takes it out of the count — or tried again, and "Scan again" puts
     * a kept song back in the work.
     */
    @Test fun aSongThatCantBeMeasuredIsSaidAndCanBeKeptFromBeingScanned() {
        val id = "${Song.LOCAL_PREFIX}9003"
        runBlocking { container.db.songs().upsertAll(listOf(Song(id, "Own song").toEntity().copy(liked = true, likedAt = 1L))) }
        show()
        compose.waitUntil(5_000) { container.library.likedIds.value == setOf(id) }
        container.measureSongs.couldnt.value = listOf(id to id)
        compose.waitForIdle()
        assertEquals("the row says it couldn't measure it", tr(R.string.measure_songs_couldnt_measure, songCount(1)), valueOf(measureSongs))
        tap(measureSongs)
        assertEquals(1, sheets())
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Own song")).fetchSemanticsNodes().isNotEmpty() }
        // Kept from being scanned: out of the work and the report, and the sheet offers to scan it again.
        compose.onNodeWithText(tr(R.string.measure_songs_dont_scan)).performClick()
        compose.waitUntil(5_000) { container.measureSongs.pending(listOf(id)).isEmpty() }
        assertTrue("kept from being scanned", container.mixTempo.skipped(id))
        assertNull(container.measureSongs.couldnt.value)
        assertEquals("", valueOf(measureSongs))
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(tr(R.string.measure_songs_not_scanned, songCount(1)))).fetchSemanticsNodes().isNotEmpty()
        }
        // Let back: the work returns.
        compose.onNodeWithText(tr(R.string.measure_songs_scan_again)).performClick()
        compose.waitUntil(5_000) { container.measureSongs.pending(listOf(id)).size == 1 }
        assertFalse(container.mixTempo.skipped(id))
    }

    /** eq: the Equalizer is its own screen; the row opens it and says whether it's on. */
    @Test fun theEqualizerRowOpensTheEqualizer() {
        show()
        assertEquals("page", navController!!.currentDestination?.route)
        tap(equalizer)
        compose.waitUntil(5_000) { navController!!.currentDestination?.route == Routes.EQ }
    }

    /** The player's Equalizer button opens this page at Sound, and old links to Playback & sound's two groups land here. */
    @Test fun oldLinksLandOnTheirGroups() {
        val quality = t(R.string.quality)
        val sound = t(R.string.sound)
        assertEquals(SettingsTarget(SettingsPageId.SOUND_QUALITY, sound), SettingsMap.resolve("sound", sound))
        assertEquals(SettingsTarget(SettingsPageId.SOUND_QUALITY, quality), SettingsMap.resolve("sound", quality))
        assertEquals(SettingsTarget(SettingsPageId.SOUND_QUALITY, sound), SettingsMap.resolve(SettingsPageId.SOUND_QUALITY.key, sound))
        val call = "Routes.settings(com.prism.music.ui.screens.settings.SettingsPageId.SOUND_QUALITY.key, com.prism.music.tr(com.prism.music.R.string.sound))"
        assertTrue("NowPlaying's Equalizer button", call in File("src/main/java/com/prism/music/ui/player/NowPlaying.kt").readText())
        show()
        assertEquals(listOf(quality, sound), groupTitles())
        // Every line of the page's search names one of the two.
        val lost = SettingsIndex.entries.filter { it.page == SettingsPageId.SOUND_QUALITY && !it.isPage && it.group !in setOf(quality, sound) }
        assertTrue("search lines in groups the page doesn't have: ${lost.map { it.group + " › " + it.title }}", lost.isEmpty())
        // The merged row is found by what its two halves were called.
        assertTrue(SettingsIndex.search("streaming quality on wi-fi").any { it.title == streaming })
        assertTrue(SettingsIndex.search("streaming quality on mobile data").any { it.title == streaming || it.title == mobileOnly })
        // Music video quality stays here (ledger (c)4).
        assertEquals(quality, SettingsIndex.entries.single { it.title == video }.group)
    }

    @Test fun atLargeTextNothingIsCutOff() {
        container.settings.setSpatial(true)
        until("spatial") { it.spatial }
        show(fontScale = 1.5f) { signedIn(it).copy(dataSaver = true) }
        assertBudgets("large text")
        assertNoTextCutOff("font scale 1.5")
        // And with a quality to pick.
        container.settings.setLossless(false)
        until("lossless off") { !it.lossless }
        assertNoTextCutOff("font scale 1.5, lossless off")
    }

    private fun drawn(name: String, glass: Boolean, over: (AppSettings) -> AppSettings) {
        show(glass = glass, over = over)
        val file = save("sound-quality", name)
        assertTrue(file.path, file.length() > 10_000)
        assertBudgets(name)
        assertNoTextCutOff(name)
    }

    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi") fun drawnLight() = drawn("sound-quality-light", true) { it.copy(themeMode = ThemeMode.LIGHT) }
    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi") fun drawnDark() = drawn("sound-quality-dark", true) { it.copy(themeMode = ThemeMode.DARK) }
    @Test @Config(qualifiers = "w360dp-h1200dp-xxhdpi") fun drawnPerformance() = drawn("sound-quality-performance", false) { it.copy(themeMode = ThemeMode.DARK, performanceMode = true) }

    /** Everything showing: no lossless (a quality to pick), signed in, Spatial on. */
    @Test @Config(qualifiers = "w360dp-h1400dp-xxhdpi") fun drawnWithEverythingOn() =
        drawn("sound-quality-dark-everything-on", true) { signedIn(it).copy(themeMode = ThemeMode.DARK, lossless = false, spatial = true, premiumStream = true, audioQuality = AudioQuality.NORMAL) }
}
