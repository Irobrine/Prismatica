package com.prism.music.data.dedup

import kotlin.math.max

/**
 * Compares two [AudioFingerprint]s entirely on the phone.
 *
 * Matching is a vote over time offsets: for every constellation landmark the two recordings share, the difference
 * between the landmark's times is a vote for one alignment. The alignment with the most votes is the match. This
 * gives the two properties the feature needs:
 *  - it is start-independent (a video's intro or a song's outro only add unmatched landmarks, they never shift the
 *    votes of the part the two recordings share), so partial matches work without comparing whole files;
 *  - it is specific to a recording (a live performance, a remix, a cover or a different master has a different set of
 *    spectral landmarks, so it produces few votes at any one offset).
 *
 * The thresholds are expressed in both absolute votes and in the fraction of the smaller fingerprint they represent.
 * The fraction matters because a long song has far more landmarks than a short clip: a fixed 80% "similarity" would
 * be meaningless here. A high score is grouped automatically; a borderline one is only offered for confirmation.
 */
object Matcher {
    enum class Level { NONE, BORDERLINE, HIGH }

    data class Match(
        /** Landmarks voting for the best alignment. */
        val votes: Int,
        /** [votes] over the smaller fingerprint's landmark count, 0…1. */
        val ratio: Double,
        /** Frames by which [b] starts after [a] at the best alignment (negative when [b] starts earlier). */
        val offsetFrames: Int,
        val level: Level,
    )

    /**
     * A match worth grouping without asking: enough independent votes, and enough of the shorter recording. The vote
     * floor stops a couple of shared tones from qualifying; the fraction stops a long, loosely related recording from
     * qualifying just because it has many landmarks. Both are needed, calibrated against real peaks (a re-encoded copy
     * scores ~0.5; unrelated material with a few coincident tones stays below 0.03).
     */
    const val HIGH_MIN_VOTES = 30
    const val HIGH_MIN_RATIO = 0.08

    /** A match worth showing for a person to confirm. Below this it is treated as unrelated. */
    const val BORDERLINE_MIN_VOTES = 12
    const val BORDERLINE_MIN_RATIO = 0.03

    /**
     * Two recordings of the same length and master score 1.0 by construction here; [ratio] is deliberately far below
     * 1 because a landmark has to match both its frequencies and its time gap, and encodes/mastering move some peaks.
     */
    fun compare(a: AudioFingerprint, b: AudioFingerprint): Match {
        if (a.landmarks.isEmpty() || b.landmarks.isEmpty()) return Match(0, 0.0, 0, Level.NONE)
        // Inverted index of the second fingerprint: hash -> the frames at which that landmark starts.
        val at = HashMap<Int, IntArray>()
        for (word in b.landmarks) {
            val h = Landmarks.hash(word)
            val old = at[h]
            if (old == null) at[h] = intArrayOf(Landmarks.time(word))
            else {
                val grown = old.copyOf(old.size + 1)
                grown[old.size] = Landmarks.time(word)
                at[h] = grown
            }
        }
        val votesByOffset = HashMap<Int, Int>()
        for (word in a.landmarks) {
            val times = at[Landmarks.hash(word)] ?: continue
            val mine = Landmarks.time(word)
            for (t in times) {
                val offset = t - mine
                votesByOffset[offset] = (votesByOffset[offset] ?: 0) + 1
            }
        }
        var bestOffset = 0
        var best = 0
        for ((offset, votes) in votesByOffset) if (votes > best) { best = votes; bestOffset = offset }
        val smaller = max(1, minOf(a.size, b.size))
        val ratio = best.toDouble() / smaller
        return Match(best, ratio, bestOffset, level(best, ratio))
    }

    fun level(votes: Int, ratio: Double): Level = when {
        votes >= HIGH_MIN_VOTES && ratio >= HIGH_MIN_RATIO -> Level.HIGH
        votes >= BORDERLINE_MIN_VOTES && ratio >= BORDERLINE_MIN_RATIO -> Level.BORDERLINE
        else -> Level.NONE
    }

    /** Convenience for the grouping layer, which only auto-groups high-confidence pairs. */
    fun high(a: AudioFingerprint, b: AudioFingerprint): Boolean = compare(a, b).level == Level.HIGH
}
