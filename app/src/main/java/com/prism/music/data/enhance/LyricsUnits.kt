package com.prism.music.data.enhance

import com.prism.music.data.lyrics.LyricLine
import java.text.BreakIterator
import java.util.Locale

/** The language a line's alignment should be asked for. Only these four are claimed (Qwen3-ForcedAligner supports them). */
enum class AlignLanguage(val qwen: String, val label: String) {
    ENGLISH("English", "English"),
    JAPANESE("Japanese", "日本語"),
    CHINESE("Chinese", "中文"),
    KOREAN("Korean", "한국어"),
    ;

    companion object {
        /**
         * The dominant script of [text]. Japanese wins when kana is present (Chinese and Japanese share ideographs);
         * Hangul is Korean; other ideographs are Chinese; anything else is English. Null when no letters or digits.
         */
        fun of(text: String): AlignLanguage? {
            var kana = 0; var hangul = 0; var ideograph = 0; var latin = 0; var other = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i); val n = Character.charCount(cp)
                when {
                    LyricsUnits.isKana(cp) -> kana++
                    LyricsUnits.isHangul(cp) -> hangul++
                    LyricsUnits.isIdeograph(cp) -> ideograph++
                    Character.isLetterOrDigit(cp) -> if (cp < 0x2E80) latin++ else other++
                }
                i += n
            }
            return when {
                kana > 0 -> JAPANESE
                hangul > 0 -> KOREAN
                ideograph > 0 -> CHINESE
                latin + other > 0 -> if (other > latin * 3) null else ENGLISH
                else -> null
            }
        }
    }
}

/** One unit the model times: its cleaned form, where it sits in the line, and its display word when grouped (CJK). */
data class AlignUnit(
    val clean: String,
    val start: Int,
    val end: Int,
    /** Which display word this unit belongs to; -1 until grouping. */
    var group: Int = -1,
    /** The line this unit came from. */
    var line: Int = -1,
)

/**
 * Splits a lyric line into the units the aligner times, without changing the line:
 * English and Korean words by spaces, Chinese by character (the model's own choice, the characters are grouped into
 * words again afterwards), Japanese by script runs (kuromoji-like words without needing a dictionary: a kanji compound,
 * a katakana word, a hiragana run), numbers as one unit, and Latin words inside CJK text as one. Punctuation is not a
 * unit; [buildWords] attaches it to the word before it so the line still reads exactly as written.
 */
object LyricsUnits {
    fun units(text: String, language: AlignLanguage): List<AlignUnit> {
        val out = ArrayList<AlignUnit>()
        when (language) {
            AlignLanguage.ENGLISH, AlignLanguage.KOREAN -> {
                var i = 0
                while (i < text.length) {
                    val cp = text.codePointAt(i); val n = Character.charCount(cp)
                    if (isWordChar(cp)) {
                        val start = i
                        i += n
                        while (i < text.length) {
                            val c = text.codePointAt(i)
                            if (!isWordChar(c) && !(c == '\''.code || c == '’'.code)) break
                            i += Character.charCount(c)
                        }
                        val raw = text.substring(start, i)
                        val clean = raw.filter { it.isLetterOrDigit() || it == '\'' || it == '’' }
                        if (clean.isNotEmpty()) out += AlignUnit(clean, start, i)
                    } else i += n
                }
            }
            AlignLanguage.CHINESE -> {
                var i = 0
                while (i < text.length) {
                    val cp = text.codePointAt(i); val n = Character.charCount(cp)
                    when {
                        LyricsUnits.isIdeograph(cp) -> { out += AlignUnit(text.substring(i, i + n), i, i + n); i += n }
                        isLatinWord(cp) -> {
                            val start = i; i += n
                            while (i < text.length && (isLatinWord(text.codePointAt(i)) || text[i] == '\'' || text[i] == '’')) i++
                            val raw = text.substring(start, i)
                            val clean = raw.filter { it.isLetterOrDigit() || it == '\'' || it == '’' }
                            if (clean.isNotEmpty()) out += AlignUnit(clean, start, i)
                        }
                        else -> i += n
                    }
                }
            }
            AlignLanguage.JAPANESE -> {
                var i = 0
                while (i < text.length) {
                    val cp = text.codePointAt(i); val n = Character.charCount(cp)
                    when {
                        LyricsUnits.isIdeograph(cp) -> {
                            val start = i; i += n
                            while (i < text.length && isIdeograph(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i))
                            out += AlignUnit(text.substring(start, i), start, i)
                        }
                        isKatakana(cp) -> {
                            val start = i; i += n
                            while (i < text.length && isKatakana(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i))
                            out += AlignUnit(text.substring(start, i), start, i)
                        }
                        isHiragana(cp) -> {
                            val start = i; i += n
                            while (i < text.length && isHiragana(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i))
                            hiraganaRun(text, start, i, out)
                        }
                        isLatinWord(cp) -> {
                            val start = i; i += n
                            while (i < text.length && (isLatinWord(text.codePointAt(i)) || text[i] == '\'' || text[i] == '’')) i++
                            val clean = text.substring(start, i).filter { it.isLetterOrDigit() || it == '\'' || it == '’' }
                            if (clean.isNotEmpty()) out += AlignUnit(clean, start, i)
                        }
                        Character.isDigit(cp) -> {
                            val start = i; i += n
                            while (i < text.length && Character.isDigit(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i))
                            out += AlignUnit(text.substring(start, i), start, i)
                        }
                        else -> i += n
                    }
                }
            }
        }
        // Group Chinese characters into words for display, while the model still sees one slot per character.
        if (language == AlignLanguage.CHINESE) groupChinese(out, text)
        else out.forEachIndexed { i, u -> u.group = i }
        return out
    }

