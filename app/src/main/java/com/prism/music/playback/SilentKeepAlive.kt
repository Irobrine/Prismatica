package com.prism.music.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.Executors

/**
 * Compatibility mode's silent playback: a zero-volume AudioTrack that keeps Android (and OriginOS/Funtouch) treating
 * Prism as a playing media app while the placeholder card is up. It makes no sound; it is stopped as soon as a real
 * song starts or the card goes away.
 *
 * Its start and stop run on a thread of their own: making and starting an AudioTrack (and asking for audio focus) on
 * the main thread cost a pause of 30-50 ms every time a song was stopped, which is the mini player's swipe-down and
 * the frame it was sliding on. Calls are queued in order on the one thread, so a quick stop/start still ends in the
 * state asked for last.
 */
internal class SilentKeepAlive(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var track: AudioTrack? = null
    private var focus: AudioFocusRequest? = null
    /** What the last call asked for; read by the queued work, so calls overtaken before they run do nothing. */
    @Volatile private var wanted = false
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PrismSilence").apply { isDaemon = true }
    }

    fun start() {
        wanted = true
        worker.execute { if (wanted) startNow() }
    }

    fun stop() {
        wanted = false
        worker.execute { if (!wanted) stopNow() }
    }

    private fun startNow() {
        if (track != null) return
        runCatching {
            val samples = 8_000 // one second at 8 kHz
            val buffer = ShortArray(samples)
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            // The focus is what makes some phones treat Prism as the playing media app (not only the audio track).
            runCatching {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener { }
                    .build()
                if (audioManager?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = request
            }
            val t = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(8_000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(buffer.size * 2)
                .build()
            t.write(buffer, 0, buffer.size)
            t.setLoopPoints(0, buffer.size, -1)
            t.setVolume(0f)
            t.play()
            track = t
            android.util.Log.i("PrismControl", "compatibility mode: silent playback on")
        }.onFailure { android.util.Log.w("PrismControl", "compatibility mode: couldn't play silence: $it") }
    }

    private fun stopNow() {
        track?.let { t ->
            runCatching { t.stop() }
            runCatching { t.release() }
        }
        track = null
        focus?.let { request -> runCatching { audioManager?.abandonAudioFocusRequest(request) } }
        focus = null
    }
}
