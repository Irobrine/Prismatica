package com.prism.music.analysis

/**
 * What the song scan ([com.prism.music.playback.MixTempo]) hands its sound analysis: the scan asks whether a song wants
 * analysing, keeps the tagger's windows only when there is a tagger to hear them, and passes on what it read.
 * [AnalysisStore] in the app; a list in tests.
 */
interface SongAnalyzer {
    /** Whether [id] has no analysis from the current descriptors and tagger. Fast and safe from any thread. */
    fun needs(id: String): Boolean

    /**
     * Whether [id] has an analysis, but one made by older descriptors or an older tagger: it's still shown until a scan
     * redoes it, so a play of the song asks for that scan ([needs] on its own also covers songs analysed by nothing yet).
     */
    fun stale(id: String): Boolean

    /** Whether the tagger's model is on the phone and switched on: only then are its windows kept. */
    fun taggerReady(): Boolean

    /**
     * What a scan read of [id] (a form, [com.prism.music.playback.Loudness.formId]); [songId] the queue's song it played
     * for, when that's another id. Runs on the scan's own (background) thread, after its figures are stored: the
     * tagger's inference happens here, so Normalize never waits for it.
     */
    fun record(id: String, songId: String?, features: SongFeatures, windows: List<FloatArray>)
}
