package com.prism.music.playback

/**
 * The persistent card's shuffle buttons, pressed again while the first press is still making its set (Quick picks is a
 * Home page and three radios, seconds of work, and the card stays tappable while it runs). Every finished set started
 * the music afresh, so a burst of taps shuffled through several songs as the sets arrived.
 *
 * A press gets a generation ([start]): the newest one is the only one that plays, so a set that lands after the listener
 * has pressed something else is made and kept, but doesn't start the music. The same button waits [DEFAULT_GAP_MS]
 * after a press, which is the double tap on a set that is already at hand.
 *
 * The service's main thread only (the session's callbacks and its Main-dispatched coroutines).
 */
class PressGate<T> {
    private var generation = 0
    /** The generation each button is still working on, once it has started. */
    private val running = HashMap<T, Int>()
    private val lastAt = HashMap<T, Long>()

    /**
     * This press's generation, or null when it is the same press again: its button is still working on the newest press,
     * or it was pressed within [DEFAULT_GAP_MS] ([now] is a clock in milliseconds that only moves forward).
     */
    fun start(action: T, now: Long): Int? {
        if (running[action] == generation) return null
        val last = lastAt[action]
        if (last != null && now - last < DEFAULT_GAP_MS) return null
        val press = ++generation
        running[action] = press
        lastAt[action] = now
        return press
    }

    /** Something newer is playing (the card's own Play): a set still on its way is no longer the one to start. */
    fun supersede(): Int = ++generation

    /** Whether [press] is still the newest press, and so the one whose set plays. */
    fun isCurrent(press: Int?): Boolean = press == null || press == generation

    /**
     * The press [start] took is done at [now]. Only the press that took this button frees it (a build that was superseded
     * and one that replaced it can be in the air together), and only the newest press starts the double tap's window:
     * a set that finished after the listener had moved on didn't play, so it holds nothing.
     */
    fun finish(action: T, press: Int, now: Long) {
        if (running[action] != press) return
        running.remove(action)
        if (press == generation) lastAt[action] = now
    }

    /** A press that plays something is still on its way. */
    val isRunning: Boolean get() = running.isNotEmpty()

    companion object {
        /** A double tap: long enough to catch the second tap, short enough not to swallow a deliberate second press. */
        const val DEFAULT_GAP_MS = 900L
    }
}
