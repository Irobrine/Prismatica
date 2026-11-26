package com.prism.music.data

import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.CollectionPage
import com.prism.music.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Library → Songs' "From" (1.8.3, G142, #382): liked songs and the songs of playlists the listener picks, in the
 * source order they choose, together and once each. Only a view: nothing is liked and nothing is written to the account.
 */
data class SongsFrom(val sources: List<Source> = listOf(Source.Liked)) {
    sealed interface Source {
        data object Liked : Source
        data class Playlist(val id: String) : Source
    }

    /** The old choice shape, still used by callers that only choose membership: Liked first when it is chosen. */
    constructor(liked: Boolean, playlists: List<String>) : this(buildList {
        if (liked) add(Source.Liked)
        playlists.distinct().forEach { add(Source.Playlist(it)) }
    })

    val liked get() = Source.Liked in sources
    val playlists get() = sources.filterIsInstance<Source.Playlist>().map { it.id }

    /** Liked songs only: Songs as it was before 1.8.3. */
    val isDefault get() = sources.size == 1 && sources.single() == Source.Liked

    fun encode(): String = VERSION + "|" + sources.joinToString(",") { source ->
        when (source) {
            Source.Liked -> LIKED
            is Source.Playlist -> PLAYLIST_PREFIX + source.id
        }
    }

    companion object {
        private const val VERSION = "v2"
        private const val LIKED = "liked"
        private const val PLAYLIST_PREFIX = "p:"
        const val KEY = "library:songs:from"

        fun decode(s: String?): SongsFrom {
            if (s.isNullOrBlank() || '|' !in s) return SongsFrom()
            val (a, b) = s.split('|', limit = 2)
            if (a == VERSION) {
                val sources = b.split(',').mapNotNull { token ->
                    val trimmed = token.trim()
                    when {
                        trimmed == LIKED -> Source.Liked
                        trimmed.startsWith(PLAYLIST_PREFIX) -> trimmed.removePrefix(PLAYLIST_PREFIX).trim()
                            .takeIf { it.isNotEmpty() }?.let { Source.Playlist(it) }
                        else -> null
                    }
                }.distinct()
                // Nothing chosen at all would be an empty tab with no way to tell why: liked songs then.
                return if (sources.isEmpty()) SongsFrom() else SongsFrom(sources)
            }
            val lists = b.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            // Legacy values are liked|id1,id2: their Liked source was always first.
            return if (a != LIKED && lists.isEmpty()) SongsFrom() else SongsFrom(a == LIKED, lists)
        }

        /**
         * Each source's songs as a block, in the listener's chosen source order and each playlist's own order; a song
         * in several shows once, where it first comes.
         */
        fun merge(liked: List<Song>, lists: List<List<Song>>, from: SongsFrom): List<Song> {
            val byId = from.playlists.zip(lists).toMap()
            val seen = HashSet<String>()
            val out = ArrayList<Song>()
            from.sources.forEach { source ->
                val songs = when (source) {
                    Source.Liked -> liked
                    is Source.Playlist -> byId[source.id].orEmpty()
                }
                songs.forEach { if (seen.add(it.id)) out += it }
            }
            return out
        }
    }
}

/**
 * A playlist's songs, whether they're all of them, and how many the playlist said it had ([count], so the next read can
 * see what changed). A read that stopped at a page that didn't come is shown as it is, but isn't kept.
 */
data class PlaylistRead(val songs: List<Song>, val complete: Boolean, val count: Int? = null)

/**
 * A playlist's songs, all of them (up to [MAX]), for views that put several playlists together. Kept for the session
 * ([cache] keeps a whole read between starts too, refreshed with what was added since): [refresh] forgets the session's
 * copy when it's picked again, so the kept one is refreshed rather than read whole.
 *
 * A copy already on the phone is shown at once and read again behind it ([scope]), so opening the view never waits on
 * the network; [version] goes up as the read lands, for the screen to take the newer songs, and a whole read is passed
 * to [onRead] — where a kept playlist's downloads are started from it, as opening its page does.
 */
