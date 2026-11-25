package com.prism.music.data

import com.prism.music.AppContainer
import com.prism.music.data.innertube.SearchRank
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
 * own library was all there was to pick from ([offline]: no network, or none that answered, so the answer says what's
 * on the phone).
 */
data class MoreFromPick(val songs: List<Song>, val offline: Boolean)

/**
 * How long a tap gives YouTube before the phone's own songs are all there is. Long enough for an artist's page and
 * their catalogue on a working network (about a second), short enough that a network with nowhere to go doesn't hold
 * the tap: its requests would sit on OkHttp's 15 s connect timeout, twice over.
 */
internal const val ONLINE_BUDGET_MS = 2_000L

/**
 * What YouTube offered for an artist or album, kept for a few seconds so a second tap picks from it without asking
 * again, and when it was last found unable to answer, so the taps after a timeout don't wait either.
 *
 * The pool is trusted only while YouTube was answering moments ago: Android can go on calling a dead network
 * "validated" for a long while (a Wi-Fi whose uplink is gone still reports internet), and a pool served then would put
 * stream-only songs in the queue that can't play. The backoff covers the taps after that.
 */
internal class MoreFromPools(
    private val ttlMs: Long = 15_000L,
    private val downMs: Long = 60_000L,
) {
    private class Pool(val songs: List<Song>, val at: Long)
    private val pools = java.util.concurrent.ConcurrentHashMap<String, Pool>()
    @Volatile private var downUntil = 0L

    /** The artist or album [song] is by, as the pools are kept: its link when it has one, else its name. */
    fun key(song: Song, from: MoreFrom): String = when (from) {
        MoreFrom.ARTIST -> "artist:" + (song.artists.firstOrNull()?.let { it.id ?: SearchRank.norm(it.name) }
            ?: SearchRank.norm(song.primaryArtist))
        MoreFrom.ALBUM -> "album:" + (song.album?.id ?: SearchRank.norm(song.album?.title.orEmpty()))
    }

    fun get(key: String, now: Long): List<Song>? = pools[key]?.takeIf { now - it.at < ttlMs }?.songs

    fun put(key: String, songs: List<Song>, now: Long) {
        // A handful of artists is all a session goes through; the map is cleared rather than grown without end.
        if (pools.size > 32) pools.clear()
        pools[key] = Pool(songs, now)
    }

    /** YouTube was found unable to answer now: the taps until [downMs] from now go to the phone at once. */
    fun down(now: Long) { downUntil = now + downMs }

    /** The phone is offline (or back from it): the next tap asks again rather than keeping to a failure it saw before. */
    fun up() { downUntil = 0 }

    fun isDown(now: Long): Boolean = now < downUntil
}

private val moreFromPools = MoreFromPools()

/**
 * [block]'s answer, or null when it takes longer than [budgetMs]. [block] runs on this scope, not the caller's, and an
 * OkHttp call can't be interrupted: one that outlasts the budget is left to finish on its own, its answer dropped,
 * rather than waited for.
 */
internal suspend fun <T> CoroutineScope.withinOrNull(budgetMs: Long, block: suspend () -> T): T? {
    val job = async { block() }
    val out = try {
        withTimeoutOrNull(budgetMs) { job.await() }
    } catch (e: Exception) {
        job.cancel()
        throw e
    }
    if (out == null) job.cancel()
    return out
}

/**
 * Up to [count] songs by [song]'s artist or from its album, shuffled, to go right after it.
 *
 * Online they come from YouTube Music: the artist's catalogue (the list behind their page's Top songs, keeping only
 * their songs) or the album's tracks, with about a third of the handful from the phone's own copies even then.
 * Offline, or when YouTube can't be reached, they come from the phone's library — Downloads and Local files — and only
 * those. When there aren't as many as were asked for, all there are come back (never an error). A network that can't
 * answer gets [ONLINE_BUDGET_MS]: a tap can't wait on a connection that has nowhere to go.
 */
