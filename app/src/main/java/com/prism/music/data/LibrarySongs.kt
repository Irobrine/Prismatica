package com.prism.music.data

import com.prism.music.AppContainer
import com.prism.music.data.model.Song

/**
 * The whole library as the phone keeps it: the liked songs, the playlists picked under "From" (1.8.3, #382), and the
 * listener's own files from Library → Local music, each once. No network: a playlist's copy is the session's or the one
 * kept from an earlier start. Used by the Library's "Shuffle the library" button, "Start playing shuffled", and the
 * persistent card's Library action, so all three mean the same library.
 */
suspend fun AppContainer.librarySongs(from: SongsFrom): List<Song> {
    val own = prismLibrary.lists.value.associateBy { it.id }
    val lists = from.playlists.map { id -> own[id]?.songs ?: playlistSongs.cached(id).orEmpty() }
    val chosen = if (from.isDefault) library.likedShown.value else SongsFrom.merge(library.likedShown.value, lists, from)
    // Local music is part of the library too (Library → Local music): the whole library is everything the phone has,
    // not only what YouTube knows. It comes after the chosen songs, and a song is never listed twice.
    return wholeLibrarySongs(chosen, localMusic.tracks.value.map { it.song })
}

/**
 * The whole library's songs: [chosen] (liked, and the playlists picked under "From") followed by the phone's own
 * [files], each once. Kept apart from [librarySongs] so the inclusion and de-duplication can be checked without a scan.
 */
internal fun wholeLibrarySongs(chosen: List<Song>, files: List<Song>): List<Song> =
    (chosen + files).distinctBy { it.id }