    /**
     * Groups consecutive Chinese characters into words (ICU's dictionary breaker; a conservative two-character grouping
     * when it isn't available on the test JVM). Non-CJK units (a Latin word or a number) are a group of their own.
     */
    private fun groupChinese(units: List<AlignUnit>, text: String) {
        if (units.isEmpty()) return
        val starts = units.map { it.start }
        val boundaries = HashSet<Int>()
        val ok = runCatching {
            val it = BreakIterator.getWordInstance(Locale.CHINESE)
            it.setText(text)
            var at = it.first()
            while (at != BreakIterator.DONE) { boundaries += at; at = it.next() }
            true
        }.getOrDefault(false)
        var group = 0
        var previousEnd = -1
        for ((i, u) in units.withIndex()) {
            val cjk = u.clean.codePointAt(0).let(::isIdeograph)
            val split = when {
                !cjk -> true
                i == 0 -> true
                previousEnd != u.start -> true
                !ok -> false
                else -> starts[i] in boundaries
            }
            if (split) group++
            u.group = group
            previousEnd = u.end
        }
    }

    /**
     * The line's display words from its timed units: consecutive units sharing a group become one word, and punctuation
     * or spaces between/after words are attached to the word before them (or the first word when the line starts with
     * punctuation), so joining every word's text gives [text] exactly.
     */
    fun buildWords(units: List<AlignedUnit>, text: String): List<AlignedWord> {
        if (units.isEmpty()) return emptyList()
        val groups = LinkedHashMap<Int, MutableList<AlignedUnit>>()
        for (u in units) groups.getOrPut(u.group) { ArrayList() }.add(u)
        val ordered = groups.values.toList()
        val out = ArrayList<AlignedWord>(ordered.size)
        for ((index, list) in ordered.withIndex()) {
            val first = list.first()
            val last = list.last()
            val start = first.startMs
            val end = list.maxOf { it.endMs }
            // The word takes everything from its first unit to where the next word begins (or the line ends): the comma
            // after it, the space after that. Joining every word's text therefore gives the line back exactly.
            val from = if (index == 0) 0 else first.start
            val to = if (index + 1 < ordered.size) ordered[index + 1].first().start else text.length
            if (from < 0 || to > text.length || from >= to) continue
            val wordText = text.substring(from, to)
            val parts = if (list.size > 1 || (last.end - first.start) > wordText.trimEnd().length) {
                val parts = ArrayList<AlignedPart>(wordText.length)
                var at = from
                for (u in list) {
                    if (u.start > at) parts += AlignedPart(u.startMs, u.startMs, u.start - at)
                    parts += AlignedPart(u.startMs, u.endMs, u.end - u.start)
                    at = u.end
                }
                if (at < to) parts += AlignedPart(end, end, to - at)
                parts.takeIf { p -> p.sumOf { it.length } == wordText.length } ?: emptyList()
            } else emptyList()
            out += AlignedWord(start, end, wordText, parts)
        }
        return out
    }

    /**
     * A hiragana run as one word, with a leading particle (は, が, を, に…) and a trailing sentence particle (ね, よ, か…)
     * taken off when there is enough of the run left to be a word of its own. It's the no-dictionary approximation of
     * what kuromoji does; a dictionary reader, when one is on the phone, gives better words for the same line.
     */
    private fun hiraganaRun(text: String, start: Int, end: Int, out: MutableList<AlignUnit>) {
        var from = start
        var to = end
        if (to - from >= 3 && text[from] in HIRA_START && to - (from + 1) >= 2) {
            out += AlignUnit(text.substring(from, from + 1), from, from + 1); from++
        }
        if (to - from >= 3 && text[to - 1] in HIRA_END && (to - 1) - from >= 2) {
            out += AlignUnit(text.substring(from, to - 1), from, to - 1)
            out += AlignUnit(text.substring(to - 1, to), to - 1, to)
            return
        }
        if (from < to) out += AlignUnit(text.substring(from, to), from, to)
    }

    private const val HIRA_START = "はがをにのとへもやかねよ"
    private const val HIRA_END = "ねよかわなさぞぜ"

    private fun isWordChar(cp: Int) = Character.isLetterOrDigit(cp) || cp == '\''.code || cp == '’'.code
    private fun isLatinWord(cp: Int) = Character.isLetter(cp) && cp < 0x2E80

    internal fun isKana(cp: Int) = (cp in 0x3040..0x30FF) || cp == 0x30FC || cp == 0x3005
    internal fun isHangul(cp: Int) = cp in 0xAC00..0xD7AF || cp in 0x1100..0x11FF || cp in 0x3130..0x318F
    internal fun isIdeograph(cp: Int) = cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xF900..0xFAFF ||
        cp in 0x20000..0x2A6DF || cp in 0x2A700..0x2B73F || cp in 0x2B740..0x2B81F || cp in 0x2B820..0x2CEAF
    private fun isHiragana(cp: Int) = cp in 0x3040..0x309F
    private fun isKatakana(cp: Int) = cp in 0x30A0..0x30FF || cp == 0x30FC
}

/** One unit with its generated time (song ms). */
data class AlignedUnit(
    val clean: String,
    val start: Int,
    val end: Int,
    val group: Int,
    val startMs: Long,
    val endMs: Long,
    val line: Int = -1,
)
