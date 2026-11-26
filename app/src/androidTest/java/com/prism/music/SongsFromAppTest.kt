package com.prism.music

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.SongsFrom
import com.prism.music.data.db.toEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Library → Songs → From (1.8.3, #382), in the app: picked playlists list their songs in the tab; their source order
 * is kept, including where Liked songs sits; nothing is liked.
 */
@RunWith(AndroidJUnit4::class)
class SongsFromAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val c get() = Fixture.container
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { c.player.stop() } }
        c.library.setSort(SongsFrom.KEY, null)
    }

    private fun has(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    private fun hasTag(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun openSongsFrom() {
        compose.tapTab("Library")
        // The tab chips scroll: back to the start, where Songs is.
        for (i in 0 until 8) {
            if (compose.onAllNodes(hasText("Songs") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()) break
            compose.onRoot().performTouchInput { swipeRight(startX = width * 0.15f, endX = width * 0.95f) }
            compose.waitForIdle()
        }
        compose.onAllNodes(hasText("Songs") and hasClickAction()).onFirst().performClick()
        compose.waitUntil(10_000) { hasTag("songsFrom") }
        compose.onAllNodes(hasTestTag("songsFrom")).onFirst().performClick()
        compose.waitUntil(5_000) { hasTag("songsFromSheet") }
    }

    @Test fun aPickedPlaylistsSongsShowUnderSongs() {
        Fixture.ready()
        c.library.setSort(SongsFrom.KEY, null)
        val songs = Fixture.songs(3)
        val id = Fixture.prismPlaylist("Road trip", songs)
        Fixture.launch()
        openSongsFrom()
        compose.clickWhenFound(hasTestTag("from:liked"))
        compose.clickWhenFound(hasTestTag("from:$id"))
        compose.waitForIdle()
        Thread.sleep(500)
        Fixture.screenshot("songs-from-sheet")
        compose.clickWhenFound(hasTestTag("fromDone"))

        compose.waitUntil(10_000) { has("Test song 1") && has("Test song 3") }
        compose.waitUntil(5_000) { has("1 playlist") && has("Original order") }
        Fixture.screenshot("songs-from")
        assertEquals(SongsFrom(false, listOf(id)), SongsFrom.decode(c.library.sortOf(SongsFrom.KEY)))
        assertEquals("nothing was liked", 0, c.library.likedShown.value.count { s -> songs.any { it.id == s.id } })
    }

    @Test fun likedCanMoveBelowPlaylistsAndTheSongsFollowThatOrder() {
        Fixture.ready()
        val songs = Fixture.songs(5)
        val liked = songs[4].copy(title = "Liked song")
        val roadSongs = songs.take(2).mapIndexed { i, song -> song.copy(title = "Road song ${i + 1}") }
        val gymSongs = songs.drop(2).take(2).mapIndexed { i, song -> song.copy(title = "Gym song ${i + 1}") }
        kotlinx.coroutines.runBlocking {
            c.db.songs().upsert(liked.toEntity().copy(liked = true, likedAt = System.currentTimeMillis()))
        }
        val road = Fixture.prismPlaylist("Road trip", roadSongs)
        val gym = Fixture.prismPlaylist("Gym", gymSongs)
        Fixture.launch()
        openSongsFrom()
        compose.clickWhenFound(hasTestTag("from:$road"))
        compose.clickWhenFound(hasTestTag("from:$gym"))

        val grip = hasContentDescription(tr(R.string.drag, tr(R.string.liked_songs))) and
            androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)
        compose.waitUntil(5_000) {
            compose.onAllNodes(grip, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.config
                ?.getOrNull(SemanticsActions.CustomActions)
                ?.any { it.label == tr(R.string.move_to_bottom) } == true
        }
        val actions = compose.onNode(grip, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        instrumentation.runOnMainSync { actions.single { it.label == tr(R.string.move_to_bottom) }.action() }
        fun top(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true)
            .fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top
        compose.waitUntil(5_000) {
            val roadTop = top("from:$road")
            val gymTop = top("from:$gym")
            val likedTop = top("from:liked")
            roadTop != null && gymTop != null && likedTop != null && roadTop < gymTop && gymTop < likedTop
        }
        compose.clickWhenFound(hasTestTag("fromDone"))

        val expected = SongsFrom(listOf(SongsFrom.Source.Playlist(road), SongsFrom.Source.Playlist(gym), SongsFrom.Source.Liked))
        compose.waitUntil(5_000) { c.library.sortOf(SongsFrom.KEY) == expected.encode() }
        assertEquals(expected, SongsFrom.decode(c.library.sortOf(SongsFrom.KEY)))
        val titles = listOf("Road song 1", "Road song 2", "Gym song 1", "Gym song 2", "Liked song")
        compose.waitUntil(10_000) { titles.all { compose.onAllNodes(hasText(it), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
        val tops = titles.map { title -> compose.onAllNodes(hasText(title), useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot.top }
        assertTrue("Songs follow Road trip, Gym, then Liked songs: $tops", tops.zipWithNext().all { (a, b) -> a < b })
    }
}
