package com.prism.music

import com.prism.music.data.MoreFrom
import com.prism.music.data.MoreFromPools
import com.prism.music.data.mixMoreFrom
import com.prism.music.data.sameRecording
import com.prism.music.data.ONLINE_BUDGET_MS
import com.prism.music.data.phoneMoreFrom
import com.prism.music.data.shuffledMoreFrom
import com.prism.music.data.sharesAlbum
import com.prism.music.data.sharesArtist
import com.prism.music.data.withinOrNull
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.8.6: "More from this artist/album" picks songs by the one playing. The phone's own pool (Downloads and Local files)
 * is what plays offline; it must hold the same artist's or album's songs and never the song itself, and asking for more
 * than there are gives all there are rather than none.
 */
class MoreFromTest {
    private val artist = ArtistRef("Radiohead", "UCradiohead")
    private val current = Song("song", "Everything in Its Right Place", listOf(artist), AlbumRef("Kid A", "MPREkid"))

    private fun song(
        id: String,
        title: String = "Song $id",
        artists: List<ArtistRef> = listOf(artist),
        album: AlbumRef? = AlbumRef("Kid A", "MPREkid"),
    ) = Song(id, title, artists, album)

    @Test fun anArtistMatchesByLinkOrName() {
        assertTrue(sharesArtist(song("a"), current))
        assertTrue("the same name without a link", sharesArtist(song("b", artists = listOf(ArtistRef("radiohead"))), current))
        assertFalse(sharesArtist(song("c", artists = listOf(ArtistRef("Portishead", "UCportishead"))), current))
        assertTrue("one of several artists", sharesArtist(song("d", artists = listOf(ArtistRef("Thom Yorke"), artist)), current))
        assertFalse("no artists at all", sharesArtist(song("e", artists = emptyList()), current))
    }

    @Test fun anAlbumMatchesByLinkOrByNameAndArtist() {
        assertTrue(sharesAlbum(song("a"), current))
        assertFalse(sharesAlbum(song("b", album = AlbumRef("Amnesiac", "MPREamnesiac")), current))
        // A file's album carries only its name; an artist in common says it's the same album.
        val file = AlbumRef("Kid A")
        assertTrue(sharesAlbum(song("c", album = file), current))
        assertFalse("the same title by another artist is another album", sharesAlbum(song("d", artists = listOf(ArtistRef("Portishead")), album = file), current))
        assertFalse("no album", sharesAlbum(song("e", album = null), current))
    }

    @Test fun thePhoneHoldsOnlyTheSameArtistOrAlbumWithoutTheSongItself() {
        val byArtist = song("byArtist", title = "Idioteque", album = AlbumRef("Amnesiac", "MPREamnesiac"))
        val byOther = song("byOther", artists = listOf(ArtistRef("Portishead")), album = AlbumRef("Dummy", "MPREdummy"))
        val sameAlbum = song("sameAlbum", title = "The National Anthem")
        val downloadedAlbum = song("downloadedAlbum", title = "Optimistic")
        val anotherAlbum = song("anotherAlbum", title = "Pyramid Song", album = AlbumRef("Amnesiac", "MPREamnesiac"))
        val downloads = listOf(current, byArtist, byOther, downloadedAlbum, anotherAlbum)
        val local = listOf(current, sameAlbum, byOther)

        assertEquals(
            setOf("byArtist", "sameAlbum", "downloadedAlbum", "anotherAlbum"),
            phoneMoreFrom(current, MoreFrom.ARTIST, downloads, local).map { it.id }.toSet(),
        )
        assertEquals(
            setOf("sameAlbum", "downloadedAlbum"),
            phoneMoreFrom(current, MoreFrom.ALBUM, downloads, local).map { it.id }.toSet(),
        )
        // One copy of a song downloaded and also on the phone as a file.
        assertEquals(listOf("byArtist"), phoneMoreFrom(current, MoreFrom.ARTIST, listOf(byArtist), listOf(byArtist)).map { it.id })
    }

    @Test fun aShuffleTakesTheNumberAskedForAndNoMore() {
        val pool = (1..10).map { song("s$it") }
        val three = shuffledMoreFrom(pool, 3)
        assertEquals(3, three.size)
        assertEquals(3, three.map { it.id }.distinct().size)
        assertTrue(three.all { it.id in pool.map { s -> s.id } })
        assertEquals("all there are, when fewer than asked", 4, shuffledMoreFrom(pool.take(4), 10).size)
        assertTrue(shuffledMoreFrom(pool, 0).isEmpty())
        assertEquals("the song itself is left out", 9, shuffledMoreFrom(pool, 10, exclude = setOf("s1")).size)
        assertFalse(shuffledMoreFrom(pool, 10, exclude = setOf("s1")).any { it.id == "s1" })
        assertEquals("nothing asked for, and all left out", 0, shuffledMoreFrom(pool, 10, exclude = pool.map { it.id }.toSet()).size)
    }

