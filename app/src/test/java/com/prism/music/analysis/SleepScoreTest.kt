package com.prism.music.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sleep score: calm against rousing, explained; slow or instrumental alone isn't sleepy; feedback moves it. */
class SleepScoreTest {
    private val calm = SongFeatures(
        seconds = 240f, lufs = -21f, lra = 4f, maxMomentary = -15f, dynamicComplexity = 1.5f, bpm = 70f, pulseClarity = 0.1f,
        tempoStability = 0.9f, onsetRate = 1.0f, percussive = 0.1f, centroidHz = 700f, rolloffHz = 1500f,
        segLoud = List(24) { -21f + (it % 2) * 0.5f }, segBright = List(24) { 700f },
    )
    private val club = SongFeatures(
        seconds = 240f, lufs = -6f, lra = 5f, maxMomentary = -3f, dynamicComplexity = 2f, bpm = 128f, pulseClarity = 0.95f,
        tempoStability = 1f, onsetRate = 6f, percussive = 0.7f, centroidHz = 2600f, rolloffHz = 5000f,
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
        // Explained: the club track's energy, beat and busyness hold it back.
        val against = b.against(4).map { it.factor }
        assertTrue(against.toString(), SleepFactor.ENERGY in against && SleepFactor.BEAT in against)
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
        assertTrue(SleepScore.of(calm, scores(148 to 0.5f)).completeness > 0.99f)
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
