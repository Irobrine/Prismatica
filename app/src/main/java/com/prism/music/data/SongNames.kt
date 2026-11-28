package com.prism.music.data

import com.prism.music.AppContainer
import com.prism.music.data.model.Song

/**
 * Songs by id, for a list that only has ids (the sound analysis' rows, its suggested tags): the `songs` table first,
 * then the music files on the phone ([com.prism.music.data.local.LocalMusic]) — a file that was only ever analysed, never
 * played, liked or tagged, has no `songs` row, and its id ("local:1234") would otherwise stand in for its name. Then the
 * downloads, for one kept offline whose row is gone.
 */
suspend fun AppContainer.songsByIds(ids: List<String>): Map<String, Song> {
    val out = HashMap<String, Song>(ids.size)
    ids.distinct().chunked(500).flatMap { chunk -> runCatching { db.songs().getAll(chunk) }.getOrDefault(emptyList()) }
        .forEach { out[it.id] = it.toSong() }
    val missing = ids.filter { it !in out }
    if (missing.isEmpty()) return out
    for (id in missing) localMusic.track(id)?.song?.let { out[id] = it }
    val still = ids.filter { it !in out }.toSet()
    if (still.isNotEmpty()) runCatching { downloads.completedSongs() }.getOrDefault(emptyList())
        .filter { it.id in still }.forEach { out[it.id] = it }
    return out
}
