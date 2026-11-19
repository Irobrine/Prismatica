package com.prism.music

import com.prism.music.data.lyrics.LyricLine
import com.prism.music.ui.player.withWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A line timed only as a whole still fills word by word: by spaces, or by the script runs Japanese and Chinese have. */
class WordFillTest {
    @Test fun japaneseFillsByItsScriptRuns() {
        val line = LyricLine(1_000, "今日はいい天気ですね", 5_000).withWords()
        assertTrue("a Japanese line must move word by word", line.words.size > 1)
        assertEquals("the words must read as the line does", line.text, line.words.joinToString("") { it.text })
        assertEquals("", line.words.filter { it.text.isBlank() }.joinToString { it.text })
        var last = line.timeMs
        for (w in line.words) { assertTrue("times run forward", w.startMs >= last); last = w.startMs }
        assertTrue("the words end inside the line", line.words.last().endMs <= line.endMs)
    }

    @Test fun aLineWithReadingsFillsOverTheCleanedText() {
        // What the player draws after SourceFurigana: the readings are gone and so are their words.
        val line = LyricLine(1_000, "君の名前", 3_000).withWords()
        assertTrue("a line of kanji and kana must still split", line.words.size > 1)
        assertEquals("君の名前", line.words.joinToString("") { it.text })
    }

    @Test fun chineseSplitsToo() {
        val line = LyricLine(0, "今天天气很好", 2_000).withWords()
        assertTrue("a Chinese line must move word by word", line.words.size > 1)
        assertEquals("今天天气很好", line.words.joinToString("") { it.text })
    }

    @Test fun englishStillSplitsOnSpaces() {
        val line = LyricLine(0, "the quick brown fox", 2_000).withWords()
        assertEquals(listOf("the ", "quick ", "brown ", "fox"), line.words.map { it.text })
    }

    @Test fun aWordTimedLineIsLeftAlone() {
        val words = listOf(com.prism.music.data.lyrics.LyricWord(0, 500, "今日"), com.prism.music.data.lyrics.LyricWord(500, 1_000, "は"))
        val line = LyricLine(0, "今日は", 1_000, words = words)
        assertEquals(words, line.withWords().words)
    }
}