class PlaylistSongs(
    private val load: suspend (id: String, cached: KeptPlaylist?) -> PlaylistRead,
    private val cache: PlaylistSongsCache? = null,
    private val scope: CoroutineScope? = null,
    private val onRead: (String, List<Song>) -> Unit = { _, _ -> },
) {
    private val kept = ConcurrentHashMap<String, List<Song>>()
    /** When each kept copy was read: a copy older than [SESSION_TTL_MS] is read again (a process may live for days). */
    private val at = ConcurrentHashMap<String, Long>()
    /** Playlists being read again behind what's shown, so two views don't ask twice. */
    private val refreshing = ConcurrentHashMap.newKeySet<String>()
    private val _version = MutableStateFlow(0)

    /** Goes up as a playlist is read (or read again), so a screen showing it can take the newer songs. */
    val version: StateFlow<Int> = _version

    /**
     * The playlist's songs: from the session's kept copy (for [SESSION_TTL_MS]), or read (a whole playlist the first
     * time, then only the songs added since [cache]'s last read). Only a whole read is kept, in memory and on the phone:
     * one that stopped short shows what it has and is read again next time, so neither a lost page nor a read the
     * screen's leaving cancelled leaves it short for ever.
     */
    suspend fun songs(id: String): List<Song>? {
        kept[id]?.let { if (fresh(id)) return it }
        // Prism's own lists are read from the phone as they are now: never kept as if they were YouTube's.
        val disk = cache?.takeUnless { PrismLibrary.isPrismList(id) }
        val cached = disk?.read(id)
        // What the phone already has shows at once: the list is read again behind it, and [version] brings the newer
        // songs when they're in (1.2.1: an 800-song playlist waited seconds for the network on every start).
        if (cached != null && scope != null) {
            kept[id] = cached.songs
            at[id] = System.currentTimeMillis()
            readBehind(id, disk, cached)
            return cached.songs
        }
        return read(id, disk, cached)
    }

    private suspend fun read(id: String, disk: PlaylistSongsCache?, cached: KeptPlaylist?): List<Song>? = try {
        load(id, cached).let { read ->
            if (read.complete) {
                kept[id] = read.songs
                at[id] = System.currentTimeMillis()
                disk?.write(id, read)
                _version.update { it + 1 }
                // A kept playlist's new songs start downloading from this read too, not only from its own check.
                runCatching { onRead(id, read.songs) }
            }
            read.songs
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Offline: a copy read earlier still beats nothing — this session's, or the one kept from an earlier start.
        cached?.songs ?: kept[id]
    }

    /** Reads [id] again in the background, updating [version] when it comes: the screen keeps showing what it has. */
    private fun readBehind(id: String, disk: PlaylistSongsCache?, cached: KeptPlaylist) {
        val scope = scope ?: return
        if (!refreshing.add(id)) return
        scope.launch(Dispatchers.IO) {
            try { read(id, disk, cached) } finally { refreshing.remove(id) }
        }
    }

    /**
     * The playlist's songs as the phone already has them (the session's copy, or the one kept between starts), without
     * asking YouTube. Null when none is kept.
     */
    suspend fun cached(id: String): List<Song>? {
        kept[id]?.let { return it }
        val disk = cache?.takeUnless { PrismLibrary.isPrismList(id) } ?: return null
        return disk.read(id)?.songs?.takeIf { it.isNotEmpty() }
    }

    /**
     * The playlist's songs already in memory this session (no disk, no network): what a screen can show in its very
     * first composition instead of drawing an empty list and composing again a frame later. Only a copy [songs] would
     * take without reading anything is returned, so a stale one still goes the way it always did.
     */
    fun peek(id: String): List<Song>? = kept[id]?.takeIf { fresh(id) }

    /** Whether [id]'s songs are kept whole (a read that stopped short isn't). */
    fun isComplete(id: String): Boolean = kept.containsKey(id)

    fun refresh(id: String) {
        kept.remove(id)
        at.remove(id)
    }

    /**
     * Every playlist is read again next time it's shown (the disk copies stay, so the read is still the short one):
     * what a library sync means, since the account's playlists may have changed.
     */
    fun refreshAll() {
        kept.clear()
        at.clear()
        _version.update { it + 1 }
    }

    /**
     * A playlist read elsewhere (the kept-download check reads it every hour): the same read serves this view, in
     * memory and on the phone, instead of a second request. Only a whole read belongs here.
     */
    suspend fun noteRead(id: String, songs: List<Song>, count: Int?) {
        val key = id.removePrefix("VL")
        if (songs.isEmpty() || PrismLibrary.isPrismList(key)) return
        kept[key] = songs
        at[key] = System.currentTimeMillis()
        cache?.write(key, PlaylistRead(songs, complete = true, count = count))
        _version.update { it + 1 }
    }

    private fun fresh(id: String): Boolean {
        val read = at[id] ?: return false
        return System.currentTimeMillis() - read < SESSION_TTL_MS
    }

    /** Signing out or switching accounts: no kept playlist of the old one stays. */
    fun clear() {
        kept.clear()
        at.clear()
        cache?.clearAll()
    }

    companion object {
        /** The most songs read of one playlist (as a queue takes). */
        const val MAX = 2000
        /** How long a session's copy is trusted: a process kept alive by the player must not show yesterday's list. */
        const val SESSION_TTL_MS = 30 * 60_000L
    }
}

/**
 * A whole playlist for Library → Songs' "From": [first] and then each page after it, up to [PlaylistSongs.MAX] songs.
 * A page that doesn't come is asked again ([PAGE_TRIES] times, [retryMs] more apart each time). Cancelling the read
 * (the screen was left) is passed on, never turned into a short list: the caller keeps nothing then, and reads it again.
 */
suspend fun readPlaylist(
    first: CollectionPage,
    page: suspend (String) -> YouTubeMusic.PlaylistChunk,
    retryMs: Long = PAGE_RETRY_MS,
): PlaylistRead {
    val songs = first.songs.toMutableList()
    var token = first.continuation
    while (token != null && songs.size < PlaylistSongs.MAX) {
        val chunk = pageWithRetries(page, token, retryMs)
            ?: return PlaylistRead(songs.distinctBy { it.id }, complete = false, count = first.trackCount)
        if (chunk.songs.isEmpty()) break
        songs += chunk.songs
        token = chunk.next
    }
    return PlaylistRead(songs.distinctBy { it.id }, complete = true, count = first.trackCount)
}

/**
 * A playlist page as YouTube lists it now ([first]) and what the phone read last time, [cached]. Only the songs added
 * since are read: its "Newest first" page, until a song that's already kept comes; the rest comes from the phone. A big
 * playlist isn't read page after page again. [readAll] reads the whole playlist when the page can't tell what changed:
 * songs removed or reordered, a "Newest first" page that doesn't come, or new songs not at either end.
 */
suspend fun refreshPlaylist(
    first: CollectionPage,
    cached: KeptPlaylist?,
    newestPage: suspend (String) -> YouTubeMusic.PlaylistChunk,
    readAll: suspend () -> PlaylistRead,
): PlaylistRead {
    if (cached == null || cached.songs.isEmpty()) return readAll()
    val count = first.trackCount
    // As many songs as last time, nothing new to read — unless the first page isn't the copy's own beginning any more
    // (songs swapped, or reordered, without the number changing). Fewer means songs went: read them all.
    if (count != null && cached.count != null) {
        if (count == cached.count) {
            val head = first.songs
            val sameHead = head.isNotEmpty() && cached.songs.size >= head.size &&
                head.indices.all { head[it].id == cached.songs[it].id }
            if (sameHead) return PlaylistRead(cached.songs, complete = true, count = count)
            return readAll()
        }
        if (count < cached.count) return readAll()
    }
    val ids = cached.songs.mapTo(HashSet()) { it.id }
    val start = first.newestContinuation ?: return readAll()
    val newest = ArrayList<Song>()
    var token: String? = start
    var anchor: Song? = null
    while (token != null) {
        val chunk = try {
            newestPage(token)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return readAll()
        }
        for (song in chunk.songs) {
            if (song.id in ids) { anchor = song; break }
            newest += song
        }
        if (anchor != null || chunk.next == null || newest.size > cached.songs.size) break
        token = chunk.next
    }
    if (anchor == null) return readAll()
    val atTop = cached.songs.first().id == anchor.id
    val atBottom = cached.songs.last().id == anchor.id
    if (!atTop && !atBottom) return readAll()
    val merged = if (atTop) {
        // Added to the top: YouTube's newest first is this playlist's own order for them.
        (newest + cached.songs).distinctBy { it.id }
    } else {
        // Added to the bottom: the playlist's own order has the oldest of them first.
        (cached.songs + newest.reversed()).distinctBy { it.id }
    }
    // A count that doesn't match means songs went too, or the order isn't the one new songs join: read them all.
    if (count != null && merged.size != count) return readAll()
    // No header count stays none: the next read mustn't compare its own size against it as if YouTube had said it.
    return PlaylistRead(merged, complete = true, count = count)
}

private suspend fun pageWithRetries(
    page: suspend (String) -> YouTubeMusic.PlaylistChunk,
    token: String,
    retryMs: Long,
): YouTubeMusic.PlaylistChunk? {
    for (attempt in 0 until PAGE_TRIES) {
        try {
            return page(token)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (attempt == PAGE_TRIES - 1) return null
            delay(retryMs * (attempt + 1))
        }
    }
    return null
}

private const val PAGE_TRIES = 3
private const val PAGE_RETRY_MS = 500L
