package com.prism.music.data

import com.prism.music.AppContainer
import com.prism.music.data.innertube.SearchRank
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The two "More from this…" buttons: they take songs from the artist of the one playing, or from its album, shuffle them
 * and put them right after it. A tap adds [MoreFrom.DEFAULT_COUNT]; holding the button lets the listener pick another
 * number.
 */
enum class MoreFrom(val artist: Boolean) {
    ARTIST(true), ALBUM(false);

    companion object {
        /** Songs a tap adds unless the listener picks another number (the button held). */
        const val DEFAULT_COUNT = 3

        /** What the number may be. */
        val COUNT_RANGE = 1..25

        fun clamp(count: Int): Int = count.coerceIn(COUNT_RANGE)
    }
}

/**
 * What one "More from this…" tap found: [songs] in a shuffled order (up to the number asked for) and whether the phone's
 * own library was all there was to pick from ([offline]: no network, so the answer says what's on the phone).
 */
data class MoreFromPick(val songs: List<Song>, val offline: Boolean)

/**
 * Up to [count] songs by [song]'s artist or from its album, shuffled, to go right after it.
 *
 * Online they come from YouTube Music: the artist's page and their own shuffle list (kept to this artist's songs only),
 * or the album's tracks. Offline, or when YouTube can't be reached, they come from the phone's library — Downloads and
 * Local files — and only those. When there aren't as many as were asked for, all there are come back (never an error).
 */
suspend fun AppContainer.moreFromSongs(song: Song, from: MoreFrom, count: Int): MoreFromPick = withContext(Dispatchers.IO) {
    val want = MoreFrom.clamp(count)
    val picked = mutableListOf<Song>()
    // The song itself, and the recording picked to play in its place (#328), never come back.
    val current = setOfNotNull(song.id, versions.of(song.id))
    val online = network.isOnline()
    if (online) {
        val found = try {
            onlineMoreFrom(song, from)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        picked += shuffledMoreFrom(found, want, exclude = current)
    }
    // What YouTube doesn't have (or couldn't be reached for), the phone still might: more of it, up to the number asked.
    if (picked.size < want) {
        val phone = phoneMoreFrom(song, from, downloads.completedSongs(), localMusic.tracks.value.map { it.song })
        picked += shuffledMoreFrom(phone, want - picked.size, exclude = picked.mapTo(mutableSetOf()) { it.id } + current)
    }
    MoreFromPick(picked, offline = !online)
}

/** The songs YouTube Music knows for [song]'s artist or album, before shuffling. Empty when it has none to offer. */
private suspend fun AppContainer.onlineMoreFrom(song: Song, from: MoreFrom): List<Song> {
    return when (from) {
        MoreFrom.ARTIST -> {
            // The song's own (first) artist: the page it opens names them. Another artist's link is no help: it would
            // fetch a catalogue this song isn't by.
            val ref = song.artists.firstOrNull() ?: return emptyList()
            val id = ref.id ?: ytm.artistIdByName(ref.name) ?: return emptyList()
            val page = ytm.artist(id)
            // The page's own songs, and its shuffle list (which can carry similar artists too: only this one's songs stay).
            val listed = page.shelves.flatMap { it.items }.filterIsInstance<SongItem>().map { it.song }
            val shuffle = page.shufflePlaylistId?.let { playlist ->
                try {
                    ytm.playlist(playlist).songs
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
            }.orEmpty()
            (listed + shuffle).filter { !it.isVideo && !it.isEpisode && !it.isLive && sharesArtist(it, song) }
        }
        MoreFrom.ALBUM -> {
            // A file's album is the phone's, not YouTube's: there's no page to ask for.
            if (song.isLocal) return emptyList()
            val id = song.album?.id?.takeIf { it.isNotBlank() } ?: return emptyList()
            ytm.album(id).songs.filter { !it.isEpisode && !it.isLive }
        }
    }
}

/** The phone's own songs for [from], from Downloads and Local files (both play without a network). */
internal fun phoneMoreFrom(song: Song, from: MoreFrom, downloads: List<Song>, local: List<Song>): List<Song> {
    val all = (downloads + local).asSequence().filter { it.id != song.id && !it.isEpisode && !it.isLive }
    return when (from) {
        MoreFrom.ARTIST -> all.filter { sharesArtist(it, song) }
        MoreFrom.ALBUM -> all.filter { sharesAlbum(it, song) }
    }.distinctBy { it.id }.toList()
}

/** [pool] in a new random order, [count] of them, leaving out [exclude] and any repeat. Fewer when the pool is smaller. */
internal fun shuffledMoreFrom(pool: List<Song>, count: Int, exclude: Set<String> = emptySet()): List<Song> {
    if (count <= 0) return emptyList()
    return pool.asSequence().filter { it.id !in exclude }.distinctBy { it.id }.toList().shuffled().take(count).toList()
}

/** Whether [candidate] is by any of [song]'s artists (by link when both have one, else by name). */
internal fun sharesArtist(candidate: Song, song: Song): Boolean {
    if (song.artists.isEmpty() || candidate.artists.isEmpty()) return false
    val ids = song.artists.mapNotNull { it.id }.toSet()
    val names = song.artists.map { SearchRank.norm(it.name) }.filter { it.isNotBlank() }.toSet()
    return candidate.artists.any { ref -> (ref.id != null && ref.id in ids) || SearchRank.norm(ref.name) in names }
}

/**
 * Whether [candidate] is from [song]'s album. Two links that are both known are compared; a file's album (which carries
 * only a name) matches a download of the same album when the names agree and the songs share an artist.
 */
internal fun sharesAlbum(candidate: Song, song: Song): Boolean {
    val mine = song.album ?: return false
    val theirs = candidate.album ?: return false
    if (mine.id != null && theirs.id != null) return mine.id == theirs.id
    val mineTitle = SearchRank.norm(mine.title)
    if (mineTitle.isBlank() || SearchRank.norm(theirs.title) != mineTitle) return false
    return sharesArtist(candidate, song)
}
