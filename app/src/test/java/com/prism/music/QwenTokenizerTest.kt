package com.prism.music

import com.prism.music.data.enhance.QwenTokenizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assume
import org.junit.Test
import java.io.File

/** The model's byte-level BPE, against ids the reference tokenizer produced for the same words. */
class QwenTokenizerTest {
    private fun tokenizer(): QwenTokenizer? {
        val dir = System.getenv("QWEN_ALIGNER_DIR")?.let(::File) ?: return null
        if (!File(dir, "vocab.json").isFile || !File(dir, "merges.txt").isFile) return null
        return QwenTokenizer.load(dir)
    }

    @Test fun matchesTheReferenceIds() {
        val tk = tokenizer()
        Assume.assumeTrue("QWEN_ALIGNER_DIR with vocab.json and merges.txt is needed", tk != null)
        val cases = mapOf(
            "The" to intArrayOf(785),
            "quick" to intArrayOf(27763),
            "brown" to intArrayOf(64461),
            "Quilter" to intArrayOf(2183, 2044),
            "今日" to intArrayOf(102242),
            "天気" to intArrayOf(35727, 94121),
            "一緒" to intArrayOf(127709),
            "今天" to intArrayOf(100644),
            "天气" to intArrayOf(104307),
            "一起" to intArrayOf(100018),
            "오늘" to intArrayOf(57268, 127478),
            "날씨가" to intArrayOf(129378, 135562, 19969),
            "노래를" to intArrayOf(127121, 53442, 18411),
            "don't" to intArrayOf(15007, 944),
            "world" to intArrayOf(14615),
        )
        for ((word, expected) in cases) assertArrayEquals(word, expected, tk!!.encode(word))
    }

    @Test fun specialTokensComeFromTheModelFile() {
        val tk = tokenizer()
        Assume.assumeTrue("QWEN_ALIGNER_DIR is needed", tk != null)
        org.junit.Assert.assertEquals(151705, tk!!.timestamp)
        org.junit.Assert.assertEquals(80, tk.timestampSegmentMs)
    }
}
