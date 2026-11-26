package com.prism.music.playback

import android.media.AudioDeviceInfo
import androidx.media3.common.Player
import com.prism.music.data.addressOrBlank
import com.prism.music.data.isBluetooth

/**
 * Picks the music up when the output it was playing on comes back — a TWS pair switching to its own 2.4 GHz connection
 * and back drops the Bluetooth link, which pauses playback (Media3's becoming-noisy pause), and connecting it again used
 * to leave the music paused.
 *
 * Only a pause the lost output caused is remembered. The same Bluetooth output (or, where the phone gives no address,
 * one of the same name) returns it within [windowMs]; so does the earbuds' own 2.4 GHz dongle, which the phone reports as
 * a USB audio output, within the first [SWITCH_WINDOW_MS]. A pause the listener made, a call, the end of the queue or the
 * sleep timer cancels the wait at once, so nothing plays again by itself after those.
 */
internal class OutputReturn(
    private val windowMs: Long = RETURN_WINDOW_MS,
    private val graceMs: Long = REMOVAL_GRACE_MS,
) {
    /** What a device is, across a reconnect (and across A2DP and LE Audio, which can be separate entries). */
    class Sink(val type: Int, val address: String, val name: String) {
        val bluetooth: Boolean get() = isBluetooth(type)
        /**
         * A 2.4 GHz dongle: the earbuds' own receiver, which the phone sees as a USB audio output rather than another
         * Bluetooth entry.
         */
        val dongle: Boolean get() = type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    private class Waiting(val sink: Sink, val at: Long)

    /** The output last seen (or picked) while there was a player; Bluetooth or not. */
    private var lastKnown: Sink? = null
    /** The last Bluetooth output the phone said went away, and when. */
    private var lastRemoved: Sink? = null
    private var lastRemovedAt = 0L
    /** When a becoming-noisy pause came in: a removal arriving just after it still belongs to that pause. */
    private var noisyAt = 0L
    private var waiting: Waiting? = null

    /** Whether a becoming-noisy pause is still waiting for its output to come back. */
    val waitingFor: Boolean get() = waiting != null

    /** The output playing now (called when playback starts, when outputs come and go, and when one is picked). */
    fun current(sink: Sink?) {
        if (sink != null) lastKnown = sink
    }

    /** Outputs the phone says went away. A Bluetooth one next to a becoming-noisy pause is what it was playing on. */
    fun removed(sinks: List<Sink>, now: Long) {
        val bt = sinks.firstOrNull { it.bluetooth } ?: return
        lastRemoved = bt
        lastRemovedAt = now
        // The pause can arrive before the device list (the broadcast first): arm it with this output now. Once one is
        // waiting, another entry going (ear detection, a case, an LE twin) must not take its place.
        if (waiting == null && noisyAt != 0L && now - noisyAt <= graceMs) waiting = Waiting(bt, noisyAt)
    }

    /**
     * A pause. Only one that says the output became noisy starts the wait; any other (the listener's own, a call, the
     * end of the queue, the sleep timer) cancels it.
     */
    fun paused(reason: Int, now: Long) {
        if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
            forget()
            return
        }
        // The output last playing, if it was Bluetooth, is what the pause was about; else a removal just before it (the
        // route may still have read as the phone until Bluetooth audio started).
        val recent = lastRemoved?.takeIf { now - lastRemovedAt <= graceMs }
        val known = lastKnown
        // The sound was on a cable or another output, not on Bluetooth: a Bluetooth entry going just before or after the
        // pause (an earbud's case or ear detection) can't be what paused it. The phone's own speaker is left permissive:
        // the route reads as the speaker until Bluetooth audio actually starts.
        val onCable = known != null && !known.bluetooth && known.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        val sink = if (onCable) null else known?.takeIf { it.bluetooth } ?: recent
        waiting = sink?.let { Waiting(it, now) }
        noisyAt = if (onCable) 0L else now
    }

    /** Playback started (the listener's Play, a call ending, the persistent card): nothing is waiting any more. */
    fun played() = forget()

    /**
     * Whether [sinks] hold the output a becoming-noisy pause lost, within the window: the same pair, or the earbuds' 2.4
     * GHz dongle (a USB output) soon after it. The wait is used up either way; one that ran out is dropped.
     */
    fun returned(sinks: List<Sink>, now: Long): Boolean {
        val w = waiting ?: return false
        val since = now - w.at
        if (since > windowMs) {
            forget()
            return false
        }
        val same = sinks.any { it.sameAs(w.sink) }
        val switched = since <= SWITCH_WINDOW_MS && sinks.any { it.dongle }
        if (!same && !switched) return false
        lastRemoved = null
        waiting = null
        noisyAt = 0L
        return true
    }

    /** Whether a becoming-noisy pause is waiting and its window hasn't run out yet. */
    fun waiting(now: Long): Boolean = waiting?.let { now - it.at <= windowMs } ?: false

    /**
     * [returned] for a look at the outputs connected now, not an add event: the lost output only counts as back if the
     * phone was seen taking it away first (just before the pause, or since). Straight after the pause the Bluetooth
     * entry is often still listed for a second, and taking that for its return played the music on the speaker.
     */
    fun returnedAlready(sinks: List<Sink>, now: Long): Boolean {
        val w = waiting ?: return false
        val gone = lastRemoved ?: return false
        if (!gone.sameAs(w.sink) || lastRemovedAt < w.at - graceMs) return false
        return returned(sinks, now)
    }

    private fun forget() {
        waiting = null
        noisyAt = 0L
    }

    private companion object {
        /** How long after the output went the same one may come back and pick the music up. */
        const val RETURN_WINDOW_MS = 120_000L
        /** The first moments after the loss, when the earbuds' 2.4 GHz dongle (a USB output) is the pair switching. */
        const val SWITCH_WINDOW_MS = 15_000L
        /** A removal this soon before (or after) the pause is the output that pause was about. */
        const val REMOVAL_GRACE_MS = 5_000L
    }
}

/** The phone's own description of [this], for [OutputReturn]. */
internal fun AudioDeviceInfo.toSink(): OutputReturn.Sink =
    OutputReturn.Sink(type, addressOrBlank(), productName?.toString().orEmpty())

/** Whether two device entries are the same pair across a reconnect (and across A2DP and LE Audio). */
private fun OutputReturn.Sink.sameAs(other: OutputReturn.Sink): Boolean {
    if (address.isNotBlank() && other.address.isNotBlank()) return address.equals(other.address, ignoreCase = true)
    if (name.isNotBlank() && other.name.isNotBlank()) return name.equals(other.name, ignoreCase = true)
    return type == other.type
}
