package com.prism.music

import com.prism.music.data.LibEntry
import com.prism.music.data.LibFilter
import com.prism.music.data.LibKind
import com.prism.music.data.LibrarySearch
import com.prism.music.data.db.LyricsRow
import com.prism.music.data.lyrics.SavedLyricsSearch
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.ui.components.LyricsMode
import com.prism.music.ui.components.lyricExtras
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lyrics mode: which saved lyrics a search finds, the line it shows, and the toggle's life. */
class SavedLyricsSearchTest {
    private fun doc(id: String, vararg lines: String) = SavedLyricsSearch.Doc(id, "LRCLIB", lines.toList())

    private val docs = listOf(
        doc("a", "Is this the real life?", "Is this just fantasy?", "Caught in a landslide"),
        doc("b", "Je ne regrette rien", "Ni le bien qu'on m'a fait"),
        doc("c", "We were both young", "when I first saw you", "I close my eyes"),
        doc("d", "Café au lait, s'il vous plaît"),
        doc("e", "君の名は", "愛してる 今も"),
    )

    @Test
    fun wordsTogetherInALineComeFirst() {
        val m = SavedLyricsSearch.matchAll(docs, "just fantasy")
        assertEquals(listOf("a"), m.map { it.songId })
        assertEquals(0, m[0].rank)
        assertEquals("Is this just fantasy?", m[0].line)
    }

    @Test
    fun wordsRunningIntoTheNextLineAndAnywhereRankLower() {
        val run = SavedLyricsSearch.matchAll(docs, "young first saw")
        assertEquals(1, run.single().rank)
        assertEquals("We were both young / when I first saw you", run.single().line)
        val spread = SavedLyricsSearch.matchAll(docs, "young eyes")
        assertEquals(2, spread.single().rank)
    }

    @Test
    fun capitalsAccentsAndPunctuationDontMatter() {
        assertEquals(listOf("d"), SavedLyricsSearch.matchAll(docs, "CAFE au lait sil").map { it.songId }.take(1))
        assertEquals(listOf("b"), SavedLyricsSearch.matchAll(docs, "regrette").map { it.songId })
    }

    @Test
    fun scriptsWithoutSpacesAreFoundAsTyped() {
        val m = SavedLyricsSearch.matchAll(docs, "愛してる")
        assertEquals("e", m.single().songId)
        assertEquals("愛してる 今も", m.single().line)
    }

    @Test
    fun latinSpellingsFindOtherScripts() {
        val k = doc("k", "사랑해 너를", "Baby baby")
        assertTrue(SavedLyricsSearch.matchAll(listOf(k), "saranghae").isEmpty())
        val spelled = doc("k", "사랑해 너를", "Baby baby")
        // What the romanizer gives: the Korean line spelled, the Latin one needs none.
        spelled.spelled(listOf("saranghae neoreul", null))
        val m = SavedLyricsSearch.matchAll(listOf(spelled), "saranghae")
        assertEquals("사랑해 너를", m.single().line)
        assertEquals(0, m.single().rank)
        // Without its spaces too.
        assertEquals(1, SavedLyricsSearch.matchAll(listOf(spelled), "saranghaeneoreul").size)
    }

    @Test
    fun oneLetterFindsNothing() {
        assertTrue(SavedLyricsSearch.matchAll(docs, "a").isEmpty())
        assertTrue(SavedLyricsSearch.matchAll(docs, "  ").isEmpty())
    }

