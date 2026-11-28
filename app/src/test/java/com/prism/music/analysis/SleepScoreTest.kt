package com.prism.music.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sleep score: calm against rousing, explained; slow or instrumental alone isn't sleepy; feedback moves it. */
class SleepScoreTest {
    private val calm = SongFeatures(
        seconds = 240f, lufs = -21f, lra = 4f, maxMomentary = -15f, dynamicComplexity = 1.5f, bpm = 70f, pulseClarity = 0.1f,
        tempoStability = 0.9f, onsetRate = 1.0f, percussive = 0.1f, centroidHz = 700f, rolloffHz = 1500f, flatness = 0.02f,
        segLoud = List(24) { -21f + (it % 2) * 0.5f }, segBright = List(24) { 700f },
    )
    private val club = SongFeatures(
        seconds = 240f, lufs = -6f, lra = 5f, maxMomentary = -3f, dynamicComplexity = 2f, bpm = 128f, pulseClarity = 0.95f,
        tempoStability = 1f, onsetRate = 6f, percussive = 0.7f, centroidHz = 2600f, rolloffHz = 5000f, flatness = 0.08f,
        segLoud = List(24) { -6f }, segBright = List(24) { 2600f },
    )

    /** Pooled tagger scores with the given classes set. */
    private fun scores(vararg set: Pair<Int, Float>) = FloatArray(YamnetTagger.CLASSES).also { s -> set.forEach { (i, v) -> s[i] = v } }

    @Test fun calmMusicScoresHighAndClubMusicLow() {
        val a = SleepScore.of(calm)
        val b = SleepScore.of(club)
        assertTrue("calm ${a.score}", a.score >= 80)
        assertTrue("club ${b.score}", b.score <= 25)
        assertFalse(a.capped)
        // Explained: the club track's beat, busyness and brightness hold it back, and its loudness still counts against it —
        // with less pull than when Normalize is off (ENERGY is no longer the heaviest of them).
        val against = b.against(6).map { it.factor }
        assertTrue(against.toString(), SleepFactor.BEAT in against && SleepFactor.DENSITY in against)
        assertTrue(against.toString(), SleepFactor.ENERGY in against)
        assertTrue(b.parts.first { it.factor == SleepFactor.ENERGY }.calm < 0.15f)
        assertTrue(a.forIt().isNotEmpty())
        assertTrue(a.parts.all { it.detail.isNotBlank() })
    }

    @Test fun slowIsNotEnoughWhenTheBeatIsHardAndTheMixLoudAndBusy() {
        // 60 BPM, but a pounding, loud, bright, busy track: tempo alone doesn't make it sleepy.
        val slowHard = club.copy(bpm = 60f)
        val r = SleepScore.of(slowHard)
        assertTrue("slow but hard ${r.score}", r.score < 45)
        // A tempo with no beat to feel barely counts: an ambient wash "at 140" stays calm.
        val ambient = calm.copy(bpm = 140f, pulseClarity = 0.05f)
        assertTrue(SleepScore.of(ambient).score >= 75)
    }

    @Test fun instrumentalAloneIsNotEnough() {
        val instrumental = scores(148 to 0.6f, 273 to 0.4f)
        val loudDrums = scores(157 to 0.8f, 274 to 0.5f)
        val r = SleepScore.of(club, loudDrums)
        assertTrue(r.score < 30)
        // Soft singing over a calm bed still scores well (sleep playlists are full of it).
        val sung = SleepScore.of(calm, scores(24 to 0.5f, 273 to 0.4f, 148 to 0.6f))
        assertTrue("soft singing ${sung.score}", sung.score >= 65)
        assertTrue(SleepScore.of(calm, instrumental).score >= sung.score)
    }

    @Test fun vocalsCanBeLeftOutOfIt() {
        val rap = scores(31 to 0.9f, 0 to 0.6f)
        val counted = SleepScore.of(calm, rap)
        val notCounted = SleepScore.of(calm, rap, SleepWeights(vocalsCount = false))
        assertTrue(notCounted.score > counted.score)
        assertEquals(0f, notCounted.parts.first { it.factor == SleepFactor.VOCALS }.weight, 0f)
    }

    @Test fun withoutTheTaggerTheScoreSaysItIsIncomplete() {
        assertTrue(SleepScore.of(calm).completeness < 0.9f)
        // Every figure there (a piano, a tender mood, no voice): everything counts.
        assertTrue(SleepScore.of(calm, scores(148 to 0.6f, 273 to 0.4f)).completeness > 0.99f)
    }

