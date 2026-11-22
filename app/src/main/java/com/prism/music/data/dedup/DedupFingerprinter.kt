package com.prism.music.data.dedup

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.prism.music.data.recognize.ShazamSignature
import com.prism.music.playback.QuickScan
import com.prism.music.playback.sing.Resampler

/**
 * Decodes bounded stretches of an on-phone file and fingerprints them, entirely offline.
 *
 * Fingerprinting a whole 30-minute recording takes minutes and keeps tens of megabytes of PCM in memory, which made a
 * scan look as if it had stopped on file 1. Instead this reuses [QuickScan]'s range decoding: three ~25 s stretches
 * spread across the recording (or the whole file when it is short). Shazam-style offset-vote matching only needs a
 * shared stretch of the recording, so this keeps the same detection quality while making each file a matter of seconds.
 */
internal object DedupFingerprinter {
    fun of(
        context: Context,
        uri: Uri,
        durationMs: Long,
        cancelled: () -> Boolean = { false },
    ): AudioFingerprint {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("the file has no audio track")
            extractor.selectTrack(track)
            // The metadata duration can be missing (a fresh download, a file the media store hasn't read yet). Ask the
            // extractor in that case, and fall back to a normal song length so the windows still land in the audio.
            val trackFormat = extractor.getTrackFormat(track)
            val fromFile = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) trackFormat.getLong(MediaFormat.KEY_DURATION) / 1_000 else 0L
            val lengthMs = if (durationMs > 0) durationMs else if (fromFile > 0) fromFile else 180_000L
            val pieces = QuickScan.decode(extractor, track, QuickScan.windows(lengthMs))
                ?: error("the audio is too short to fingerprint")
            // QuickScan decodes mono at ~11 kHz. Join the stretches and bring them to Shazam's 16 kHz input rate.
            val mono = FloatArray(pieces.first.sumOf { it.size }) { i ->
                var at = i
                var piece = 0
                while (piece < pieces.first.size - 1 && at >= pieces.first[piece].size) {
                    at -= pieces.first[piece].size
                    piece++
                }
                pieces.first[piece][at]
            }
            if (cancelled()) error("the scan was stopped")
            val at16 = Resampler.convert(mono, 1, pieces.second, ShazamSignature.RATE)
            val pcm = ShortArray(at16.size) { i -> (at16[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
            if (cancelled()) error("the scan was stopped")
            val signature = ShazamSignature.of(pcm)
            return AudioFingerprint(AudioFingerprint.VERSION, durationMs, ShazamSignature.RATE, Landmarks.of(signature))
        } finally {
            runCatching { extractor.release() }
        }
    }
}
