package com.prism.music

import android.content.Context
import com.prism.music.data.SongMetadata
import com.prism.music.data.SongVersions
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.ui.screens.sortUploadedSongs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Ordering by a song's names (1.8.3): a title, artist or album edited on this phone sorts where the new name goes, not
 * where the file's own would. [SongMetadata.revision] changes with every edit, so a list kept from before is sorted again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MetadataSortTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("song_metadata", Context.MODE_PRIVATE)
    private lateinit var store: SongMetadata

    private fun song(id: String, title: String, artist: String, album: String? = null) =
        Song(id, title, listOf(ArtistRef(artist)), album?.let { AlbumRef(it) })

    @Before fun setUp() {
        prefs.edit().clear().commit()
        SongVersions.shared = null
        store = SongMetadata(context)
    }

    @After fun reset() {
        prefs.edit().clear().commit()
        SongMetadata.shared = null
        SongVersions.shared = null
    }

    @Test fun aRenamedArtistSortsWhereTheNewNameGoes() {
        val a = song("a", "A", "a")
        val b = song("b", "B", "b")
        val c = song("c", "C", "c")
        val songs = listOf(a, b, c)

        assertEquals(listOf("a", "b", "c"), sortUploadedSongs(songs, "ARTIST").map { it.id })

        store.set(listOf(a.id), artist = "d")
        assertEquals(listOf("b", "c", "a"), sortUploadedSongs(songs, "ARTIST").map { it.id })
    }

    @Test fun aRenamedTitleAndAlbumSortByTheirNewNames() {
        val apple = song("apple", "Apple", "Band", "First")
        val zebra = song("zebra", "Zebra", "Band", "Second")
        val songs = listOf(apple, zebra)

        store.set(listOf(apple.id), title = "Zucchini", album = "Third")
        assertEquals(listOf("zebra", "apple"), sortUploadedSongs(songs, "TITLE").map { it.id })
        assertEquals(listOf("zebra", "apple"), sortUploadedSongs(songs, "ALBUM").map { it.id })

        store.clear(listOf(apple.id))
        assertEquals(listOf("apple", "zebra"), sortUploadedSongs(songs, "TITLE").map { it.id })
    }

    @Test fun everyEditAsksListsSortedByWhatSongsShowToBeSortedAgain() {
        val before = store.revision
        store.set(listOf("a"), title = "Another")
        assertTrue("a save bumps the revision", store.revision > before)
        val saved = store.revision
        store.clear(listOf("a"))
        assertTrue("a reset bumps it too", store.revision > saved)
    }
}
