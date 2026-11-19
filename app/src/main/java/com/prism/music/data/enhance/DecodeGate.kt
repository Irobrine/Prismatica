package com.prism.music.data.enhance

import java.util.concurrent.atomic.AtomicBoolean

/**
 * How far a song's decode has got, for a separator reading that file piece by piece: the first piece waits for its own
 * bytes rather than for the whole song. The reader polls — a couple of milliseconds between looks, on the thread that is
 * about to work anyway — and a failed or abandoned decode is told apart from a file that simply ended.
 */
internal class DecodeGate {
    @Volatile var frames = 0L
    @Volatile var error: Throwable? = null
    @Volatile var done = false
    @Volatile var abandoned = false

    fun publish(f: Long) {
        frames = f
    }

    /**
     * Waits until [need] frames are on disk. Returns early when the decode finished (the file is shorter than the length
     * the song declared), throws when it failed or the job was stopped.
     */
    fun await(need: Long, cancel: AtomicBoolean) {
        while (frames < need) {
            error?.let { throw it }
            if (abandoned || cancel.get()) throw SeparationCancelled()
            if (done) return
            try {
                Thread.sleep(2)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw SeparationCancelled()
            }
        }
    }
}
