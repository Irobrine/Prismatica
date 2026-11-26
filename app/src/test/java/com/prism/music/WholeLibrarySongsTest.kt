package com.prism.music

import com.prism.music.data.model.Song
import com.prism.music.data.wholeLibrarySongs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The whole library: liked songs and the playlists picked under "From", then the phone's own local files — the same
 * list the Library's shuffle button, "Start playing shuffled" and the persistent card's shuffle all use.
 */
class WholeLibrarySongsTest {
    private fun song(id: String) = Song(id, "Song $id")
    private val local = Song("${Song.LOCAL_PREFIX}7", "A file", listOf(com.prism.music.data.model.ArtistRef("Someone")))

    @Test fun localFilesComeAfterTheChosenSongs() {
        val songs = wholeLibrarySongs(listOf(song("a"), song("b")), listOf(local))
        assertEquals(listOf("a", "b", local.id), songs.map { it.id })
    }

    @Test fun thePhoneHasNoneOnlyTheChosenSongs() {
        assertEquals(listOf("a", "b"), wholeLibrarySongs(listOf(song("a"), song("b")), emptyList()).map { it.id })
    }

    @Test fun aSongAlreadyChosenIsNotListedTwice() {
        val twice = wholeLibrarySongs(listOf(song("a")), listOf(song("a"), local))
        assertEquals(listOf("a", local.id), twice.map { it.id })
    }
}
