package com.prism.music.analysis

/**
 * Orders a run of songs as a whole rather than one best pick at a time: a beam search over the candidates that keeps
 * the [BEAM] best partial runs at each step and scores a run by
 *
 *   Σ relevance − transition cost (tempo, key, loudness, brightness, timbre jumps) − repetition (the same artist within
 *   [ARTIST_GAP] songs, the same album within [ALBUM_GAP]) − redundancy (MMR: too like a song already in the run)
 *   + discovery (songs the listener hasn't heard, by [Candidate.fresh]).
 *
 * [Mode.SLEEP] (the sleep timer with a score limit) adds the song's sleep score and penalizes a rise in energy, so a
 * bedtime queue only ever winds down. Songs without analysis take neutral transitions: the run still keeps its
 * relevance order and artist spacing.
 *
 * Only ever orders what autoplay adds; the listener's own queue is never touched.
 */
object QueueSequencer {
    enum class Mode { NORMAL, SLEEP }

    class Candidate(
        val id: String,
        /** 0 … 1: how well it follows the seeds and suits the listener (the caller's ranking). */
        val relevance: Double,
        val artist: String,
        val album: String?,
        val sound: SoundEntry?,
        /** 0 … 1: how new to the listener (0 played often, 1 never). */
        val fresh: Double = 0.0,
    )

    class Weights(
        val transition: Double = 0.35,
        val redundancy: Double = 0.25,
        val artist: Double = 0.8,
        val album: Double = 0.4,
        val discovery: Double = 0.1,
        val sleep: Double = 0.5,
    )

    const val BEAM = 6
    const val BRANCH = 10
    const val ARTIST_GAP = 3
    const val ALBUM_GAP = 5

    private class Run(val items: List<Candidate>, val score: Double)

    /**
     * Up to [count] of [candidates] in the order to play them after [before] (the queue's last songs, oldest first,
     * whose artists and sound the run continues from).
     */
    fun order(
        candidates: List<Candidate>,
        count: Int,
        similarity: Similarity,
        before: List<Candidate> = emptyList(),
        mode: Mode = Mode.NORMAL,
        w: Weights = Weights(),
    ): List<Candidate> {
        if (candidates.isEmpty() || count <= 0) return emptyList()
        val pool = candidates.distinctBy { it.id }.sortedByDescending { it.relevance }.take(maxOf(count * 3, 30))
        var beam = listOf(Run(emptyList(), 0.0))
        val steps = minOf(count, pool.size)
        repeat(steps) {
            val next = ArrayList<Run>()
            for (run in beam) {
                val used = run.items.mapTo(HashSet()) { it.id }
                val context = before + run.items
                pool.asSequence().filter { it.id !in used }
                    .map { c -> c to gain(c, context, run.items, similarity, mode, w) }
                    .sortedByDescending { it.second }
                    .take(BRANCH)
                    .forEach { (c, g) -> next += Run(run.items + c, run.score + g) }
            }
            if (next.isEmpty()) return beam.first().items
            beam = next.sortedByDescending { it.score }.distinctBy { r -> r.items.map { it.id }.sorted() }.take(BEAM)
        }
        return beam.first().items
    }

    /** What adding [c] after [context] (with [run] the part chosen here) is worth. */
    internal fun gain(c: Candidate, context: List<Candidate>, run: List<Candidate>, similarity: Similarity, mode: Mode, w: Weights): Double {
        var g = c.relevance + w.discovery * c.fresh
        val last = context.lastOrNull()
        if (last != null) {
            val a = last.sound; val b = c.sound
            g -= w.transition * (if (a != null && b != null) similarity.transition(a, b) else 0.5)
        }
        // The same artist within a few songs, the same album within a few more.
        val artist = c.artist.lowercase()
        if (artist.isNotBlank()) {
            // How many songs back the artist last played (0: the one just before), within the gap: nearer costs more.
            val recent = context.takeLast(ARTIST_GAP)
            val at = recent.indexOfLast { it.artist.equals(artist, ignoreCase = true) }
            if (at >= 0) g -= w.artist * (ARTIST_GAP - (recent.size - 1 - at)).toDouble() / ARTIST_GAP
        }
        val album = c.album?.lowercase()
        if (!album.isNullOrBlank() && context.takeLast(ALBUM_GAP).any { it.album?.lowercase() == album }) g -= w.album
        // Redundancy: too like what this run already holds.
        val s = c.sound
        if (s != null && run.isNotEmpty()) {
            val most = run.mapNotNull { it.sound }.maxOfOrNull { similarity.between(it, s) } ?: 0.0
            g -= w.redundancy * ((most - 0.75) / 0.25).coerceIn(0.0, 1.0)
        }
        if (mode == Mode.SLEEP) {
            g += w.sleep * ((s?.sleepScore ?: 40) / 100.0)
            // Winding down: louder or busier than the song before costs.
            val prev = last?.sound?.features
            val now = s?.features
            if (prev != null && now != null) {
                val louder = ((now.lufs ?: -20f) - (prev.lufs ?: -20f)).coerceAtLeast(0f) / 6.0
                val busier = (now.onsetRate - prev.onsetRate).coerceAtLeast(0f) / 2.0
                g -= w.sleep * (louder + busier).coerceAtMost(1.0)
            }
        }
        return g
    }
}
