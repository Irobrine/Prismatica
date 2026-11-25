package com.prism.music

import com.prism.music.data.MoreFrom
import com.prism.music.data.phoneMoreFrom
import com.prism.music.data.shuffledMoreFrom
import com.prism.music.data.sharesAlbum
import com.prism.music.data.sharesArtist
import com.prism.music.data.model.AlbumRef
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
