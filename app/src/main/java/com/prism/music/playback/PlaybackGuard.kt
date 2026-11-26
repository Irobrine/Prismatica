package com.prism.music.playback

import android.content.Context
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.media3.common.Player

/**
 * Settings → Playback → "Play only when it can be heard": a play request is held back while nothing would be heard —
 * the phone's media volume is 0, no audio output is connected, or the output that was playing just went away (Media3's
 * becoming-noisy pause, as when headphones are unplugged) — and starts by itself once sound is possible again: the
 * volume comes back up, an output is connected, or a headset is plugged in again.
 *
 * It works with "Pause when muted" rather than on top of it: that setting keeps pausing and resuming music that is
 * already playing, while this one owns the start of a play request. With both on, a volume drop during playback is the
 * mute watch's, and a play pressed at volume 0 is this guard's.
 *
 * A becoming-noisy pause (ACTION_AUDIO_BECOMING_NOISY, which Media3 reports as the pause's reason) only starts the music
 * again through a headset or Bluetooth output (or the same output coming back), never the phone's speaker: the speaker
 * was not where the music was, and playing there after an unplug is the surprise this setting is for. A pair switching
 * to its own 2.4 GHz dongle (a USB output to the phone) is waited for the same way: a play waits for any outside
 * headset/Bluetooth/USB output while the one that was playing is gone.
 */
