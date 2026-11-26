package com.prism.music

import com.prism.music.data.KeptPlaylist
import com.prism.music.data.PlaylistRead
import com.prism.music.data.PlaylistSongs
import com.prism.music.data.SongsFrom
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.CollectionKind
import com.prism.music.data.model.CollectionPage
import com.prism.music.data.model.Song
import com.prism.music.data.readPlaylist
import com.prism.music.data.refreshPlaylist
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Library → Songs' "From" (1.8.3, #382): chosen source blocks, once each; the ordered choice kept as text; whole
 * playlists read once and kept.
 */
class SongsFromTest {
    private fun s(id: String) = Song(id, "Song $id", listOf(ArtistRef("Artist")))

    @Test fun sourceBlocksFollowTheChosenOrderAndTheFirstSongWins() {
        val liked = listOf(s("a"), s("b"))
        val gym = listOf(s("c"), s("a"), s("d"))
        val road = listOf(s("d"), s("e"))
        val gymSource = SongsFrom.Source.Playlist("gym")
        val roadSource = SongsFrom.Source.Playlist("road")
        assertEquals(
            "Liked between playlists", listOf("c", "a", "d", "b", "e"),
            SongsFrom.merge(liked, listOf(gym, road), SongsFrom(listOf(gymSource, SongsFrom.Source.Liked, roadSource))).map { it.id },
        )
        assertEquals(
            "Liked last", listOf("d", "e", "c", "a", "b"),
            SongsFrom.merge(liked, listOf(road, gym), SongsFrom(listOf(roadSource, gymSource, SongsFrom.Source.Liked))).map { it.id },
        )
        assertEquals(
            "no Liked source", listOf("d", "e", "c", "a"),
            SongsFrom.merge(liked, listOf(road, gym), SongsFrom(listOf(roadSource, gymSource))).map { it.id },
        )
        assertEquals(
            "repeats within a playlist", listOf("c"),
            SongsFrom.merge(emptyList(), listOf(listOf(s("c"), s("c"))), SongsFrom(false, listOf("x"))).map { it.id },
        )
        val fromPlaylist = s("shared").copy(title = "From playlist")
        val fromLikes = s("shared").copy(title = "From likes")
        assertEquals(
            "the earlier source supplies the song", listOf("From playlist"),
            SongsFrom.merge(listOf(fromLikes), listOf(listOf(fromPlaylist)), SongsFrom(false, listOf("x"))).map { it.title },
        )
    }

    @Test fun legacyChoicesStillDecodeWithLikedFirst() {
        assertEquals(SongsFrom(true, listOf("a", "b")), SongsFrom.decode("liked|a,b"))
        assertEquals(SongsFrom(false, listOf("a", "b")), SongsFrom.decode("|a,b"))
        assertEquals(SongsFrom(false, listOf("liked")), SongsFrom.decode("|liked"))
        assertEquals("repeats and blanks dropped", listOf("a", "b"), SongsFrom.decode("liked|a,,b,a").playlists)
        assertTrue(SongsFrom.decode("|").isDefault)
    }

    @Test fun theOrderedChoiceIsKeptAsV2Text() {
        val from = SongsFrom(listOf(SongsFrom.Source.Playlist("gym"), SongsFrom.Source.Liked, SongsFrom.Source.Playlist("road")))
        assertEquals("v2|p:gym,liked,p:road", from.encode())
        assertEquals(from, SongsFrom.decode(from.encode()))
        assertEquals(SongsFrom(true, listOf("PLabc")), SongsFrom.decode(SongsFrom(true, listOf("PLabc")).encode()))
        val playlistNamedLiked = SongsFrom(false, listOf("liked"))
        assertEquals("v2|p:liked", playlistNamedLiked.encode())
        assertEquals(playlistNamedLiked, SongsFrom.decode(playlistNamedLiked.encode()))
        assertFalse("a playlist called liked is not the Liked source", playlistNamedLiked.liked)
    }

    @Test fun blankDuplicatesAndUnknownV2TokensAreDropped() {
        val decoded = SongsFrom.decode("v2| p:gym , ,liked,p:gym,unknown,liked,p: road ")
        assertEquals(
            listOf(SongsFrom.Source.Playlist("gym"), SongsFrom.Source.Liked, SongsFrom.Source.Playlist("road")),
            decoded.sources,
        )
    }

    @Test fun missingMalformedAndEmptyChoicesFallBackToLikedOnly() {
        listOf(null, "", " ", "liked", "|", "v2|", "v2| ,unknown,p:,p: ").forEach { stored ->
            assertTrue("'$stored' falls back to Liked songs", SongsFrom.decode(stored).isDefault)
        }
        assertTrue(SongsFrom().isDefault)
        assertFalse(SongsFrom(false, listOf("a")).isDefault)
        assertFalse(SongsFrom(true, listOf("a")).isDefault)
    }

