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
 * again through a headset or Bluetooth output (or the same output coming back): the phone's speaker was not where the
 * music was, and playing there after an unplug is the surprise this setting is for. Pressing Play while it waits is
 * taken as the listener asking for the speaker after all.
 */
internal class PlaybackGuard(
    private val watcher: Watcher,
    private val settingOn: () -> Boolean,
    private val muteHandlesVolume: () -> Boolean,
    private val controls: Controls,
    private val log: (String) -> Unit = { AudioDiagnostics.log("state", it) },
) : Player.Listener {

    /** Why playback is waiting, or null when it isn't. */
    enum class Hold { VOLUME, OUTPUT, NOISY }

    /** What [PlaybackGuard] needs of the player and the service. */
    interface Controls {
        /** Nothing would be heard: the phone's media volume is 0 (or Prism's own volume is, when that's what plays). */
        val silent: Boolean
        /** Any music output is connected right now (the phone's own speaker counts). */
        val hasOutput: Boolean
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

    private var watching = false
    private var released = false
    private var hold: Hold? = null
    /** The listener wants music: set by their play, dropped by their pause or the end of the queue. */
    private var wanted = false
    /** The playWhenReady our own pause or play is about to report, so it isn't taken for the listener's. */
    private var expect: Boolean? = null

    /** Why playback is held, for tests and diagnostics. */
    val holding: Hold? get() = hold

    /** Starts or stops watching to match the setting. Call on the main thread when it changes. */
    fun update() {
        if (released) return
        if (controls.playWhenReady) wanted = true
        val want = settingOn()
        if (want != watching) {
            watching = want
            if (want) {
                // Music already playing when the switch goes on: silence at volume 0 pauses it below.
                watcher.start(::onVolume, ::onDevices)
            } else {
                watcher.stop()
                if (hold != null) releaseHold()
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
        expect = null
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (released) return
        if (expect == playWhenReady) {
            expect = null
            return
        }
        if (playWhenReady) {
            wanted = true
            if (!settingOn() || controls.blocked) {
                hold = null
                return
            }
            val block = blockNow()
            if (block != null) holdNow(block) else hold = null
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
        else -> null
    }

    private fun holdNow(reason: Hold) {
        hold = reason
        if (!controls.playWhenReady) return
        expect = false
        controls.pause()
        log(
            when (reason) {
                Hold.VOLUME -> "paused: the phone's media volume is 0 (waiting for it)"
                Hold.OUTPUT -> "paused: no audio output is connected (waiting for one)"
                Hold.NOISY -> "paused: the output went away (waiting for a headset or Bluetooth output)"
            },
        )
    }

    /** Lets a held request start, unless something else blocks it still. */
    private fun releaseHold() {
        hold = null
        if (!wanted || controls.blocked) return
        val block = blockNow()
        if (block != null) {
            hold = block
            return
        }
        if (controls.playWhenReady) return
        expect = true
        controls.play()
        log("playing on: it can be heard again")
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
        if (released || !settingOn()) return
        val held = hold ?: return
        val back = when (held) {
            Hold.VOLUME -> false
            Hold.OUTPUT -> added.isNotEmpty() || controls.hasOutput
            // Headphones or Bluetooth again: the speaker alone is not where the music was.
            Hold.NOISY -> added.any { !it.phoneOutput }
        }
        if (back) releaseHold()
    }

    /** The phone's own sound: the speaker (or an earpiece on a phone without one). */
    private val OutputReturn.Sink.phoneOutput: Boolean
        get() = type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE

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
}
