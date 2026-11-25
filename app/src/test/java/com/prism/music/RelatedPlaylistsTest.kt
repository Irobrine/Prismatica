package com.prism.music

import com.prism.music.data.innertube.communityPlaylistsFrom
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.SongItem
import com.prism.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The playlists a song's "Playlists with this song" sheet lists: the community playlists among a search's answer, with
 * no mixes or radios (they have no page to open) and nothing twice.
 */
class RelatedPlaylistsTest {
    private fun playlist(id: String, mix: Boolean = false, title: String = "A playlist") =
        PlaylistItem(id, title, "Someone · 20 songs", null, isMix = mix)

    @Test fun onlyPlaylistsAreKept() {
        val found = communityPlaylistsFrom(
            listOf(
                playlist("PL1"),
                AlbumItem("MPRE1", "An album", "An artist", null, null),
                SongItem(Song("v1", "A song")),
                playlist("PL2"),
            ),
        )
        assertEquals(listOf("PL1", "PL2"), found.map { it.id })
    }

    @Test fun mixesAndRadiosAreLeftOut() {
        val found = communityPlaylistsFrom(listOf(playlist("mix", mix = true), playlist("RDAMVMabc"), playlist("PL1")))
        assertEquals(listOf("PL1"), found.map { it.id })
    }

    @Test fun theSamePlaylistTwiceIsListedOnce() {
        val found = communityPlaylistsFrom(listOf(playlist("PL1", title = "First"), playlist("PL1", title = "Again")))
        assertEquals(listOf("PL1"), found.map { it.id })
    }

    @Test fun theSheetTakesAtMostTheLimit() {
        val found = communityPlaylistsFrom((1..20).map { playlist("PL$it") }, limit = 12)
        assertEquals(12, found.size)
        assertEquals("PL1", found.first().id)
    }
}
