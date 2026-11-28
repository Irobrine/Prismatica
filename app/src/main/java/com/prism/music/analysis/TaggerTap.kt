package com.prism.music.analysis

import com.prism.music.playback.sing.Resampler

/**
 * Keeps the stretches of a song the tagger listens to, as the song scan decodes it: [WINDOWS] windows of
 * [WINDOW_S] seconds spread over the song (8 % … 92 % of it, past intros and fades), each resampled once from the
 * decoder's own rate to the tagger's 16 kHz mono the moment it's complete. Only one window's samples at the decoder's
 * rate are ever held (under 1 MB at 48 kHz); the result is ~2.5 MB of 16 kHz audio.
 *
 * A song whose length isn't known takes a window every 30 s from 20 s in.
 */
class TaggerTap(private val rate: Int, durationMs: Long, private val targetRate: Int = TARGET_RATE) {
    private val starts: LongArray
    private val length = (WINDOW_S * rate).toInt()
    private var frame = 0L
    private var current: FloatArray? = null
    private var filled = 0
    private var next = 0
    private val done = ArrayList<FloatArray>(WINDOWS)

    init {
        starts = if (durationMs > 0) {
            val total = durationMs / 1000.0 * rate
            // A short song gets fewer windows rather than overlapping ones.
            val fit = ((total * 0.84) / length).toInt().coerceIn(1, WINDOWS)
            LongArray(fit) { i ->
                val centre = 0.08 + 0.84 * (i + 0.5) / fit
                (total * centre - length / 2.0).toLong().coerceAtLeast(0)
            }
        } else LongArray(WINDOWS) { ((20 + 30 * it) * rate).toLong() }
    }

    /** One mono sample at the decoder's rate, in order. */
    fun sample(v: Float) {
        val f = frame++
        val buf = current
        if (buf == null) {
            if (next < starts.size && f >= starts[next]) {
                current = FloatArray(length).also { it[0] = v }
                filled = 1
            }
            return
        }
        buf[filled++] = v
        if (filled == length) {
            done += Resampler.convert(buf, 1, rate, targetRate)
            current = null
            next++
            // A window that started late (a format change mid-song) never overlaps the next.
            while (next < starts.size && starts[next] < frame) next++
        }
    }

    /** The windows heard in full, at 16 kHz. */
    fun windows(): List<FloatArray> = done

    companion object {
        const val TARGET_RATE = 16_000
        const val WINDOWS = 8
        /** Ten YAMNet frames (0.96 s, 0.48 s apart) a window. */
        const val WINDOW_S = 4.8
    }
}
