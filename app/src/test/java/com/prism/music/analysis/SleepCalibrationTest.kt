package com.prism.music.analysis

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The score against a listener's own "Export sleep scores" file (the folder-free path SLEEP_CSV names): each row's
 * figures and tags are read back into [SongFeatures] and a tag-probability array, scored again with the current code, and
 * printed beside the score in the file and the listener's High/Low mark. A tuning aid, not a check: it's skipped unless
 * the variable is set, and the file stays on the listener's machine (it names their songs).
 *
 * What the file can't carry (the per-segment curves, the loudest 400 ms, the full tag probabilities) is left out, so the
 * scores here are close to, not equal to, the phone's; what matters is how the marked songs move against the rest.
 */
class SleepCalibrationTest {
    @Test fun againstAnExport() {
        val file = System.getenv("SLEEP_CSV")?.let(::File)
        assumeTrue("SLEEP_CSV not set", file != null && file.isFile)
        val lines = file!!.readLines().filter { it.isNotBlank() }
        val head = cells(lines.first())
        fun col(row: List<String>, name: String) = row.getOrNull(head.indexOf(name)).orEmpty()
        fun num(row: List<String>, name: String) = col(row, name).toFloatOrNull()
        data class Row(val title: String, val old: Int, val now: Int, val mark: String, val detail: String)
        val rows = lines.drop(1).map { line ->
            val r = cells(line)
            val f = SongFeatures(
                seconds = 200f, lufs = num(r, "lufs"), lra = num(r, "lra"), peakDb = num(r, "peak"), truePeakDb = num(r, "truePeak"),
                suddenPerMinute = num(r, "sudden") ?: 0f, worstRise = num(r, "worstRise") ?: 0f, bpm = num(r, "bpm"),
                pulseClarity = num(r, "pulse") ?: 0f, tempoStability = num(r, "steady"), onsetRate = num(r, "onsets") ?: 0f,
                percussive = num(r, "percussive") ?: 0f, danceability = num(r, "dance") ?: 0f, flatness = num(r, "flatness") ?: 0f,
                brightShare = num(r, "brightShare") ?: 0f, centroidHz = num(r, "centroid") ?: 0f, rolloffHz = num(r, "rolloff") ?: 0f,
                lowRatio = num(r, "lowRatio") ?: 0f,
            )
            // The tags back into class probabilities: each tag's classes at its confidence (the tagger's own max).
            val scores = FloatArray(YamnetTagger.CLASSES)
            for (t in col(r, "tags").split(';').filter { it.isNotBlank() }) {
                val key = t.substringBeforeLast(':')
                val p = t.substringAfterLast(':').toFloatOrNull() ?: continue
                TagVocabulary.def(key)?.classes?.forEach { c -> if (c in scores.indices) scores[c] = maxOf(scores[c], p) }
            }
            val s = SleepScore.of(f, scores.takeIf { a -> a.any { it > 0f } } ?: FloatArray(YamnetTagger.CLASSES) { 0.03f }, SleepWeights(vocalsCount = false))
            Row(col(r, "title"), col(r, "score").toInt(), s.score, col(r, "scoreMark"),
                s.parts.filter { it.weight > 0f }.joinToString(" ") { "${it.factor.name.take(4)}${"%.2f".format(it.calm)}" })
        }
        fun pct(list: List<Int>, v: Int) = list.count { it < v } / (list.size - 1f).coerceAtLeast(1f)
        val olds = rows.map { it.old }
        val nows = rows.map { it.now }
        println("old  now  move  mark  title  |  factors")
        for (r in rows.sortedByDescending { it.now }) {
            val move = pct(nows, r.now) - pct(olds, r.old)
            println("%3d  %3d  %+5.2f  %-4s  %s  |  %s".format(r.old, r.now, move, r.mark, r.title.take(26), r.detail))
        }
        for (m in listOf("low", "high")) {
            val marked = rows.filter { it.mark == m }
            val moves = marked.map { pct(nows, it.now) - pct(olds, it.old) }
            val rest = rows.filter { it.mark != m }.map { it.now - it.old }.average()
            println("marked %-4s: %d songs, mean percentile move %+.2f (%d moved the right way), score %+.1f against the rest's %+.1f".format(
                m, marked.size, moves.average(), moves.count { if (m == "low") it > 0 else it < 0 }, marked.map { it.now - it.old }.average(), rest))
        }
    }

    /** A CSV line's cells: quoted cells may hold commas and doubled quotes. */
    private fun cells(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }
}