    @Test fun aPlaylistIsReadOnceUntilRefreshed() = runBlocking {
        var reads = 0
        val lists = PlaylistSongs(load = { id, _ -> reads++; if (id == "bad") error("offline") else PlaylistRead(listOf(s("$id-1")), complete = true) })
        assertEquals("x-1", lists.songs("x")!!.single().id)
        lists.songs("x")
        assertEquals(1, reads)
        lists.refresh("x")
        lists.songs("x")
        assertEquals(2, reads)
        assertNull("a playlist that won't load shows nothing, no crash", lists.songs("bad"))
    }

    @Test fun theKeptCopySeedsAScreenWithoutReadingItAgain() = runBlocking {
        var reads = 0
        val lists = PlaylistSongs(load = { id, _ -> reads++; PlaylistRead(listOf(s("$id-1")), complete = true) })
        assertNull("nothing kept before the first read", lists.peek("x"))
        assertEquals("x-1", lists.songs("x")!!.single().id)
        assertEquals("the same copy, with no second read", "x-1", lists.peek("x")!!.single().id)
        assertEquals(1, reads)
        lists.refresh("x")
        assertNull("a refreshed playlist is not seeded from the old copy", lists.peek("x"))
    }

    @Test fun aReadThatStoppedShortIsReadAgainNextTime() = runBlocking {
        var reads = 0
        val lists = PlaylistSongs(load = { id, _ ->
            reads++
            if (reads == 1) PlaylistRead(listOf(s("$id-1")), complete = false)
            else PlaylistRead(listOf(s("$id-1"), s("$id-2"), s("$id-3")), complete = true)
        })
        assertEquals("the page that came shows meanwhile", listOf("x-1"), lists.songs("x")!!.map { it.id })
        assertFalse("but it isn't kept as the whole playlist", lists.isComplete("x"))
        assertEquals("the rest comes next time", listOf("x-1", "x-2", "x-3"), lists.songs("x")!!.map { it.id })
        assertTrue(lists.isComplete("x"))
        lists.songs("x")
        assertEquals("then it's kept whole", 2, reads)
    }

    @Test fun everyPageOfAPlaylistIsRead() = runBlocking {
        val first = page(listOf("a", "b"), continuation = "next")
        val read = readPlaylist(first, page = { token ->
            assertEquals("next", token)
            YouTubeMusic.PlaylistChunk(listOf(s("c"), s("d")), next = null, setVideoIds = emptyMap())
        }, retryMs = 0)
        assertEquals(listOf("a", "b", "c", "d"), read.songs.map { it.id })
        assertTrue(read.complete)
    }

    @Test fun aPageThatFailsIsAskedAgain() = runBlocking {
        var tries = 0
        val read = readPlaylist(page(listOf("a"), continuation = "next"), page = {
            if (++tries < 3) throw java.io.IOException("busy")
            YouTubeMusic.PlaylistChunk(listOf(s("b")), next = null, setVideoIds = emptyMap())
        }, retryMs = 0)
        assertEquals("a page lost once doesn't leave the rest out", listOf("a", "b"), read.songs.map { it.id })
        assertTrue(read.complete)
        assertEquals(3, tries)
    }

    @Test fun aPageThatNeverComesLeavesTheReadShortAndUnkept() = runBlocking {
        val read = readPlaylist(page(listOf("a"), continuation = "next"), page = { throw java.io.IOException("offline") }, retryMs = 0)
        assertEquals(listOf("a"), read.songs.map { it.id })
        assertFalse("shown, but not as the whole playlist", read.complete)
    }

    @Test fun leavingTheScreenDoesNotLeaveAShortListKept() = runBlocking {
        var reads = 0
        val lists = PlaylistSongs(load = { id, _ ->
            reads++
            // The first read is cancelled midway (the screen was left): it mustn't be kept as if it were whole.
            if (reads == 1) throw kotlinx.coroutines.CancellationException("the screen was left")
            PlaylistRead(listOf(s("$id-1"), s("$id-2")), complete = true)
        })
        try {
            lists.songs("x")
            throw AssertionError("the read should have been cancelled")
        } catch (_: kotlinx.coroutines.CancellationException) {
        }
        assertEquals("the cancelled read kept nothing", listOf("x-1", "x-2"), lists.songs("x")!!.map { it.id })
        assertEquals(2, reads)
    }

