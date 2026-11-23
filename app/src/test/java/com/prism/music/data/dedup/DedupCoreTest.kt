package com.prism.music.data.dedup

import com.prism.music.data.recognize.ShazamSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The detection, grouping, representative and view logic is pure, so it is tested on the JVM with synthetic audio
 * instead of a device. The same generator the app uses for recognition ([ShazamSignature]) makes the fingerprints,
 * which means these tests exercise the real algorithm, not a stand-in.
 */
class DedupCoreTest {
    /** A few seconds of changing chords: enough varied peaks for constellation landmarks. */
    private fun music(seconds: Int, seed: Int, gain: Float = 1f): ShortArray {
        val n = ShazamSignature.RATE * seconds
        return ShortArray(n) { i ->
            val t = i.toDouble() / ShazamSignature.RATE
            val section = ((t * 4).toInt() + seed) % 8
            val base = 180.0 + section * 67.0 + seed * 11.0
            val v = sin(2 * PI * base * t) + 0.6 * sin(2 * PI * base * 1.5 * t) +
                0.4 * sin(2 * PI * base * 2.0 * t + section) + 0.25 * sin(2 * PI * base * 3.0 * t)
            (gain * v * 8000).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun fp(shorts: ShortArray): AudioFingerprint = AudioFingerprint(
        AudioFingerprint.VERSION,
        shorts.size * 1000L / ShazamSignature.RATE,
        ShazamSignature.RATE,
        Landmarks.of(ShazamSignature.of(shorts)),
    )

    /**
     * The stretches of [samples] the duplicate scan fingerprints ([DedupFingerprinter] decodes
     * [com.prism.music.playback.QuickScan.windows] of a file), joined: three ~25 s windows, or a short recording whole.
     */
    private fun windowed(samples: ShortArray): ShortArray {
        val durationMs = samples.size * 1000L / ShazamSignature.RATE
        val out = ArrayList<Short>(samples.size)
        for ((startMs, lengthMs) in com.prism.music.playback.QuickScan.windows(durationMs)) {
            val from = (startMs * ShazamSignature.RATE / 1000).toInt().coerceIn(0, samples.size)
            val to = ((startMs + lengthMs) * ShazamSignature.RATE / 1000).toInt().coerceIn(from, samples.size)
            for (i in from until to) out += samples[i]
        }
        return out.toShortArray()
    }

    /** The fingerprint the duplicate scan makes of a recording: its windows, joined (`DedupFingerprinter`). */
    private fun windowedFp(samples: ShortArray): AudioFingerprint = fp(windowed(samples))

    @Test
    fun fingerprintSurvivesEncoding() {
        val a = fp(music(12, 1))
        assertTrue("landmarks ${a.size}", a.size > 50)
        val back = AudioFingerprint.decode(a.encode())
        assertTrue(a.sameAs(back))
    }

    @Test
    fun sameRecordingMatchesAndADifferentOneDoesNot() {
        val a = fp(music(20, 1))
        val sameEncoding = fp(music(20, 1, gain = 0.82f))
        val different = fp(music(20, 7))
        val match = Matcher.compare(a, sameEncoding)
        println("same: ${match.votes} votes, ratio ${match.ratio}, level ${match.level}")
        assertEquals(Matcher.Level.HIGH, match.level)
        val other = Matcher.compare(a, different)
        println("different: ${other.votes} votes, ratio ${other.ratio}, level ${other.level}")
        assertEquals(Matcher.Level.NONE, other.level)
    }

    @Test
    fun aPartialRecordingStillMatchesAtAnOffset() {
        val whole = fp(music(24, 2))
        val all = music(24, 2)
        val clip = all.copyOfRange(ShazamSignature.RATE * 6, ShazamSignature.RATE * 18)
        val part = fp(clip)
        val match = Matcher.compare(whole, part)
        println("partial: ${match.votes} votes, ratio ${match.ratio}, offset ${match.offsetFrames}, level ${match.level}")
        assertEquals(Matcher.Level.HIGH, match.level)
    }

    /**
     * The scan fingerprints three stretches of a recording, not the whole of it ([DedupFingerprinter]): the same
     * recording re-encoded still matches, and a different one still doesn't.
     */
    @Test
    fun theScansWindowsStillTellACopyFromAnotherRecording() {
        val a = windowedFp(music(60, 1))
        val copy = windowedFp(music(60, 1, gain = 0.82f))
        val different = windowedFp(music(60, 7))
        val match = Matcher.compare(a, copy)
        println("windowed copy: ${match.votes} votes, ratio ${match.ratio}, level ${match.level}")
        assertEquals(Matcher.Level.HIGH, match.level)
        val other = Matcher.compare(a, different)
        println("windowed other: ${other.votes} votes, ratio ${other.ratio}, level ${other.level}")
        assertEquals(Matcher.Level.NONE, other.level)
    }

    /**
     * A copy with a video's intro is still the same recording: its windows land on different parts of it, but most of
     * each stretch's sound is shared, so the votes still agree.
     */
    @Test
    fun aCopyWithAnIntroStillMatches() {
        val song = music(60, 3)
        val video = music(8, 5) + song // another tune's 8 s ahead of it, as a video's intro
        val match = Matcher.compare(windowedFp(song), windowedFp(video))
        println("windowed with intro: ${match.votes} votes, ratio ${match.ratio}, level ${match.level}")
        assertEquals(Matcher.Level.HIGH, match.level)
    }

    @Test
    fun thresholdsAreAboutVotesAndFractionNotAnAssumedEightyPercent() {
        val borderlineRatio = Matcher.BORDERLINE_MIN_RATIO + 0.001
        assertEquals(Matcher.Level.NONE, Matcher.level(Matcher.BORDERLINE_MIN_VOTES - 1, 0.5))
        assertEquals(Matcher.Level.BORDERLINE, Matcher.level(Matcher.BORDERLINE_MIN_VOTES, borderlineRatio))
        assertEquals(Matcher.Level.BORDERLINE, Matcher.level(Matcher.HIGH_MIN_VOTES - 1, 0.5))
        assertEquals(Matcher.Level.HIGH, Matcher.level(Matcher.HIGH_MIN_VOTES, Matcher.HIGH_MIN_RATIO + 0.001))
        // Many votes out of a huge fingerprint are still only borderline.
        assertEquals(Matcher.Level.BORDERLINE, Matcher.level(Matcher.HIGH_MIN_VOTES + 4, borderlineRatio))
    }

    @Test
    fun transitiveWeakMatchesDoNotMergeUnrelatedTracks() {
        val relations = listOf(
            Relation("a", "b", RelationSource.AUTO, Matcher.HIGH_MIN_VOTES.toDouble()),
            Relation("b", "c", RelationSource.AUTO, Matcher.BORDERLINE_MIN_VOTES.toDouble() + 2),
        )
        val result = Grouping.build(listOf("a", "b", "c"), relations)
        // a and b group; the weak b~c edge is only a suggestion, so c stays on its own.
        assertEquals(2, result.groups.size)
        assertEquals(setOf("a", "b"), result.groups.first { it.members.size == 2 }.members)
        assertEquals(1, result.suggestions.size)
    }

    @Test
    fun aCompleteHighConfidenceCliqueGroupsAllThree() {
        val high = Matcher.HIGH_MIN_VOTES.toDouble()
        val result = Grouping.build(
            listOf("a", "b", "c"),
            listOf(Relation("a", "b", RelationSource.AUTO, high), Relation("b", "c", RelationSource.AUTO, high), Relation("a", "c", RelationSource.AUTO, high)),
        )
        assertEquals(1, result.groups.size)
        assertEquals(setOf("a", "b", "c"), result.groups[0].members)
    }

    @Test
    fun manualOverridesThresholdsAndSeparationOverridesManual() {
        val set = RelationSet()
        set.confirm("a", "c", 7.0, 1L)
        var result = Grouping.build(listOf("a", "b", "c"), set.all())
        assertEquals(2, result.groups.size)
        assertTrue(result.groups.any { it.members == setOf("a", "c") })

        set.separate("a", "c", 2L)
        result = Grouping.build(listOf("a", "b", "c"), set.all())
        assertEquals(3, result.groups.size)
        assertFalse(set.hasPair("a", "c", RelationSource.MANUAL))
    }

    @Test
    fun manualEditsSurviveARescan() {
        val set = RelationSet()
        set.addManual("a", "b", 1L)
        set.separate("c", "d", 1L)
        set.replaceAuto(listOf(Relation("a", "c", RelationSource.AUTO, 40.0)))
        assertEquals(3, set.all().size)
        assertTrue(set.hasPair("a", "b", RelationSource.MANUAL))
        assertTrue(set.hasPair("c", "d", RelationSource.SEPARATED))
        assertTrue(set.hasPair("a", "c", RelationSource.AUTO))
        set.removeManualMembership("a", "b")
        assertFalse(set.hasPair("a", "b", RelationSource.MANUAL))
    }

    @Test
    fun representativeFollowsPinPreferenceAudioAndQuality() {
        val audio = Candidate("audio", false, Quality.of("FLAC", 900, 96_000, 24))
        val video = Candidate("video", true, Quality.of("AAC", 320, 48_000, 16))
        assertEquals("audio", Representative.select(listOf(video, audio)))
        assertEquals("video", Representative.select(listOf(video, audio), setOf("video")))
        val pinnedVideo = video.copy(pinned = true)
        assertEquals("video", Representative.select(listOf(audio, pinnedVideo)))
        // A pin that is absent from this collection is used as soon as it comes back.
        assertEquals("audio", Representative.select(listOf(audio, pinnedVideo), setOf("audio")))
        assertEquals("video", Representative.select(listOf(audio, pinnedVideo), setOf("audio", "video")))
        val preferred = Candidate("cover", false, Quality.of("MP3", 128, 44_100, 16), manuallyPreferred = true)
        assertEquals("cover", Representative.select(listOf(audio, preferred)))
        val betterAudio = Candidate("lossy", false, Quality.of("AAC", 320, 48_000, 16))
        assertEquals("lossy", Representative.select(listOf(preferred.copy(manuallyPreferred = false), betterAudio)))
    }

    @Test
    fun settingsInheritAndPageOverrides() {
        val overrides = mapOf(DedupScope.SONGS.setting to false)
        assertFalse(DedupSettingsLogic.enabled(DedupScope.SONGS, true, overrides))
        assertTrue(DedupSettingsLogic.enabled(DedupScope.PLAYLISTS, true, overrides))
        val inherit = DedupSettingsLogic.withOverride(DedupScope.SONGS, overrides, null)
        assertFalse(inherit.containsKey(DedupScope.SONGS.setting))
        assertTrue(DedupSettingsLogic.enabled(DedupScope.SONGS, true, inherit))
        assertEquals(false, DedupSettingsLogic.override(DedupScope.SONGS, overrides))
    }

    @Test
    fun viewsHideAlternativesAndKeepUnderlyingIdsUntouched() {
        val group = Grouping.Group("video", setOf("video", "audio", "cover"))
        val candidates = listOf(
            Candidate("video", true), Candidate("audio", false, Quality.of("FLAC", 900, 96_000, 24)),
            Candidate("cover", false, Quality.of("MP3", 128, 44_100, 16)),
        )
        val ids = listOf("video", "other", "audio", "cover")
        val on = DedupViews(listOf(group), candidates, enabled = true)
        assertEquals(listOf("other", "audio"), on.queue(ids))
        assertEquals(setOf("video", "cover"), on.hidden(ids))
        assertEquals(2, on.rows(ids).size)
        val row = on.rows(ids).first() as DisplayRow.Group
        assertEquals("audio", row.representative)
        assertEquals(setOf("video", "cover"), row.alternatives.toSet())

        val off = DedupViews(listOf(group), candidates, enabled = false)
        assertEquals(ids, off.queue(ids))
        assertTrue(off.hidden(ids).isEmpty())
        assertEquals(4, off.rows(ids).size)
    }

    @Test
    fun aLargeLibraryIsGroupedOncePerGroupAndConsistentWithItsQueue() {
        val groups = listOf(
            Grouping.Group("g1", setOf("a1", "a2", "a3")),
            Grouping.Group("g2", setOf("b1", "b2")),
            Grouping.Group("g3", setOf("c1", "c2", "c3")),
        )
        val candidates = listOf("a1", "a2", "a3", "b1", "b2", "c1", "c2", "c3").map { Candidate(it, false) }
        // x, y, z are in no group; a3, c1 and c3 are in groups but not in this list.
        val ids = listOf("x", "a2", "b1", "y", "a1", "c2", "b2", "z")
        val views = DedupViews(groups, candidates, enabled = true)
        val hidden = views.hidden(ids)
        val rows = views.rows(ids)
        assertEquals("one row per group, none per hidden version", ids.size - hidden.size, rows.size)
        assertEquals(ids.filterNot { it in hidden }, views.queue(ids))
        assertEquals("each row where its group first appears", listOf("x", "g1", "g2", "y", "g3", "z"), rows.map { row ->
            when (row) {
                is DisplayRow.Single -> row.id
                is DisplayRow.Group -> row.id
            }
        })
        val g1 = rows.first { it is DisplayRow.Group && it.id == "g1" } as DisplayRow.Group
        assertEquals("only the versions this list has", setOf("a1", "a2"), (g1.alternatives + g1.representative).toSet())
        val g3 = rows.first { it is DisplayRow.Group && it.id == "g3" } as DisplayRow.Group
        assertEquals("its one version here is its own row", "c2", g3.representative)
        assertTrue("nothing to hide when no other version is in the list", g3.alternatives.isEmpty())
        assertEquals("a group this view was not built from still reads its versions", "a1", views.representative(Grouping.Group("other", setOf("a1")), setOf("a1")))
    }

    @Test
    fun aPlayingHiddenSongSwitchesToTheRepresentativeWhileKeepingItsIdOtherwise() {
        val group = Grouping.Group("video", setOf("video", "audio"))
        val candidates = listOf(Candidate("video", true), Candidate("audio", false, Quality.of("FLAC", 900, 96_000, 24)))
        assertEquals("audio", Representative.replacement("video", listOf(group), candidates))
        assertEquals("unknown", Representative.replacement("unknown", listOf(group), candidates))
    }
}
