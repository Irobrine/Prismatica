package com.prism.music.data.dedup

import android.net.Uri
import com.prism.music.AppContainer
import com.prism.music.data.enhance.AudioScratch
import com.prism.music.data.recognize.ShazamSignature
import java.io.File

/**
 * Decodes a file on the phone and makes its [AudioFingerprint], entirely offline.
 *
 * The decoding is the app's existing [AudioScratch.decode] (MediaCodec → mono PCM at Shazam's 16 kHz) and the
 * fingerprint is the same [ShazamSignature] the recognition feature uses — only the matching is new. PCM is streamed
 * through a scratch file, so a long song never sits in memory as floats.
 */
internal object DedupFingerprinter {
    fun of(
        container: AppContainer,
        uri: Uri,
        scratch: File,
        durationMs: Long,
        cancelled: () -> Boolean = { false },
    ): AudioFingerprint {
        scratch.parentFile?.mkdirs()
        try {
            AudioScratch.decode(uri, ShazamSignature.RATE, 1, scratch, container, cancelled)
            val mono = AudioScratch.readMono16k(scratch)
            val pcm = ShortArray(mono.size) { i -> (mono[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
            val signature = ShazamSignature.of(pcm)
            return AudioFingerprint(AudioFingerprint.VERSION, durationMs, ShazamSignature.RATE, Landmarks.of(signature))
        } finally {
            runCatching { scratch.delete() }
        }
    }
}
