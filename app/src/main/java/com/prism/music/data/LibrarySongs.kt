package com.prism.music.data

import com.prism.music.AppContainer
import com.prism.music.data.model.Song

/**
 * Library → Songs as the phone keeps it: the liked songs, plus the playlists picked under "From" (1.8.3, #382), in
 * the order that tab shows them. No network: a playlist's copy is the session's or the one kept from an earlier start.
 *
 * Shared by the Library screen's own "shuffle everything" and by the persistent card's, which used to spell the same
 * thing out in the playback service.
 */
suspend fun AppContainer.librarySongs(from: SongsFrom): List<Song> {
    val own = prismLibrary.lists.value.associateBy { it.id }
    val lists = from.playlists.map { id -> own[id]?.songs ?: playlistSongs.cached(id).orEmpty() }
    return if (from.isDefault) library.likedShown.value else SongsFrom.merge(library.likedShown.value, lists, from)
}
