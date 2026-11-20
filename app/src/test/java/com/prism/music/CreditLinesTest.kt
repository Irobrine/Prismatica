package com.prism.music

import com.prism.music.data.enhance.CreditLines
import com.prism.music.data.lyrics.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Credit lines at the edges of a lyric file are kept out of what the aligner is asked to find in the audio. */
class CreditLinesTest {
    private fun line(text: String, at: Long = 0) = LyricLine(at, text)

    @Test fun aCreditLineAtTheTopIsLeftOut() {
        val lines = listOf(line("词：やなぎなぎ"), line("地面には一路の轍"), line("どこへいこうか"), line("こわれた世界"))
        val out = CreditLines.strip(lines)
        assertEquals(1, CreditLines.count(lines))
        assertEquals("", out[0].text)
        assertTrue(out[0].isGap)
        // The sung lines are untouched, in place, so every index still matches the lyrics.
        assertEquals("地面には一路の轍", out[1].text)
        assertEquals(lines[1], out[1])
        assertEquals(lines[3], out[3])
    }

    @Test fun theShapesACreditLineReallyHas() {
        val credits = listOf(
            "作詞：kz", "作詞・作曲：kz", "編曲：kz", "Lyrics: kz", "Lyrics & Music: kz", "Composed by kz",
            "Lyrics by kz", "Produced by kz", "℗ 2024 Something Records", "© 2024 kz", "https://example.com/song",
            "Vocals: kz", "Song by kz",
        )
        for (text in credits) {
            assertTrue(text, CreditLines.strip(listOf(line(text), line("a"), line("b")))[0].isGap)
        }
    }

    @Test fun aSungLineThatMerelyStartsWithSuchAWordIsKept() {
        val sung = listOf(
            "曲がり角には", "Music to my ears", "I would write you a song", "music", "Song of the wind",
            "https://example.com in the middle", "word: not a credit",
        )
        for (text in sung) {
            val lines = listOf(line(text), line("a"), line("b"))
            assertSame(text, lines, CreditLines.strip(lines))
        }
    }

    @Test fun onlyTheEdgesAreLookedAtAndShortLyricsAreLeftWhole() {
        val many = MutableList(20) { line("歌詞 $it") }
        many[0] = line("作詞：kz")
        many[10] = line("作曲：kz") // in the middle: a sung line that happens to look like one is left alone
        val out = CreditLines.strip(many)
        assertTrue(out[0].isGap)
        assertEquals("作曲：kz", out[10].text)
        assertEquals(1, CreditLines.count(many))

        val tiny = listOf(line("作詞：kz"), line("歌"))
        assertSame(tiny, CreditLines.strip(tiny))
    }
}