    @Test fun withNormalizeOnLoudnessCountsFarLess() {
        // Normalize (the default) brings every song to one level, so a master's own loudness must not decide the score:
        // ENERGY's weight drops from 1.4 to 0.6, and what's left of energy (density, brightness, beat) has its own factors.
        val on = SleepScore.of(club, weights = SleepWeights(normalizeTarget = -14f))
        val off = SleepScore.of(club, weights = SleepWeights(normalizeTarget = null))
        assertEquals(SleepWeights.NORMALIZED_ENERGY, on.parts.first { it.factor == SleepFactor.ENERGY }.weight, 1e-6f)
        assertEquals(SleepFactor.ENERGY.weight, off.parts.first { it.factor == SleepFactor.ENERGY }.weight, 1e-6f)
        // A loud, compressed master is held back less once its level is equalised, but it's still far from calm.
        assertTrue("on ${on.score}, off ${off.score}", on.score > off.score)
        assertTrue(on.score < 45)
        // A quiet song gains nothing from the cut (its loudness was a calm signal), and both ways stay calm.
        assertTrue(SleepScore.of(calm, weights = SleepWeights(normalizeTarget = -14f)).score >= 80)
        // The target is part of the weights: the analysis' cache sees the change and re-scores without any re-analysis.
        assertTrue(SleepWeights(normalizeTarget = -14f) != SleepWeights(normalizeTarget = null))
    }

    /**
     * With Normalize on, a quiet master is played at the target like any other: its own level mustn't read as calm. What
     * still tells a squashed master from an open one is how far its peaks stand over its loudness.
     */
    @Test fun aQuietButSquashedMasterIsNotCalmOnceNormalizeLiftsIt() {
        val w = SleepWeights(normalizeTarget = -14f)
        fun energy(f: SongFeatures) = SleepScore.of(f, weights = w).parts.first { it.factor == SleepFactor.ENERGY }.calm
        // A -21 LUFS upload of a squashed pop master (peaks 9 dB over it) and an open, dynamic recording at the same level.
        val squashed = club.copy(lufs = -21f, peakDb = -12f, truePeakDb = -12f, maxMomentary = null)
        val open = club.copy(lufs = -21f, peakDb = -4f, truePeakDb = -4f, maxMomentary = null)
        assertTrue("squashed ${energy(squashed)}", energy(squashed) < 0.3f)
        assertTrue("open ${energy(open)} vs squashed ${energy(squashed)}", energy(open) > energy(squashed) + 0.3f)
    }

    /** Brightness is read above the sub-bass: a bass-boosted mix isn't "dark" (and so calm) because its bass is huge. */
    @Test fun aBassBoostedMixIsNotReadAsDark() {
        fun bright(f: SongFeatures) = SleepScore.of(f).parts.first { it.factor == SleepFactor.BRIGHTNESS }.calm
        val mellow = calm.copy(brightShare = 0.03f, lowRatio = 0.2f)
        val boosted = calm.copy(brightShare = 0.06f, lowRatio = 0.8f)
        assertTrue("mellow ${bright(mellow)} vs boosted ${bright(boosted)}", bright(mellow) > bright(boosted) + 0.3f)
    }

    @Test fun theEnergyWeightFollowsWhatThePlayersGainLeaves() {
        val w = SleepWeights(normalizeTarget = -14f)
        // Loud master the player cuts to target: nothing of its level is left, so loudness only proxies density.
        assertEquals(SleepWeights.NORMALIZED_ENERGY, w.energy(club.copy(peakDb = -0.1f)), 1e-6f)
        // Normalize off: the recording plays at its own level.
        assertEquals(SleepFactor.ENERGY.weight, SleepWeights(normalizeTarget = null).energy(club), 1e-6f)
        // A very quiet, peak-limited recording: the player can lift it only so far, and some of the level is still heard.
        val quietPeaky = calm.copy(lufs = -30f, peakDb = -0.5f, maxMomentary = -24f)
        assertTrue("quiet ${w.energy(quietPeaky)}", w.energy(quietPeaky) > SleepWeights.NORMALIZED_ENERGY + 0.3f)
        // The target matters: at -23 a -14 master is cut to it exactly (no level left).
        assertEquals(SleepWeights.NORMALIZED_ENERGY, SleepWeights(normalizeTarget = -23f).energy(club.copy(lufs = -14f)), 1e-6f)
        // And a residual bigger than the threshold counts in full.
        assertEquals(SleepFactor.ENERGY.weight, w.energy(calm.copy(lufs = -34f, peakDb = 0f)), 1e-6f)
    }

