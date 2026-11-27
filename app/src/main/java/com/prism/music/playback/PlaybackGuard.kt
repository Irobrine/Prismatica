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
 * the phone's media volume is 0, or the output that was playing just went away (Media3's becoming-noisy pause, as when
 * headphones are unplugged) — and starts by itself once sound is possible again: the volume comes back up, the pair
 * plays again, or another outside output is connected.
 *
 * It complements "Pause when muted": with that on, the mute watch owns pausing and resuming music that is already
 * playing; with it off, this guard pauses and resumes it at volume 0 itself. A play pressed at volume 0 waits either way.
 *
 * A becoming-noisy pause (ACTION_AUDIO_BECOMING_NOISY, which Media3 reports as the pause's reason) only starts the music
 * again through a headset or Bluetooth output (or the same output coming back), never the phone's speaker: the speaker
 * was not where the music was, and playing there after an unplug is the surprise this setting is for. A pair switching
 * to its own 2.4 GHz receiver may keep its Bluetooth entry listed; for the pairs chosen in Settings → Playback →
 * Headphones, its pause is taken as the switch, and a play on the phone waits for the pair's own play, or for a play
 * control the listener presses, before it can start again. Another output (a different pair, a dongle, wired
 * headphones) plays normally meanwhile, and its connection, entry or play never clears the switch.
 */
internal class PlaybackGuard(
    private val watcher: Watcher,
    private val settingOn: () -> Boolean,
    private val muteHandlesVolume: () -> Boolean,
    /** The Bluetooth headphones whose pause is their 2.4 GHz switch (empty: none). */
    private val switchDevices: () -> Set<String>,
    private val controls: Controls,
    private val log: (String) -> Unit = { AudioDiagnostics.log("state", it) },
    private val memory: Memory = Memory.None,
) : Player.Listener {

    /** Why playback is waiting, or null when it isn't. */
    enum class Hold { VOLUME, NOISY, LOST }

    /** What [PlaybackGuard] needs of the player and the service. */
    interface Controls {
        /** Nothing would be heard: the phone's media volume is 0 (or Prism's own volume is, when that's what plays). */
        val silent: Boolean
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
        /** The pair paused for a possible 2.4 GHz switch and hasn't played since. */
        fun switched(): Boolean
        fun rememberSwitched(on: Boolean)

        companion object {
            val None: Memory = object : Memory {
                override fun lastHeadset(): OutputReturn.Sink? = null
                override fun rememberHeadset(sink: OutputReturn.Sink?) {}
                override fun switched(): Boolean = false
                override fun rememberSwitched(on: Boolean) {}
            }
        }
    }

    private var watching = false
    private var released = false
    /** The setting has been seen off once since it was last on, so its stored headset/switch are already cleared. */
    private var offCleared = false
    private var hold: Hold? = null
    /** The listener wants music: set by their play, dropped by their pause or the end of the queue. */
    private var wanted = false
    /** The pair's own play (its switch back) is landing: the switch/route holds don't apply to it (volume still does). */
    private var pairPlay = false
    /** The playWhenReady our own pause or play is about to report, so it isn't taken for the listener's. */
    private var expect: Boolean? = null
    /** The player's playWhenReady as last reported, so a reason-only change to a pause is told from a pause. */
    private var lastPlayWhenReady = false
    /** The last outside output (headphones, earbuds, USB) seen connected: gone, a play waits for another, not the speaker. */
    private var lastHeadset: OutputReturn.Sink? = null
    /** The last outside output seen leaving: the same one coming back is the music's route again (see [check]). */
    private var lastOutsideRemoved: OutputReturn.Sink? = null
    /** The outside outputs connected right now, as the device watcher last reported them. */
    private val connectedOutside = LinkedHashSet<OutputReturn.Sink>()
    /** The pair paused for a possible 2.4 GHz switch: its own play or a play control lets playback start again. */
    private var switched = false

    /** Why playback is held, for tests and diagnostics. */
    val holding: Hold? get() = hold

    /** The pair paused for a possible 2.4 GHz switch: playback is held until its own play or a play control. */
    val switchedNow: Boolean get() = switched

    /** Starts or stops watching to match the setting. Call on the main thread when it changes. */
    fun update() {
        if (released) return
        lastPlayWhenReady = controls.playWhenReady
        if (controls.playWhenReady) wanted = true
        val want = settingOn()
        if (!want) {
            val wasOn = watching
            if (watching) {
                watching = false
                watcher.stop()
                android.util.Log.i("PrismControl", "play guard off")
            }
            if (wasOn || !offCleared) {
                // The setting is off: forget the last output/switch (a service started later with it off must not load
                // an old one when it is turned back on).
                offCleared = true
                lastHeadset = null
                switched = false
                pairPlay = false
                lastOutsideRemoved = null
                connectedOutside.clear()
                memory.rememberHeadset(null)
                memory.rememberSwitched(false)
                if (hold != null) releaseHold(force = true)
            }
            return
        }
        offCleared = false
        if (!watching) {
            watching = true
            android.util.Log.i("PrismControl", "play guard on")
            // What the last service was playing on: if it's gone (the pair switched to 2.4 GHz), a play waits.
            if (lastHeadset == null) memory.lastHeadset()?.let {
                lastHeadset = it
                android.util.Log.i("PrismControl", "guard remembered the last headset: ${describe(it)}")
            }
            if (memory.switched()) {
                switched = true
                android.util.Log.i("PrismControl", "guard remembered the pair switched to 2.4 GHz")
            }
            watcher.start(::onVolume, ::onDevices)
        }
        onVolume()
    }

    fun release() {
        released = true
        if (watching) watcher.stop()
        watching = false
        hold = null
        wanted = false
        pairPlay = false
        switched = false
        lastOutsideRemoved = null
        connectedOutside.clear()
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
                pairPlay = false
                hold = null
                return
            }
            if (pairPlay) {
                // The pair's own play (its switch back): the switch/route holds don't stop it. Silence still does.
                pairPlay = false
                if (controls.silent) holdNow(Hold.VOLUME) else hold = null
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
        pairPlay = false
        hold = null
    }

    private fun blockNow(): Hold? = when {
        controls.silent -> Hold.VOLUME
        // The pair paused for a possible switch to its 2.4 GHz receiver: a play mustn't start it on the phone. Another
        // headphone that is the route is allowed; the switch flag stays until the chosen pair itself comes back.
        switched && !controls.speakerPicked && !otherHeadphonesRouted -> Hold.LOST
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
            Hold.NOISY -> "the output went away (waiting for a headset or Bluetooth output)"
            Hold.LOST -> if (switched) "the pair switched to its 2.4 GHz receiver (waiting for its play)"
                else "the headphones that were playing are gone (waiting for them)"
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
     * Re-checks the guard while something is held or a 2.4 GHz switch is set: the route can change without a device
     * being added (a dongle already connected, a pair whose Bluetooth entry stays listed, or a switch to another pair
     * and back), so the service asks once a second.
     */
    fun check() {
        if (released || !settingOn()) return
        val held = hold
        if (held == null) {
            // The switch is set and the sound moved back to the chosen pair (the phone switched to it while it is still
            // in 2.4 GHz): hold the music again instead of playing into a pair that isn't listening.
            if (!switched || !controls.playWhenReady) return
            holdNow(blockNow() ?: return)
            return
        }
        val lost = lastHeadset
        val removed = lastOutsideRemoved
        val back = when (held) {
            Hold.VOLUME -> !controls.silent
            // A 2.4 GHz switch is over on the pair's play, never on a look at the route. An unselected headphone that
            // becomes the route is different: playback there is normal while the switch holds.
            Hold.NOISY, Hold.LOST -> if (switched) otherHeadphonesRouted else
                controls.routedOutput.let { routed ->
                    routed != null && !routed.phoneOutput &&
                        (lost == null || !routed.sameAs(lost) || removed?.sameAs(routed) == true)
                }
        }
        if (back) releaseHold()
    }

    /** A pause from the pair itself: on a TWS that can be the switch to its 2.4 GHz receiver (the Bluetooth link stays). */
    fun onRemotePause() {
        if (released || !settingOn()) return
        val lost = lastHeadset ?: return
        // Only the headphones the listener chose: another pair's pause is an ordinary pause.
        if (!isChosen(lost)) return
        // A pause from the pair itself can be it switching to its 2.4 GHz receiver, which keeps the Bluetooth link on
        // some pairs but sends a pause first. Hold as if the output went; only its own play (or a reconnect, or a
        // picked speaker) releases it, so an app play can't start it on the phone meanwhile.
        if (!lost.bluetooth || routedIsPhone()) return
        switched = true
        memory.rememberSwitched(true)
        // Nothing playing (swiped away, stopped): there is no song to pause, but a play started now must still wait.
        if (controls.playWhenReady) {
            wanted = true
            holdNow(Hold.LOST)
            android.util.Log.i("PrismControl", "play held: the Bluetooth device paused (possible switch to 2.4 GHz)")
        } else {
            android.util.Log.i("PrismControl", "the Bluetooth device paused while stopped: a play waits for its own")
        }
    }

    /**
     * The pair played on (a Bluetooth media button): its switch back. The switch and a stale headset hold are released,
     * and the play the system is about to make is allowed even if the route still reads as the phone. Silence still
     * holds it; the pair's play at volume 0 waits for the volume.
     */
    fun onRemotePlay() {
        if (released) return
        // Only a chosen pair's play is its switch back; another pair must not clear the flag.
        if (!playIsChosenPair()) return
        pairPlay = true
        switched = false
        memory.rememberSwitched(false)
        if (hold == Hold.LOST || hold == Hold.NOISY) hold = null
        android.util.Log.i("PrismControl", "the Bluetooth device played: the 2.4 GHz hold is released")
    }

    /** A single play/pause button on the pair, while paused: the same switch back, before the toggle plays. */
    fun onRemotePlayToggle() {
        if (released) return
        if (!playIsChosenPair()) return
        pairPlay = true
        switched = false
        memory.rememberSwitched(false)
        if (hold == Hold.LOST || hold == Hold.NOISY) hold = null
        android.util.Log.i("PrismControl", "the Bluetooth device played: the 2.4 GHz hold is released")
    }

    /**
     * The listener pressed a play control (Prism's play button, the system's media controls, the persistent card's play,
     * a widget): an explicit play, and so an override of the 2.4 GHz switch. It has to be: a pair that goes back to
     * Bluetooth without a media button sends the phone nothing, so the switch could never end otherwise. Choosing a
     * song, a playlist or a shuffle action does not come through here, so those stay held while the pair is in 2.4 GHz.
     */
    fun playFromControl() {
        if (released || !settingOn() || !switched) return
        switched = false
        memory.rememberSwitched(false)
        pairPlay = true
        if (hold == Hold.LOST || hold == Hold.NOISY) hold = null
        android.util.Log.i("PrismControl", "play control: the 2.4 GHz switch is released")
    }

    /**
     * The queue was stopped or emptied by the listener (swiped away): nothing is waiting to play on any more, so a hold
     * must not keep the persistent card away from starting something.
     */
    fun stopped() {
        if (released) return
        hold = null
        wanted = false
        pairPlay = false
        // switched stays: a new play from the card still waits for the pair while it is in 2.4 GHz.
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
        val outside = music.filter { !it.phoneOutput }
        connectedOutside.removeAll { seen -> removed.any { it.sameAs(seen) } }
        connectedOutside.addAll(outside)
        // What the music was playing on, remembered even while nothing is held: once it's gone, a play waits for another
        // outside output instead of coming out of the phone's speaker (a pair switching to its 2.4 GHz dongle, say).
        outside.firstOrNull()?.let {
            lastHeadset = it
            memory.rememberHeadset(it)
        }
        // A 2.4 GHz switch is over on the pair's own play, never on its Bluetooth entry: a TWS in 2.4 GHz mode can
        // re-list that entry (taken out of its case, the phone switched back to it), and clearing the switch there would
        // let the music play into a pair that isn't listening.
        removed.lastOrNull { com.prism.music.data.isMusicOutput(it.type) && !it.phoneOutput }?.let { lastOutsideRemoved = it }
        // The output left while the music was playing: whatever resumed in the gap (a volume-up after "Pause when
        // muted") must not keep playing out of the phone now. This also holds the persistent card back.
        if (removed.any { com.prism.music.data.isMusicOutput(it.type) && !it.phoneOutput } &&
            settingOn() && controls.playWhenReady && routedIsPhone()
        ) {
            wanted = true
            holdNow(Hold.LOST)
        }
        android.util.Log.i(
            "PrismControl",
            "guard devices: +[${added.joinToString { describe(it) }}] -[${removed.joinToString { describe(it) }}]; " +
                "routed ${describe(controls.routedOutput)}, route outside ${!routedIsPhone()}",
        )
        if (!settingOn()) return
        val held = hold ?: return
        val back = when (held) {
            Hold.VOLUME -> false
            // A headset, Bluetooth pair or the 2.4 GHz dongle (USB) connected: the sound has somewhere to go. While a
            // switch is set, only an unselected output counts: the chosen pair's own entry coming back is not a return.
            Hold.NOISY, Hold.LOST -> if (switched) outside.any { !isChosen(it) } else outside.isNotEmpty()
        }
        if (back) releaseHold()
    }

    /** The phone's own sound: the speaker (or an earpiece on a phone without one). */
    private val OutputReturn.Sink.phoneOutput: Boolean
        get() = com.prism.music.data.isPhoneOutput(type)

    /** Whether [sink] is one of the headphones chosen for the 2.4 GHz switch. */
    private fun isChosen(sink: OutputReturn.Sink): Boolean =
        switchDevices().any { it.equals(sink.name, ignoreCase = true) || it.equals(sink.address, ignoreCase = true) }

    /**
     * Whether a media play that just arrived came from a chosen pair, so it may be the switch back. Android's media
     * button says nothing about which device sent it, so the route decides: a play while another (unchosen) headphone
     * is the route belongs to that pair and must not clear the switch.
     */
    private fun playIsChosenPair(): Boolean {
        val routed = controls.routedOutput
        if (routed != null && !routed.phoneOutput) return isChosen(routed)
        // The sound is on the phone's speaker (the pair in 2.4 GHz keeps its Bluetooth entry, so the play came over its
        // AVRCP): the chosen pair's, unless another Bluetooth output is connected to have sent it.
        val bluetooth = connectedOutside.filter { it.bluetooth }
        if (bluetooth.isNotEmpty()) return bluetooth.all { isChosen(it) }
        return lastHeadset?.let { isChosen(it) } ?: false
    }

    /**
     * Another headphone (not a chosen switch pair) is the route right now: the music may play there normally while the
     * chosen pair's switch stays set for when it comes back.
     */
    val otherHeadphonesRouted: Boolean
        get() = controls.routedOutput?.let { routed -> !routed.phoneOutput && !isChosen(routed) } ?: false

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
     * The last-output fallback is forgotten after [MAX_AGE_MS]; the explicit switch flag is kept until the pair plays again,
     * reconnects, or the setting is turned off.
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
                    remove(KEY_TYPE); remove(KEY_ADDRESS); remove(KEY_NAME); remove(KEY_AT); remove(KEY_SWITCHED)
                } else {
                    putInt(KEY_TYPE, sink.type)
                    putString(KEY_ADDRESS, sink.address)
                    putString(KEY_NAME, sink.name)
                    putLong(KEY_AT, System.currentTimeMillis())
                }
            }.apply()
        }

        override fun switched(): Boolean {
            // The explicit 2.4 GHz pause lasts until the pair plays again, reconnects, or the setting is turned off:
            // a listening session can be all day, and this flag is the only signal while the Bluetooth link stays.
            return prefs.getBoolean(KEY_SWITCHED, false)
        }

        override fun rememberSwitched(on: Boolean) {
            prefs.edit().apply {
                if (on) {
                    putBoolean(KEY_SWITCHED, true)
                    putLong(KEY_AT, System.currentTimeMillis())
                } else remove(KEY_SWITCHED)
            }.apply()
        }

        private companion object {
            const val KEY_TYPE = "guard_output_type"
            const val KEY_ADDRESS = "guard_output_address"
            const val KEY_NAME = "guard_output_name"
            const val KEY_AT = "guard_output_at"
            const val KEY_SWITCHED = "guard_switched"
            /** The last outside output matters for the listening session, not for the next day. */
            const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
        }
    }
}
