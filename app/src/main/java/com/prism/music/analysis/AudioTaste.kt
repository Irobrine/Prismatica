package com.prism.music.analysis

import kotlin.math.sqrt

/**
 * What the listener's music sounds like to them, as directions in the tagger's embedding space: the long-term taste
 * (songs liked, finished again and again, or queued by hand, against songs skipped again and again) and the short-term
 * one (this sitting: finished against skipped in the last hour). [fit] says how well a song lies along both.
 */
class AudioTaste(private val long: FloatArray?, private val short: FloatArray?) {
    /** -1 … 1 (0 when there's nothing to go by): long-term counts more, this sitting steers. */
    fun fit(embedding: FloatArray?): Double {
        if (embedding == null) return 0.0
        var sum = 0.0; var w = 0.0
        long?.let { sum += 0.6 * Similarity.cosine(embedding, it); w += 0.6 }
        short?.let { sum += 0.4 * Similarity.cosine(embedding, it); w += 0.4 }
        return if (w > 0) sum / w else 0.0
    }

    companion object {
        val NONE = AudioTaste(null, null)

        /**
         * The direction from what's avoided towards what's chosen: the mean of [liked] minus half the mean of [avoided],
         * each centred on the library's mean ([centre]) so that "music" itself (which every YAMNet embedding shares) isn't
         * the taste. Null without at least [MIN] chosen songs.
         */
        fun direction(liked: List<FloatArray>, avoided: List<FloatArray>, centre: FloatArray?): FloatArray? {
            if (liked.size < MIN) return null
            val n = liked.first().size
            val out = FloatArray(n)
            for (e in liked) for (i in 0 until n) out[i] += e[i] / liked.size
            if (avoided.isNotEmpty()) for (e in avoided) for (i in 0 until n) out[i] -= 0.5f * e[i] / avoided.size
            if (centre != null) for (i in 0 until n) out[i] -= centre[i] * (if (avoided.isNotEmpty()) 0.5f else 1f)
            var norm = 0.0
            for (v in out) norm += v * v
            return if (sqrt(norm) < 1e-6) null else out
        }

        fun mean(all: Collection<FloatArray>): FloatArray? {
            if (all.isEmpty()) return null
            val n = all.first().size
            val out = FloatArray(n)
            for (e in all) for (i in 0 until n) out[i] += e[i] / all.size
            return out
        }

        const val MIN = 3
    }
}