    @Test
    fun findReadsTheSavedLyricsOnlyAmongTheSongsAskedAndSpellsInTheBackground() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // The lyrics table, by rowid; a page is 100 rows.
        val table = sortedMapOf<Long, LyricsRow>()
        fun save(row: Long, song: String, content: String, source: String = "LRCLIB", at: Long = row) { table[row] = LyricsRow(row, song, source, content, at) }
        save(1, "x", "Hold me closer")
        save(2, "y", "Hold the line")
        save(3, "k", "사랑해")
        for (i in 4L..250L) save(i, "f$i", "filler line $i")
        val asked = mutableListOf<Long>()
        val search = SavedLyricsSearch(
            scope,
            page = { after, limit -> asked += after; table.tailMap(after + 1).values.take(limit) },
            lines = { it.lines() },
            spell = { lines -> lines.map { if (it == "사랑해") "saranghae" else null } },
        )
        assertEquals(listOf("x", "y"), search.find("hold").map { it.songId }.sorted())
        assertEquals(listOf(0L, 100L, 200L), asked)
        assertEquals(listOf("y"), search.find("hold", among = setOf("y")).map { it.songId })
        assertTrue(search.find("hold", among = emptySet()).isEmpty())
        // Only from the last row on, after that.
        assertEquals(249L, asked.last())
        // Saved since: a new song, a song's lyrics replaced (a new rowid), and a miss over a source's lyrics.
        table.remove(1); save(251, "x", "Let it go")
        save(252, "z", "Hold on tight")
        table.remove(2); save(253, "y", "")
        assertEquals(listOf("z"), search.find("hold").map { it.songId })
        assertEquals(listOf("x"), search.find("let it go").map { it.songId })
        // The newest row replaced keeps its rowid, and is still read again.
        save(253, "y", "Hold it now", at = 999)
        assertEquals(listOf("y", "z"), search.find("hold").map { it.songId }.sorted())
        withTimeout(5_000) { search.version.first { it > 0 } }
        assertEquals(listOf("k"), search.find("saranghae").map { it.songId })
        scope.cancel()
    }

    @Test
    fun theToggleGoesOffOnceTheSearchIsMadeAndItsResultsStay() {
        val mode = LyricsMode()
        assertFalse(mode.on)
        assertFalse(mode.active("hello"))
        mode.toggle("hello")
        assertTrue(mode.active("hello"))
        mode.left("hello ")
        assertFalse(mode.on)
        // The search made with it on keeps its lyric matches…
        assertTrue(mode.active("hello"))
        // …until the text changes.
        assertFalse(mode.active("hello world"))
        // Back in the field, changed and left again without the toggle: an ordinary search.
        mode.left("hello world")
        assertFalse(mode.active("hello"))
    }

    @Test
    fun turnedOffByHandTheResultsAreTheUsualOnes() {
        val mode = LyricsMode()
        mode.toggle("love")
        mode.left("love")
        mode.toggle("love") // back in the field: on again…
        mode.toggle("love") // …and off
        assertFalse(mode.active("love"))
    }

    @Test
    fun lyricMatchesJoinTheLibrarysSongsAfterThoseFoundByName() {
        fun song(id: String) = Song(id = id, title = id, artists = emptyList())
        fun entry(id: String, liked: Boolean = false) = LibEntry(LibKind.SONG, SongItem(song(id)), listOf(id), liked = liked)
        val album = LibEntry(LibKind.ALBUM, AlbumItem("al", "Album", "", null), listOf("Album"))
        val entries = listOf(entry("s1"), entry("s2", liked = true), entry("s3"), album)
        val found = listOf(entry("s1"), album)
        val (list, added) = LibrarySearch.withLyrics(found, entries, LibFilter(), listOf("s3", "s1", "s2"))
        assertEquals(listOf("s1", "s3", "s2", "al"), list.map { it.item.id })
        assertEquals(setOf("s3", "s2"), added)
        // Filters hold for them too.
        assertEquals(listOf("s1", "s2", "al"), LibrarySearch.withLyrics(found, entries, LibFilter(liked = true), listOf("s3", "s2")).first.map { it.item.id })
        assertEquals(found, LibrarySearch.withLyrics(found, entries, LibFilter(kind = LibKind.ALBUM), listOf("s3")).first)
        // In a plain list: the songs found by name keep no line.
        val extras = lyricExtras(listOf(song("s1")), listOf(SavedLyricsSearch.Match("s1", "x", 0), SavedLyricsSearch.Match("s3", "y", 1)))
        assertEquals(mapOf("s3" to "y"), extras)
    }
}
