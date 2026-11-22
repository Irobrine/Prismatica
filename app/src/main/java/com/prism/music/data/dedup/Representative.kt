package com.prism.music.data.dedup

/**
 * The audio quality signals available for one version of a recording. [formatRank] groups lossless formats above
 * lossy ones; the rest break ties the way a listener would expect. A missing value is 0, never a guess.
 */
data class Quality(
    val formatRank: Int = 0,
    val bitrateKbps: Int = 0,
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
) {
    companion object {
        /** Lossless, then the common lossy codecs, then unknown. */
        fun rank(format: String?): Int = when (format?.trim()?.uppercase()) {
            "FLAC", "ALAC", "WAV", "AIFF", "APE", "DSF", "DFF", "PCM" -> 5
            "AAC", "MP3", "OPUS", "OGG", "VORBIS", "WMA", "M4A" -> 4
            "", null -> 0
            else -> 2
        }

        fun of(format: String?, bitrateKbps: Int, sampleRateHz: Int, bitDepth: Int) =
            Quality(rank(format), bitrateKbps.coerceAtLeast(0), sampleRateHz.coerceAtLeast(0), bitDepth.coerceAtLeast(0))
    }
}

/** One version of a recording, as far as choosing a representative is concerned. */
data class Candidate(
    val id: String,
    val isVideo: Boolean,
    val quality: Quality = Quality(),
    /** A version the listener pinned as this group's representative, globally. */
    val pinned: Boolean = false,
    /** A version the listener already chose with "Play a different version" (an existing manual preference). */
    val manuallyPreferred: Boolean = false,
)

/**
 * Picks the one version shown and played for a duplicate group. The order is the product requirement:
 *  1. a pinned version, 2. an existing manual "different version" preference, 3. audio over a music video,
 *  4. the best audio quality, 5. a video only if there is no audio version.
 *
 * A pin is a *preference*, not a requirement: if the pinned version is not in [available] (e.g. it is not in this
 * collection or its file was deleted) the next best version is used temporarily, and the pin takes effect again as
 * soon as that version returns — the choice is recomputed from the same persisted flag every time.
 */
object Representative {
    private val comparator = compareByDescending<Candidate> { it.pinned }
        .thenByDescending { it.manuallyPreferred }
        .thenBy { it.isVideo }
        .thenByDescending { it.quality.formatRank }
        .thenByDescending { it.quality.bitrateKbps }
        .thenByDescending { it.quality.sampleRateHz }
        .thenByDescending { it.quality.bitDepth }
        .thenBy { it.id }

    /** Ranks every version best first. */
    fun rank(candidates: List<Candidate>, available: Set<String>? = null): List<Candidate> =
        candidates.filter { available == null || it.id in available }.sortedWith(comparator)

    /** The best version, or null when [candidates] (or the [available] subset) is empty. */
    fun select(candidates: List<Candidate>, available: Set<String>? = null): String? =
        rank(candidates, available).firstOrNull()?.id

    /**
     * The version to play when the one that was playing has just become hidden: the group's current representative,
     * or the original id when there is no group for it. The caller keeps the playback position.
     */
    fun replacement(playingId: String, groups: List<Grouping.Group>, candidates: List<Candidate>, available: Set<String>? = null): String {
        val group = groups.firstOrNull { playingId in it.members } ?: return playingId
        val pool = candidates.filter { it.id in group.members }
        return select(pool, available?.let { it intersect group.members } ?: group.members) ?: playingId
    }
}
