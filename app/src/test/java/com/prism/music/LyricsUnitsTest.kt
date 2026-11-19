package com.prism.music

import com.prism.music.data.enhance.AlignLanguage
import com.prism.music.data.enhance.AlignedUnit
import com.prism.music.data.enhance.LyricsUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The aligner's units and the words they're shown as, for the scripts the feature claims. */
class LyricsUnitsTest {
    @Test fun englishWordsKeepPunctuationAndSpaces() {
        val text = "Hello, world! It's 2026."
        val units = LyricsUnits.units(text, AlignLanguage.ENGLISH)
        assertEquals(listOf("Hello", "world", "It's", "2026"), units.map { it.clean })
        val timed = units.mapIndexed { i, u -> AlignedUnit(u.clean, u.start, u.end, u.group, i * 500L, i * 500L + 400, u.line) }
        val words = LyricsUnits.buildWords(timed, text)
        assertEquals(text, words.joinToString("") { it.text })
        assertEquals(listOf("Hello, ", "world! ", "It's ", "2026."), words.map { it.text })
    }

    @Test fun japaneseIsSplitByScriptRun() {
        val text = "今日はいい天気ですね"
        val units = LyricsUnits.units(text, AlignLanguage.JAPANESE)
        assertEquals(listOf("今日", "は", "いい", "天気", "です", "ね"), units.map { it.clean })
        val timed = units.mapIndexed { i, u -> AlignedUnit(u.clean, u.start, u.end, u.group, i * 400L, i * 400L + 350, u.line) }
        val words = LyricsUnits.buildWords(timed, text)
        assertEquals(text, words.joinToString("") { it.text })
    }

    @Test fun chineseIsTimedByCharacterAndGroupedIntoWords() {
        val text = "今天天气很好"
        val units = LyricsUnits.units(text, AlignLanguage.CHINESE)
        assertEquals(text.toList().map { it.toString() }, units.map { it.clean })
        val timed = units.mapIndexed { i, u -> AlignedUnit(u.clean, u.start, u.end, u.group, i * 200L, i * 200L + 180, u.line) }
        val words = LyricsUnits.buildWords(timed, text)
        assertEquals(text, words.joinToString("") { it.text })
        // ICU or the fallback both group more than one character here; a single-char-per-word result would lose the
        // word-level promise of the feature.
        assertTrue("Chinese words should be grouped: ${words.map { it.text }}", words.size < units.size)
    }

    @Test fun koreanKeepsEojeolAndPunctuation() {
        val text = "오늘 날씨가 정말 좋네요."
        val units = LyricsUnits.units(text, AlignLanguage.KOREAN)
        assertEquals(listOf("오늘", "날씨가", "정말", "좋네요"), units.map { it.clean })
        val timed = units.mapIndexed { i, u -> AlignedUnit(u.clean, u.start, u.end, u.group, i * 500L, i * 500L + 450, u.line) }
        val words = LyricsUnits.buildWords(timed, text)
        assertEquals(text, words.joinToString("") { it.text })
    }

    @Test fun languageComesFromTheScript() {
        assertEquals(AlignLanguage.JAPANESE, AlignLanguage.of("今日はいい天気"))
        assertEquals(AlignLanguage.CHINESE, AlignLanguage.of("今天天气很好"))
        assertEquals(AlignLanguage.KOREAN, AlignLanguage.of("오늘 날씨"))
        assertEquals(AlignLanguage.ENGLISH, AlignLanguage.of("Hello world"))
        assertNotNull(AlignLanguage.of("Mixed 今日 text"))
    }
}