    @Test fun leavingTheScreenStopsTheRead() = runBlocking {
        try {
            readPlaylist(page(listOf("a"), continuation = "next"), page = {
                throw kotlinx.coroutines.CancellationException("the screen was left")
            }, retryMs = 0)
            throw AssertionError("the read should have been cancelled, not returned short")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Passed on: the caller keeps nothing, so the next visit reads it all again.
        }
    }

    @Test fun everyPlaylistIsReadAgainAfterALibrarySync() = runBlocking {
        var reads = 0
        val lists = PlaylistSongs(load = { id, _ -> reads++; PlaylistRead(listOf(s("$id-1")), complete = true) })
        lists.songs("x")
        lists.songs("y")
        assertEquals(2, reads)
        // A library sync: the account's playlists may have changed, so the session's copies go.
        lists.refreshAll()
        lists.songs("x")
        assertEquals(3, reads)
    }

    @Test fun aReadMadeByTheKeptDownloadCheckServesThisViewToo() = runBlocking {
        var reads = 0
        val lists = PlaylistSongs(load = { _, _ -> reads++; PlaylistRead(emptyList(), complete = true) })
        // The kept-download check has already read the playlist: no second request when Songs opens.
        lists.noteRead("PLx", listOf(s("k1"), s("k2")), count = 2)
        assertEquals(listOf("k1", "k2"), lists.songs("PLx")!!.map { it.id })
        assertEquals(0, reads)
    }

    @Test fun aSameNumberOfSongsWithTheSameStartKeepsTheCopy() = runBlocking {
        val cached = KeptPlaylist(listOf(s("a"), s("b")), 2)
        var all = false
        val read = refreshPlaylist(
            page(listOf("a", "b"), continuation = null, count = 2), cached,
            newestPage = { error("nothing new to ask for") },
            readAll = { all = true; PlaylistRead(listOf(s("a"), s("b")), complete = true, count = 2) },
        )
        assertEquals(listOf("a", "b"), read.songs.map { it.id })
        assertFalse("the copy is the playlist as it stands", all)
    }

    @Test fun aSwapWithTheSameNumberIsReadAgain() = runBlocking {
        val cached = KeptPlaylist(listOf(s("a"), s("b")), 2)
        var all = false
        val read = refreshPlaylist(
            page(listOf("b", "a"), continuation = null, count = 2), cached,
            newestPage = { error("not asked") },
            readAll = { all = true; PlaylistRead(listOf(s("b"), s("a")), complete = true, count = 2) },
        )
        assertTrue("the copy's start isn't the playlist's any more", all)
        assertEquals(listOf("b", "a"), read.songs.map { it.id })
    }

    @Test fun aCountYouTubeNeverGaveStaysUnknown() = runBlocking {
        val cached = KeptPlaylist(listOf(s("a"), s("b")), null)
        val read = refreshPlaylist(
            page(listOf("c"), continuation = null, count = null, newest = "newest"), cached,
            newestPage = { YouTubeMusic.PlaylistChunk(listOf(s("c"), s("a")), next = null, setVideoIds = emptyMap()) },
            readAll = { error("the newest page joins it") },
        )
        assertEquals(listOf("c", "a", "b"), read.songs.map { it.id })
        assertNull("not invented from the list's own size", read.count)
    }

    @Test fun aWholeReadIsHandedOnSoItsNewSongsDownload() = runBlocking {
        val seen = mutableListOf<Pair<String, List<String>>>()
        val lists = PlaylistSongs(
            load = { id, _ -> PlaylistRead(listOf(s("$id-1"), s("$id-2")), complete = true) },
            onRead = { id, songs -> seen += id to songs.map { it.id } },
        )
        lists.songs("PLx")
        assertEquals("a read is a read: the kept check fetches from it too", listOf("PLx" to listOf("PLx-1", "PLx-2")), seen)
    }

    @Test fun aReadThatStoppedShortIsNotHandedOn() = runBlocking {
        val seen = mutableListOf<String>()
        val lists = PlaylistSongs(
            load = { id, _ -> PlaylistRead(listOf(s("$id-1")), complete = false) },
            onRead = { id, _ -> seen += id },
        )
        lists.songs("PLx")
        assertTrue("a short read isn't the playlist: nothing is fetched from it", seen.isEmpty())
    }

    private fun page(songs: List<String>, continuation: String?, count: Int? = null, newest: String? = null) = CollectionPage(
        id = "playlist", kind = CollectionKind.PLAYLIST, title = "Playlist", subtitle = "", thumbnail = null,
        songs = songs.map(::s), continuation = continuation, newestContinuation = newest, trackCount = count,
    )
}
