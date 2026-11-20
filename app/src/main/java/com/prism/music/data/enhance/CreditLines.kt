package com.prism.music.data.enhance

import com.prism.music.data.lyrics.LyricLine
import java.text.Normalizer

/**
 * Credit lines at the top (and bottom) of a lyric file — "词：やなぎなぎ", "作詞・作曲：…", "Lyrics by …", "℗ 2024 …" —
 * are not sung, but they are text like any other line, so the aligner is asked to find them in the audio too. They
 * steal its attention from the intro, and their words come back with no time, which is enough on its own to make a
 * whole result read as doubtful. Only the alignment's input is cleaned: the lines stay in the lyrics as they were.
 *
 * Only the edges are examined, and only the shapes a credit line really has: a known label followed by a colon (or
 * "… by"), a rights line, or a bare link. A sung line that merely starts with one of those words ("曲がり角には",
 * "Music to my ears") is left alone, because it has no such shape.
 */
internal object CreditLines {
    /** Label words before a colon. Japanese and Chinese first; the Latin list is matched on whole lowercase words. */
    private val cjkLabels = setOf(
        "作词", "作詞", "作曲", "编曲", "編曲", "词", "詞", "曲", "词曲", "詞曲", "歌", "唄", "歌唱", "演唱", "歌手",
        "演奏", "演奏者", "制作", "製作", "制作人", "製作人", "监制", "監製", "出品", "发行", "發行", "版权", "版權",
        "混音", "母带", "母帶", "录音", "錄音", "配唱", "和声", "和聲", "合唱", "吉他", "贝斯", "鼓", "弦乐", "弦樂",
        "钢琴", "鋼琴", "翻译", "翻譯", "校对", "校對", "时间轴", "時間軸", "策划", "企划", "企劃", "原作", "原曲",
        "出处", "出處", "专辑", "專輯", "音源", "配信", "映像", "动画", "動畫", "主题曲", "主題曲", "片头曲", "片尾曲",
    )

    private val latinLabels = setOf(
        "lyrics", "lyric", "lyricist", "composer", "composed", "written", "words", "music", "arranged", "arranger",
        "arrangement", "producer", "produced", "mixed", "mix", "mastered", "master", "mastering", "vocals", "vocal",
        "performed", "performer", "singer", "artist", "album", "title", "opening", "ending", "theme", "op", "ed",
        "translation", "translated", "romaji", "copyright", "song", "direction", "directed", "illustration",
    )

    /** Labels that go before "by" instead of a colon ("Lyrics by kz", "Produced by …"). */
    private val byLabels = setOf(
        "lyrics", "music", "words", "song", "vocals", "vocal", "performed", "written", "produced", "arranged",
        "composed", "mixed", "mastered", "translated", "translation", "directed",
    )

    /** How many lines at each end are examined at all. */
    private const val EDGE = 6

    /** The lyrics with credit lines at either edge marked as gaps, so no words are made from them. */
    fun strip(lines: List<LyricLine>): List<LyricLine> {
        if (lines.size < 3) return lines
        val from = maxOf(0, lines.size - EDGE)
        val edges = buildList { for (i in 0 until minOf(EDGE, lines.size)) add(i); for (i in lines.size - 1 downTo from) add(i) }
        var out: MutableList<LyricLine>? = null
        for (i in edges) {
            val line = (out ?: lines)[i]
            if (line.isGap || !isCredit(line.text)) continue
            val list = out ?: lines.toMutableList().also { out = it }
            list[i] = line.copy(isGap = true, text = "", words = emptyList(), background = emptyList())
        }
        return out ?: lines
    }

    /** How many of [lines] [strip] would take out. */
    fun count(lines: List<LyricLine>): Int {
        val stripped = strip(lines)
        return if (stripped === lines) 0 else lines.indices.count { stripped[it] !== lines[it] }
    }

    private fun isCredit(raw: String): Boolean {
        // Full-width colons and digits come out as ASCII here, so one set of shapes covers both widths.
        val text = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
        if (text.isEmpty()) return true
        if (text.startsWith("℗") || text.startsWith("©") || text.startsWith("(p)") || text.startsWith("(c)")) return true
        val lower = text.lowercase()
        // A link alone on its line is a credit; a sung line that mentions one is not.
        if (lower.matches(Regex("^(https?://|www\\.)\\S+$"))) return true
        // YouTube's own credit block, common on auto-generated lyric files.
        if (lower.startsWith("provided to youtube")) return true
        // "lyrics by kz", "produced by …": no colon, so it needs its own shape.
        val firstWord = lower.takeWhile { it.isLetter() }
        if (firstWord in byLabels && lower.removePrefix(firstWord).trimStart().startsWith("by ")) return true
        val colon = text.indexOfFirst { it == ':' }
        if (colon <= 0) return false
        val label = text.substring(0, colon).trim()
        if (label.isEmpty()) return false
        // "作詞・作曲: …", "Lyrics & Music: …": every part must be a label before the line counts as credit.
        val parts = label.split('・', '/', '＆', '&', '、', ',', '，', '+', ' ')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        return parts.all { it in cjkLabels || it in latinLabels }
    }
}
