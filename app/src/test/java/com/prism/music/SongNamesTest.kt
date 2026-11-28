package com.prism.music

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.prism.music.data.db.toEntity
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.songsByIds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Names for lists that only hold ids (the suggested tags, the sleep scores): the songs table's, and — since a file on the
 * phone that was only ever analysed has no row there — the phone's own music list's, instead of showing "local:1234".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SongNamesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val made = lazy { AppContainer(RuntimeEnvironment.getApplication()) }
    private val c by made

    @After fun tearDown() = made.closeAfterTest()

    private fun file(id: String, title: String, artist: String) = com.prism.music.data.local.LocalMusic.Track(
        song = Song(id, title, artists = listOf(ArtistRef(artist))),
        uri = android.net.Uri.parse("content://media/external/audio/media/${id.removePrefix(Song.LOCAL_PREFIX)}"),
        albumId = 0, trackNumber = 0, year = 0, addedAt = 0, mime = "audio/mpeg", size = 1,
    )

    /**
     * The Sleep scores list draws a row's cover through the app's own lookup ([PlaylistCovers.art]): a file's row in the
     * songs table may have no thumbnail (it was written by a play), but the phone's scan has its album's picture.
     */
    @Test fun aFilesCoverComesFromThePhoneWhenItsRowHasNone() = runBlocking {
        val unplayed = file("${Song.LOCAL_PREFIX}9002", "A file on the phone", "Its artist")
        val cover = "content://media/external/audio/albumart/77"
        val track = unplayed.copy(song = unplayed.song.copy(thumbnail = cover))
        c.localMusic.putTracksForTests(listOf(track))
        // The row a play would leave: the same id, no cover on it.
        c.db.songs().upsertAll(listOf(unplayed.song.toEntity()))
        val listed = c.songsByIds(listOf(unplayed.song.id))[unplayed.song.id]!!
        assertEquals("the table's row has no cover", null, listed.thumbnail)
        assertEquals(cover, c.covers.art(listed))
    }

    @Test fun aFileNeverPlayedIsNamedFromThePhoneNotItsId() = runBlocking {
        val unplayed = file("${Song.LOCAL_PREFIX}9001", "A file on the phone", "Its artist")
        val played = Song("yt1", "A played song", artists = listOf(ArtistRef("Played artist")))
        c.db.songs().upsertAll(listOf(played.toEntity()))
        c.localMusic.putTracksForTests(listOf(unplayed))
        val names = c.songsByIds(listOf(unplayed.song.id, played.id, "nothing"))
        assertEquals("A file on the phone", names[unplayed.song.id]?.title)
        assertEquals("Its artist", names[unplayed.song.id]?.artistText)
        assertEquals("A played song", names[played.id]?.title)
        assertNull("an id nothing knows stays unnamed", names["nothing"])
        assertTrue("no id is ever its own name", names[unplayed.song.id]?.title != unplayed.song.id)
    }
}