    @Test fun evidenceTheTaggerIsUnsureOfCountsForLess() {
        // Nothing heard at all: no voice isn't proof of an instrumental, and no mood isn't "tender".
        val silent = FloatArray(YamnetTagger.CLASSES)
        val clear = scores(24 to 0.7f, 148 to 0.6f, 273 to 0.4f)
        fun weightOf(s: FloatArray, key: String) = SleepScore.of(calm, s).parts.first { it.factor == keyFactor(key) }.weight
        assertTrue("vocals ${weightOf(silent, "vocals")} < ${weightOf(clear, "vocals")}", weightOf(silent, "vocals") < weightOf(clear, "vocals"))
        assertTrue("instruments ${weightOf(silent, "instruments")}", weightOf(silent, "instruments") < 0.2f)
        assertTrue("mood ${weightOf(silent, "mood")}", weightOf(silent, "mood") < 0.2f)
        assertTrue(weightOf(clear, "instruments") > 0.6f)
        // A half-heard voice (a word or two) says less than a clear one.
        val faint = SleepScore.of(calm, scores(24 to 0.2f)).parts.first { it.factor == SleepFactor.VOCALS }.weight
        val strong = SleepScore.of(calm, scores(24 to 0.7f)).parts.first { it.factor == SleepFactor.VOCALS }.weight
        assertTrue("faint $faint, strong $strong", faint < strong)
        // A tagger that heard nothing leaves the score exactly as the untagged one: no evidence, no invented calm factors.
        assertEquals(SleepScore.of(calm).score, SleepScore.of(calm, silent).score)
        assertEquals(SleepScore.of(calm).completeness, SleepScore.of(calm, silent).completeness, 0f)
    }

    @Test fun whatIsUnknownCountsAsNeutralNotAsCalm() {
        // Same song, with and without the tagger: the untagged one must not be raised by the factors it lacks.
        val tagged = SleepScore.of(club, scores(157 to 0.8f, 274 to 0.5f, 31 to 0.6f))
        val untagged = SleepScore.of(club)
        assertTrue("untagged ${untagged.score} vs tagged ${tagged.score}", tagged.score < untagged.score)
        assertTrue("completeness ${untagged.completeness}", untagged.completeness < 0.85f)
        // A fully figured calm song still scores high; a partial one sits closer to 50 than its figures alone would put it.
        val calmTagged = SleepScore.of(calm, scores(148 to 0.6f, 273 to 0.4f))
        assertTrue(calmTagged.completeness > 0.99f)
        assertTrue("calm ${calmTagged.score} vs partial ${SleepScore.of(calm).score}", calmTagged.score >= SleepScore.of(calm).score)
    }

    private fun keyFactor(key: String) = when (key) {
        "vocals" -> SleepFactor.VOCALS
        "instruments" -> SleepFactor.INSTRUMENTS
        else -> SleepFactor.MOOD
    }

    @Test fun aSongsOwnVoteMovesItsScore() {
        val base = SleepScore.of(club).score
        assertEquals((base + SleepScore.VOTE_SHIFT).coerceAtMost(100), SleepScore.of(club, vote = 1).score)
        assertEquals((base - SleepScore.VOTE_SHIFT).coerceAtLeast(0), SleepScore.of(club, vote = -1).score)
    }

    @Test fun theListenersFeedbackTeachesWhatTheyFindSleepy() {
        // This listener sleeps to energetic tracks and skips quiet ones: their scores shift that way, within the bound.
        val energetic = SleepScore.of(club).parts
        val quiet = SleepScore.of(calm).parts
        val examples = List(4) { SleepPersonal.Example(energetic, good = true, weight = 1f) } + List(4) { SleepPersonal.Example(quiet, good = false, weight = 1f) }
        val p = SleepPersonal.fit(examples)
        val clubShift = SleepScore.of(club, personal = p).personal
        val calmShift = SleepScore.of(calm, personal = p).personal
        assertTrue("club $clubShift calm $calmShift", clubShift > 0 && calmShift < 0)
        assertTrue(kotlin.math.abs(clubShift) <= SleepScore.PERSONAL_SHIFT)
        // One-sided feedback teaches nothing (it only says the listener likes what they voted on).
        assertEquals(0, SleepScore.of(club, personal = SleepPersonal.fit(List(6) { SleepPersonal.Example(energetic, true, 1f) })).personal)
    }

    @Test fun theEasingFunctionRunsFromOneToZero() {
        assertEquals(1f, SleepScore.down(0f, 1f, 2f), 0f)
        assertEquals(0f, SleepScore.down(3f, 1f, 2f), 0f)
        assertEquals(0.5f, SleepScore.down(1.5f, 1f, 2f), 1e-6f)
    }
}