internal class PlaybackGuard(
    private val watcher: Watcher,
    private val settingOn: () -> Boolean,
    private val muteHandlesVolume: () -> Boolean,
    private val controls: Controls,
    private val log: (String) -> Unit = { AudioDiagnostics.log("state", it) },
    private val memory: Memory = Memory.None,
) : Player.Listener {

    /** Why playback is waiting, or null when it isn't. */
    enum class Hold { VOLUME, OUTPUT, NOISY, LOST }

    /** What [PlaybackGuard] needs of the player and the service. */
    interface Controls {
        /** Nothing would be heard: the phone's media volume is 0 (or Prism's own volume is, when that's what plays). */
        val silent: Boolean
        /** Any music output is connected right now (the phone's own speaker counts). */
        val hasOutput: Boolean
        /** The route carrying media right now (null if unknown): the speaker during a switch, even if the new pair is listed. */
        val routedOutput: OutputReturn.Sink?
        /** "Resume when Bluetooth comes back" is waiting for the output that went: a play must not go to the speaker now. */
        val awaitingReturn: Boolean
        /** The listener picked the phone's own speaker in Prism's device sheet: a lost headset mustn't hold it. */
        val speakerPicked: Boolean
        /** Not now: casting, the car (Android Auto), or a call. */
        val blocked: Boolean
        val playWhenReady: Boolean
        fun pause()
        fun play()
    }

    /** Volume and output changes, or a fake one in tests. */
    interface Watcher {
        /** Starts watching; the outputs connected now arrive through [devices] at once. */
        fun start(
            onVolume: () -> Unit,
            devices: (added: List<OutputReturn.Sink>, removed: List<OutputReturn.Sink>) -> Unit,
        )
        fun stop()
    }

    /**
     * The outside output music was last playing on, kept across a service restart (a swipe away, a phone reclaiming the
     * service) so a pair that went to its 2.4 GHz dongle still holds playback on the phone. Expired by the real one.
     */
    interface Memory {
        fun lastHeadset(): OutputReturn.Sink?
        fun rememberHeadset(sink: OutputReturn.Sink?)

        companion object {
            val None: Memory = object : Memory {
                override fun lastHeadset(): OutputReturn.Sink? = null
                override fun rememberHeadset(sink: OutputReturn.Sink?) {}
            }
        }
    }

    private var watching = false
    private var released = false
    private var hold: Hold? = null
    /** The listener wants music: set by their play, dropped by their pause or the end of the queue. */
    private var wanted = false
    /** The playWhenReady our own pause or play is about to report, so it isn't taken for the listener's. */
    private var expect: Boolean? = null
    /** The player's playWhenReady as last reported, so a reason-only change to a pause is told from a pause. */
    private var lastPlayWhenReady = false
    /** The last outside output (headphones, earbuds, USB) seen connected: gone, a play waits for another, not the speaker. */
    private var lastHeadset: OutputReturn.Sink? = null
    /** The last outside output seen leaving: the same one coming back is the music's route again (see [check]). */
    private var lastOutsideRemoved: OutputReturn.Sink? = null

    /** Why playback is held, for tests and diagnostics. */
    val holding: Hold? get() = hold

    /** Starts or stops watching to match the setting. Call on the main thread when it changes. */
    fun update() {
        if (released) return
        lastPlayWhenReady = controls.playWhenReady
        if (controls.playWhenReady) wanted = true
        val want = settingOn()
        if (want != watching) {
            watching = want
            android.util.Log.i("PrismControl", "play guard ${if (want) "on" else "off"}")
            if (want) {
                // What the last service was playing on: if it's gone (the pair switched to 2.4 GHz), a play waits.
                if (lastHeadset == null) memory.lastHeadset()?.let {
                    lastHeadset = it
                    android.util.Log.i("PrismControl", "guard remembered the last headset: ${describe(it)}")
                }
                watcher.start(::onVolume, ::onDevices)
            } else {
                watcher.stop()
                lastHeadset = null
                memory.rememberHeadset(null)
                // Switched off: a play held for the headphones (or the volume) goes through, as it would without the guard.
                if (hold != null) releaseHold(force = true)
                return
            }
        }
        if (want) onVolume()
    }

    fun release() {
        released = true
        if (watching) watcher.stop()
        watching = false
        hold = null
        wanted = false
        lastOutsideRemoved = null
        expect = null
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (released) return
        val wasPlaying = lastPlayWhenReady
        lastPlayWhenReady = playWhenReady
        if (expect == playWhenReady) {
            expect = null
            return
        }
        // Already paused, only the reason is new: the persistent card's silent track taking audio focus while a play
        // waits here (which used to drop the wait, so nothing started when sound came back), or headphones going after
        // the listener's own pause (which used to start a wait, so the music came back with them). Neither changes
        // what the listener asked for.
        if (!playWhenReady && !wasPlaying) return
        if (playWhenReady) {
            wanted = true
            if (!settingOn() || controls.blocked) {
                hold = null
                return
            }
            val block = blockNow()
            if (block != null) holdNow(block) else {
                hold = null
                android.util.Log.i(
                    "PrismControl",
                    "play allowed: routed ${describe(controls.routedOutput)}, silent ${controls.silent}, " +
                        "last headset ${describe(lastHeadset)}, awaiting resume ${controls.awaitingReturn}",
                )
            }
            return
        }
        // Media3's own becoming-noisy pause (ACTION_AUDIO_BECOMING_NOISY): the output went away while the music played.
        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY && settingOn() && !controls.blocked) {
            wanted = true
            hold = Hold.NOISY
            log("paused: the output went away (waiting for a headset or Bluetooth output)")
            return
        }
        // Anything else (the listener's pause, the end of the queue, the sleep timer) ends the request.
        wanted = false
        hold = null
    }

    private fun blockNow(): Hold? = when {
        controls.silent -> Hold.VOLUME
        !controls.hasOutput -> Hold.OUTPUT
        // The route is the phone's own speaker, and either the headphones that were playing are gone or Resume is
        // still waiting for the Bluetooth output that went: nothing plays out loud in that gap. An outside output
        // (the dongle, a pair back, another headset) is allowed: that's where the music can be heard.
        routedIsPhone() && (controls.awaitingReturn || (lastHeadset != null && !controls.speakerPicked)) -> Hold.LOST
        else -> null
    }

    private fun routedIsPhone(): Boolean = controls.routedOutput?.phoneOutput ?: true

    private fun holdNow(reason: Hold) {
        hold = reason
        val why = when (reason) {
            Hold.VOLUME -> "the phone's media volume is 0 (waiting for it)"
            Hold.OUTPUT -> "no audio output is connected (waiting for one)"
            Hold.NOISY -> "the output went away (waiting for a headset or Bluetooth output)"
            Hold.LOST -> "the headphones that were playing are gone (waiting for them)"
        }
        android.util.Log.i("PrismControl", "play held: $why")
        if (!controls.playWhenReady) return
        expect = false
        controls.pause()
        log("paused: $why")
    }

    /** Lets a held request start, unless something else blocks it still. [force]: the guard is being switched off. */
    private fun releaseHold(force: Boolean = false) {
        hold = null
        if (!wanted || controls.blocked) return
        val block = if (force) null else blockNow()
        if (block != null) {
            hold = block
            return
        }
        if (controls.playWhenReady) return
        expect = true
        controls.play()
        lastOutsideRemoved = null
        android.util.Log.i("PrismControl", "held play released: sound is possible again")
        log("playing on: it can be heard again")
    }

    /**
     * Re-checks a held play: the route can change without a device being added (the earbuds' 2.4 GHz dongle is often
     * already connected when they switch to it), so the service asks once a second while something is held.
     */
    fun check() {
        if (released || !settingOn()) return
        val held = hold ?: return
        val lost = lastHeadset
        val removed = lastOutsideRemoved
        val back = when (held) {
            Hold.VOLUME -> !controls.silent
            Hold.OUTPUT -> controls.hasOutput
            // The route is an outside output again. The same pair is allowed once its entry was seen leaving (back on
            // Bluetooth after a 2.4 GHz switch) or once Resume has stopped waiting for it; a route still naming a pair
            // that never left is the old, dead one.
            Hold.NOISY, Hold.LOST -> controls.routedOutput.let { routed ->
                routed != null && !routed.phoneOutput &&
                    (lost == null || !routed.sameAs(lost) || removed?.sameAs(routed) == true || !controls.awaitingReturn)
            }
        }
        if (back) releaseHold()
    }

    private fun onVolume() {
        if (released || !settingOn()) return
        if (!controls.silent) {
            if (hold == Hold.VOLUME) releaseHold()
            return
        }
        // With "Pause when muted" on, the mute watch owns pausing and resuming music that's already playing.
        if (muteHandlesVolume()) return
        if (controls.playWhenReady) holdNow(Hold.VOLUME)
    }

    private fun onDevices(added: List<OutputReturn.Sink>, removed: List<OutputReturn.Sink>) {
        if (released) return
        // Only outputs music plays through: earbuds connecting add their call channel (Bluetooth SCO) a moment before
        // their music one, and that alone isn't the headphones back.
        val music = added.filter { com.prism.music.data.isMusicOutput(it.type) }
        // What the music was playing on, remembered even while nothing is held: once it's gone, a play waits for another
        // outside output instead of coming out of the phone's speaker (a pair switching to its 2.4 GHz dongle, say).
        music.firstOrNull { !it.phoneOutput }?.let {
            lastHeadset = it
            memory.rememberHeadset(it)
        }
        removed.lastOrNull { com.prism.music.data.isMusicOutput(it.type) && !it.phoneOutput }?.let { lastOutsideRemoved = it }
        android.util.Log.i(
            "PrismControl",
            "guard devices: +[${added.joinToString { describe(it) }}] -[${removed.joinToString { describe(it) }}]; " +
                "routed ${describe(controls.routedOutput)}, headset connected ${!routedIsPhone()}",
        )
        if (!settingOn()) return
        val held = hold ?: return
        val back = when (held) {
            Hold.VOLUME -> false
            Hold.OUTPUT -> music.isNotEmpty() || controls.hasOutput
            // A headset, Bluetooth pair or the 2.4 GHz dongle (USB) connected: the sound has somewhere to go. A dongle
            // already connected when the pair switched is seen by [check] once the route moves to it.
            Hold.NOISY, Hold.LOST -> music.any { !it.phoneOutput }
        }
        if (back) releaseHold()
    }

    /** The phone's own sound: the speaker (or an earpiece on a phone without one). */
    private val OutputReturn.Sink.phoneOutput: Boolean
        get() = com.prism.music.data.isPhoneOutput(type)

    private fun describe(sink: OutputReturn.Sink?): String =
        sink?.let { "${it.name.ifBlank { "?" }} (type ${it.type})" } ?: "unknown"

    /** The phone's volume and audio outputs, watched only while the setting is on. */
    class AndroidWatcher(private val context: Context) : Watcher {
        private val audio = context.getSystemService(AudioManager::class.java)
        private var observer: ContentObserver? = null
        private var devices: AudioDeviceCallback? = null

        override fun start(
            onVolume: () -> Unit,
            devices: (added: List<OutputReturn.Sink>, removed: List<OutputReturn.Sink>) -> Unit,
        ) {
            stop()
            val main = Handler(Looper.getMainLooper())
            val volume = object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) = onVolume()
            }
            observer = volume
            runCatching { context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volume) }
            val callback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) =
                    devices(addedDevices.filter { it.isSink }.map { it.toSink() }, emptyList())

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) =
                    devices(emptyList(), removedDevices.filter { it.isSink }.map { it.toSink() })
            }
            this.devices = callback
            runCatching { audio?.registerAudioDeviceCallback(callback, main) }
        }

        override fun stop() {
            observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
            observer = null
            devices?.let { runCatching { audio?.unregisterAudioDeviceCallback(it) } }
            devices = null
        }
    }

    /**
     * The last outside output, kept in the service's preferences: the player is torn down (swiped away, Android reclaiming
     * the service) while the earbuds are switching to their 2.4 GHz dongle, and the next service must still know they went.
     * Forgotten after [MAX_AGE_MS], so a pair used hours ago doesn't keep the phone's speaker from playing today.
     */
    class AndroidMemory(context: Context) : Memory {
        private val prefs = context.getSharedPreferences("playback_service", Context.MODE_PRIVATE)

        override fun lastHeadset(): OutputReturn.Sink? {
            val at = prefs.getLong(KEY_AT, 0L)
            if (at <= 0L || System.currentTimeMillis() - at > MAX_AGE_MS) return null
            val type = prefs.getInt(KEY_TYPE, -1)
            if (type < 0) return null
            return OutputReturn.Sink(type, prefs.getString(KEY_ADDRESS, "").orEmpty(), prefs.getString(KEY_NAME, "").orEmpty())
        }

        override fun rememberHeadset(sink: OutputReturn.Sink?) {
            prefs.edit().apply {
                if (sink == null) {
                    remove(KEY_TYPE); remove(KEY_ADDRESS); remove(KEY_NAME); remove(KEY_AT)
                } else {
                    putInt(KEY_TYPE, sink.type)
                    putString(KEY_ADDRESS, sink.address)
                    putString(KEY_NAME, sink.name)
                    putLong(KEY_AT, System.currentTimeMillis())
                }
            }.apply()
        }

        private companion object {
            const val KEY_TYPE = "guard_output_type"
            const val KEY_ADDRESS = "guard_output_address"
            const val KEY_NAME = "guard_output_name"
            const val KEY_AT = "guard_output_at"
            /** The last outside output matters for the listening session, not for the next day. */
            const val MAX_AGE_MS = 2 * 60 * 60 * 1000L
        }
    }
}
