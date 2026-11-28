package com.prism.music.analysis

import com.prism.music.AppContainer
import com.prism.music.data.meta.Genres
import com.prism.music.data.model.Song
import com.prism.music.data.songsByIds

/**
 * Every analysed song as CSV, for tuning the score on a real library: each song's score and base, how complete it was,
 * the listener's vote, every factor's calmness and weight (which sum to the score), and the figures behind them, plus its
 * tags and a few flags for the cases most likely to be wrong (an energetic-looking song scoring calm). Nothing is shared
 * by itself: Settings' "Export sleep scores" writes the file and hands it to the share sheet.
 */
object SleepScoresExport {
    const val HEADER = "id,title,artist,score,base,completeness,capped,vote,lufs,lra,peak,truePeak,bpm,pulse,steady,onsets," +
        "percussive,dance,flatness,brightShare,centroid,rolloff,lowRatio,sudden,worstRise,lraSegments,key,catalogGenre,audioGenre," +
        "tags,flags,factors"

    /**
     * The CSV. [analysis] is read as it is (the listener's weights, votes and feedback included), names from the songs
     * table or the phone's own music list, so a file only ever analysed is named too.
     */
    suspend fun csv(c: AppContainer): String {
        c.analysis.load()
        val entries = c.analysis.all()
        val names = if (entries.isEmpty()) emptyMap() else c.songsByIds(entries.map { it.id })
        // The catalog's genre (a Deezer/iTunes answer, or the listener's edit), to compare with what the audio sounds like:
        // a wrong label ("classical" on a metal track) stays out of the score but is worth seeing. Cached answers only.
        val catalog = runCatching { c.meta.cachedGenres(names.values.toList()) }.getOrDefault(emptyMap())
        val rows = entries.mapNotNull { e ->
            val result = c.analysis.sleepResult(e.id) ?: return@mapNotNull null
            Triple(e, names[e.id] as Song?, result)
        }.sortedWith(compareByDescending<Triple<SoundEntry, Song?, SleepResult>> { it.third.score }.thenBy { it.second?.title.orEmpty() })
        val out = StringBuilder(HEADER.length + rows.size * 420)
        out.append(HEADER).append('\n')
        for ((e, song, r) in rows) {
            val f = e.features
            val tagRows = runCatching { c.db.analysis().tags(e.id) }.getOrDefault(emptyList())
                .filter { it.status != TagStatus.REJECTED.ordinal }
            val tags = tagRows.sortedByDescending { it.confidence }.joinToString(";") { "${it.tag}:${"%.2f".format(it.confidence)}" }
            val audioGenre = tagRows
                .filter { it.category == TagCategory.GENRE.name && it.status != TagStatus.REVIEW.ordinal }
                .maxByOrNull { it.confidence }?.tag?.removePrefix("genre:")
            val catalogGenre = Genres.normalize(c.metadata.genreOf(e.id)) ?: c.metadata.genreOf(e.id) ?: catalog[e.id]
            fun factor(p: FactorPart) = "${p.factor.name} ${"%.2f".format(p.calm)}/${"%.2f".format(p.weight)}"
            val cells = listOf(
                e.id, song?.title, song?.artistText, r.score.toString(), r.base.toString(),
                "%.2f".format(r.completeness), r.capped.toString(), r.vote.toString(),
                num(f.lufs), num(f.lra), num(f.peakDb), num(f.truePeakDb), num(f.bpm), "%.2f".format(f.pulseClarity),
                f.tempoStability?.let { "%.2f".format(it) } ?: "", "%.1f".format(f.onsetRate), "%.2f".format(f.percussive),
                "%.2f".format(f.danceability), "%.3f".format(f.flatness), "%.3f".format(f.brightShare),
                "%.0f".format(f.centroidHz), "%.0f".format(f.rolloffHz), "%.3f".format(f.lowRatio),
                "%.1f".format(f.suddenPerMinute), "%.0f".format(f.worstRise), f.segLoud.size.toString(),
                e.key?.let { "${it.name} (${it.camelot})" } ?: "", catalogGenre ?: "", audioGenre ?: "",
                tags, genreFlag(catalogGenre, audioGenre) + flags(r, f),
                r.parts.filter { it.weight > 0f }.joinToString(";") { factor(it) },
            )
            out.append(cells.joinToString(",") { quote(it) }).append('\n')
        }
        return out.toString()
    }

    /**
     * What looks off, by rule, to find songs the weights misread: a song that scores calm while its sound says otherwise.
     * Empty when nothing looks wrong. Not used by the score; only by this report.
     */
    fun flags(r: SleepResult, f: SongFeatures): String {
        val out = ArrayList<String>()
        if (r.score >= 55) {
            if (f.flatness >= 0.06f) out += "harsh"
            if (f.percussive >= 0.5f) out += "percussive"
            if (f.onsetRate >= 4f) out += "busy"
            if (f.suddenPerMinute >= 0.5f) out += "sudden"
            if (f.worstRise >= 6f) out += "rise"
            if (f.pulseClarity >= 0.6f && (f.bpm ?: 0f) >= 110f) out += "fast-beat"
        }
        if (r.score < 30 && r.completeness >= 0.9f && f.onsetRate < 1f && f.percussive < 0.3f && f.lufs != null && f.lufs <= -18f) {
            out += "calm-but-low"
        }
        return out.joinToString(";")
    }

    private fun num(x: Float?): String = if (x == null || x.isNaN()) "" else "%.1f".format(x)

    /**
     * "genre-mismatch;" when the catalog's genre and the audio's own disagree (both known, and not the same category): a
     * wrong label in the catalog — a metal track filed as classical — is worth reviewing, and mustn't reach the score.
     */
    fun genreFlag(catalog: String?, audio: String?): String =
        if (catalog != null && audio != null && !catalog.equals(audio, ignoreCase = true)) "genre-mismatch;" else ""

    /** A CSV cell: quoted when it holds a comma, a quote or a newline. */
    internal fun quote(v: String?): String {
        val s = v ?: return ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