suspend fun AppContainer.moreFromSongs(song: Song, from: MoreFrom, count: Int): MoreFromPick = withContext(Dispatchers.IO) {
    val want = MoreFrom.clamp(count)
    // The song itself, and the recording picked to play in its place (#328), never come back.
    val current = setOfNotNull(song.id, versions.of(song.id))
    // Whether YouTube answered at all: one that doesn't (a network with nowhere to go, a request that failed) leaves
    // the phone's songs as the only ones there were, and the toast says so.
    var onlineAnswered = false
    // The app's own offline signal, the one its lists and playback go by. Android's capability flags can't be trusted
    // here: a Wi-Fi left on with no uplink goes on reporting a validated internet, so a dead network is found out by
    // the look-up itself running out of its budget.
    val online = network.online.value
    if (!online) moreFromPools.up()
    val key = moreFromPools.key(song, from)
    val now = System.currentTimeMillis()
    var pool = if (online) moreFromPools.get(key, now) else null
    if (pool == null && online && !moreFromPools.isDown(now)) {
        val found = try {
            scope.withinOrNull(ONLINE_BUDGET_MS) { onlineMoreFrom(song, from) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (found != null) {
            moreFromPools.put(key, found, System.currentTimeMillis())
            pool = found
        } else {
            // A network that didn't answer is remembered, so the next tap goes straight to the phone.
            moreFromPools.down(System.currentTimeMillis())
        }
    }
    onlineAnswered = pool != null
    val phone = phoneMoreFrom(song, from, downloads.completedSongs(), localMusic.tracks.value.map { it.song })
    val picked = mixMoreFrom(pool, phone, want, exclude = current)
    MoreFromPick(picked, offline = !onlineAnswered)
}

/** The songs YouTube Music knows for [song]'s artist or album, before shuffling. Empty when it has none to offer. */
internal suspend fun AppContainer.onlineMoreFrom(song: Song, from: MoreFrom): List<Song> {
    return when (from) {
        MoreFrom.ARTIST -> {
            // The song's own (first) artist: the page it opens names them. Another artist's link is no help: it would
            // fetch a catalogue this song isn't by.
            val ref = song.artists.firstOrNull() ?: return emptyList()
            val id = ref.id ?: ytm.artistIdByName(ref.name) ?: return emptyList()
            val page = ytm.artist(id)
            // The page itself carries five top songs; the list behind their "See all" has the artist's catalogue (a
            // hundred or so, across their albums). The handful picked has to come from that, not the same five every
            // time. (The page's own shuffle link is a "next" queue, not a list that can be read.)
            val pageSongs = page.shelves.flatMap { it.items }.filterIsInstance<SongItem>().map { it.song }
            val catalogue = page.shelves.firstOrNull { s -> s.moreBrowseId?.startsWith("VL") == true && s.items.any { it is SongItem && !it.song.isVideo } }
                ?.moreBrowseId?.let { vl ->
                    try {
                        ytm.playlist(vl).songs
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                }.orEmpty()
            (catalogue + pageSongs).filter { !it.isVideo && !it.isEpisode && !it.isLive && sharesArtist(it, song) }
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

/**
 * The handful to add: up to [want] shuffled songs, about a third of them from [phone] even when [online] has plenty, so
 * the listener's own copies (the ones that play without a network) sit among YouTube's. [online] null means YouTube
 * didn't answer: then they're the phone's alone. Nothing repeats, and a file on the phone isn't added beside YouTube's
 * copy of the same recording.
 */
internal fun mixMoreFrom(online: List<Song>?, phone: List<Song>, want: Int, exclude: Set<String>): List<Song> {
    if (want <= 0) return emptyList()
    val picked = mutableListOf<Song>()
    val phoneShare = if (online == null) want else want / 3
    picked += shuffledMoreFrom(phone, phoneShare, exclude = exclude)
    // Picked first, so YouTube's copy of what the phone already offered is left out of the pool.
    val pool = online.orEmpty().filterNot { candidate -> picked.any { sameRecording(it, candidate) } }
    picked += shuffledMoreFrom(pool, want - picked.size, exclude = exclude + picked.map { it.id })
    // What one side doesn't have, the other still might (a phone with only a couple of the artist's songs, say).
    if (picked.size < want) picked += shuffledMoreFrom(phone, want - picked.size, exclude = exclude + picked.map { it.id })
    if (picked.size < want) picked += shuffledMoreFrom(pool, want - picked.size, exclude = exclude + picked.map { it.id })
    picked.shuffle()
    return picked
}

/** Whether two entries are the same recording: a file on the phone and YouTube's copy of it carry different ids. */
internal fun sameRecording(a: Song, b: Song): Boolean =
    a.title.isNotBlank() && a.artists.isNotEmpty() && SongVersions.bare(a.title) == SongVersions.bare(b.title) &&
        sharesArtist(a, b)

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