    @Test fun theCountHasItsOwnRange() {
        assertEquals(3, MoreFrom.DEFAULT_COUNT)
        assertEquals(1, MoreFrom.clamp(0))
        assertEquals(25, MoreFrom.clamp(99))
        assertEquals(7, MoreFrom.clamp(7))
    }

    /**
     * A tap gives YouTube a budget and no more. OkHttp can't be interrupted, so the look-up runs on the app's own scope
     * and one that outlasts the budget is left behind: a network that can't answer doesn't hold the tap on its connect
     * timeout. A look-up that answers in time is kept.
     */
    @Test fun aLookupThatCantAnswerDoesNotHoldTheTap() = runBlocking {
        assertTrue("the budget is short", ONLINE_BUDGET_MS <= 2_000)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val started = System.currentTimeMillis()
            assertNull("a look-up that never answers gives nothing", scope.withinOrNull(150) { delay(5_000); "songs" })
            val took = System.currentTimeMillis() - started
            assertTrue("waited $took ms on a look-up that can't finish", took < 2_000)
            assertEquals("a look-up that answers in time is kept", "songs", scope.withinOrNull(5_000) { "songs" })
        } finally {
            scope.cancel()
        }
    }

    /**
     * What YouTube offered for an artist is kept for a while, so the next tap picks from it without asking again, and a
     * network that found nowhere to go is remembered for a minute: an offline tap after the first goes to the phone at
     * once instead of waiting out the budget every time.
     */
    @Test fun thePoolsKeepWhatWasFoundAndForgetADeadNetwork() {
        val pools = MoreFromPools(ttlMs = 1_000, downMs = 500)
        assertEquals("artist:UCradiohead", pools.key(current, MoreFrom.ARTIST))
        assertEquals("album:MPREkid", pools.key(current, MoreFrom.ALBUM))
        // No link: the name, normalized, keeps the same artist's songs together.
        assertEquals("artist:portishead", pools.key(Song("x", "Glory Box", listOf(ArtistRef("Portishead")), null), MoreFrom.ARTIST))

        assertNull("nothing kept yet", pools.get("artist:UCradiohead", 0))
        pools.put("artist:UCradiohead", listOf(current), now = 0)
        assertEquals(listOf(current), pools.get("artist:UCradiohead", 999))
        assertNull("a stale pool is asked for again", pools.get("artist:UCradiohead", 1_000))

        assertFalse("a working YouTube isn't given up on", pools.isDown(0))
        pools.down(0)
        assertTrue(pools.isDown(499))
        pools.up()
        assertFalse("back online, the next tap asks again", pools.isDown(499))
        pools.down(600)
        assertFalse("the taps after a minute try again", pools.isDown(600 + 60_000))
    }

    /**
     * Even with YouTube answering, about a third of the handful is the phone's own copies, so they play without a
     * network; the rest is YouTube's. A side that's short is made up by the other, and nothing repeats.
     */
    @Test fun aThirdOfTheHandfulIsThePhonesEvenOnline() {
        val online = (1..100).map { song("o$it") }
        val phone = (1..10).map { song("p$it") }

        val three = mixMoreFrom(online, phone, want = 3, exclude = emptySet())
        assertEquals(3, three.size)
        assertEquals("one of three is the phone's", 1, three.count { it.id.startsWith("p") })

        val six = mixMoreFrom(online, phone, want = 6, exclude = emptySet())
        assertEquals(6, six.size)
        assertEquals(2, six.count { it.id.startsWith("p") })

        // YouTube didn't answer: the phone's alone, filled to the number asked.
        val offline = mixMoreFrom(null, phone, want = 3, exclude = emptySet())
        assertEquals(3, offline.size)
        assertTrue(offline.all { it.id.startsWith("p") })

        // One side short: the other fills in, and no song comes twice.
        val short = mixMoreFrom(listOf(song("o1")), phone, want = 4, exclude = emptySet())
        assertEquals(4, short.size)
        assertEquals(4, short.map { it.id }.distinct().size)
        assertTrue(short.any { it.id == "o1" })

        // The song playing never comes back.
        val kept = mixMoreFrom(online, phone, want = 5, exclude = setOf("p1", "o1"))
        assertEquals(5, kept.size)
        assertFalse(kept.any { it.id == "p1" || it.id == "o1" })
    }

    /** A file on the phone and YouTube's copy of the same song are one song, though their ids differ. */
    @Test fun theSameRecordingIsNotAddedTwice() {
        val file = Song("local:1", "Creep", listOf(ArtistRef("Radiohead")), null)
        val stream = Song("v1", "Creep", listOf(ArtistRef("Radiohead", "UCradiohead")), null)
        assertTrue(sameRecording(file, stream))
        assertFalse(sameRecording(file, song("o1", title = "High and Dry")))
        val out = mixMoreFrom((1..10).map { song("o$it") } + stream, listOf(file), want = 3, exclude = emptySet())
        assertEquals(3, out.size)
        assertEquals("one Creep, the phone's", 1, out.count { it.title == "Creep" })
        assertTrue(out.any { it.id == "local:1" })
    }
}
