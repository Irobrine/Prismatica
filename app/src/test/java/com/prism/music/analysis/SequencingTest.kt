package com.prism.music.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Similarity, the listener's audio taste, and ordering whole runs of songs. */
class SequencingTest {
    private fun feat(lufs: Float, bpm: Float, centroid: Float, onsets: Float = 3f, pulse: Float = 0.8f) = SongFeatures(
        seconds = 200f, lufs = lufs, bpm = bpm, pulseClarity = pulse, onsetRate = onsets, centroidHz = centroid, rolloffHz = centroid * 2,
        percussive = 0.4f, lra = 6f, mfcc = List(13) { 0f }, segLoud = List(20) { lufs }, segBright = List(20) { centroid },
    )

    private fun emb(seed: Int) = FloatArray(64) { i -> (if ((i + seed) % 3 == 0) 1f else 0.2f) }

    private fun entry(id: String, f: SongFeatures, seed: Int) = SoundEntry(id, f, emb(seed), null)

    private val sim = Similarity(DescriptorStats.DEFAULT)

    @Test fun alikeSongsAreCloserThanUnlikeOnes() {
        val a = entry("a", feat(-10f, 120f, 1500f), 0)
        val b = entry("b", feat(-10.5f, 121f, 1550f), 0)
        val c = entry("c", feat(-24f, 70f, 600f, onsets = 0.8f, pulse = 0.1f), 1)
        assertTrue(sim.between(a, b) > sim.between(a, c) + 0.2)
        assertTrue(sim.transition(a, b) < sim.transition(a, c))
        // Half or double time is the same tempo.
        assertEquals(1.0, Similarity.tempoCloseness(feat(-10f, 60f, 1500f), feat(-10f, 120f, 1500f))!!, 1e-9)
    }

    @Test fun theRunKeepsArtistsApartAndStepsSmoothly() {
        val c = listOf(
            QueueSequencer.Candidate("1", 1.0, "A", "x", entry("1", feat(-10f, 120f, 1500f), 0)),
            QueueSequencer.Candidate("2", 0.99, "A", "x", entry("2", feat(-10f, 120f, 1500f), 0)),
            QueueSequencer.Candidate("3", 0.95, "B", "y", entry("3", feat(-10f, 122f, 1520f), 0)),
            QueueSequencer.Candidate("4", 0.9, "C", "z", entry("4", feat(-11f, 118f, 1480f), 0)),
        )
        val run = QueueSequencer.order(c, 4, sim)
        assertEquals(4, run.size)
        for (i in 1 until run.size) assertTrue(run.map { it.artist }.toString(), run[i].artist != run[i - 1].artist)
        assertEquals(setOf("1", "2", "3", "4"), run.map { it.id }.toSet())
    }

    @Test fun aBedtimeRunWindsDown() {
        // The scores are the caller's (read live from the analysis): a bedtime run keeps to them.
        val loud = QueueSequencer.Candidate("loud", 1.0, "A", null, entry("loud", feat(-7f, 128f, 2500f, onsets = 6f), 0), sleepScore = 20)
        val mid = QueueSequencer.Candidate("mid", 0.9, "B", null, entry("mid", feat(-14f, 90f, 1400f, onsets = 3f), 0), sleepScore = 55)
        val calm = QueueSequencer.Candidate("calm", 0.8, "C", null, entry("calm", feat(-22f, 65f, 700f, onsets = 1f, pulse = 0.2f), 0), sleepScore = 90)
        // Two of the three: the loud one leads an ordinary run, and a bedtime run leaves it out and winds down.
        val normal = QueueSequencer.order(listOf(loud, mid, calm), 2, sim)
        val sleep = QueueSequencer.order(listOf(loud, mid, calm), 2, sim, mode = QueueSequencer.Mode.SLEEP)
        assertEquals("loud", normal.first().id)
        assertEquals(listOf("mid", "calm"), sleep.map { it.id })
        // All three: never louder than the song before.
        val all = QueueSequencer.order(listOf(loud, mid, calm), 3, sim, mode = QueueSequencer.Mode.SLEEP)
        val louds = all.map { it.sound!!.features.lufs!! }
        for (i in 1 until louds.size) assertTrue(louds.toString(), louds[i] <= louds[i - 1])
    }

    @Test fun theTasteLeansTowardsWhatsChosen() {
        val liked = listOf(emb(0), emb(0), emb(0))
        val skipped = listOf(emb(1), emb(1))
        val centre = AudioTaste.mean(liked + skipped)
        val t = AudioTaste(AudioTaste.direction(liked, skipped, centre), null)
        assertTrue(t.fit(emb(0)) > t.fit(emb(1)))
        assertEquals(0.0, AudioTaste.NONE.fit(emb(0)), 0.0)
        // Too few liked songs: no direction.
        assertEquals(null, AudioTaste.direction(liked.take(2), skipped, centre))
    }
}
