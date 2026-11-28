package com.prism.music.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tag vocabulary over YAMNet's classes, the tagger's pooling and windows, and the int8/byte packing of what's kept. */
class TaggingTest {
    private fun scores(vararg set: Pair<Int, Float>) = FloatArray(YamnetTagger.CLASSES).also { s -> set.forEach { (i, v) -> s[i] = v } }
    private fun List<PredictedTag>.of(key: String) = firstOrNull { it.key == key }

    @Test fun confidentClassesAreTheSongsAndUncertainOnesAskForReview() {
        val tags = TagVocabulary.tags(scores(148 to 0.8f, 185 to 0.15f, 273 to 0.2f, 232 to 0.4f))
        assertEquals(TagStatus.AUTO, tags.of("instrument:piano")!!.status)
        assertEquals(TagStatus.REVIEW, tags.of("instrument:strings")!!.status)
        assertEquals(TagStatus.AUTO, tags.of("mood:tender")!!.status)
        assertEquals(TagStatus.AUTO, tags.of("genre:Classical")!!.status)
        // Nothing below the maybe line; acoustic (piano, strings, classical, no synths) is a class score's.
        assertNull(tags.of("instrument:drums"))
        assertNotNull(tags.of(TagVocabulary.ACOUSTIC))
        // "Instrumental" is not a tag: it was a guess from the voice classes' silence, which gave nearly every instrumental
        // track a meaningless 99 % and crowded out the tags the model actually heard.
        assertNull(tags.of(TagVocabulary.INSTRUMENTAL))
        assertEquals(0.8f, tags.of("instrument:piano")!!.confidence, 1e-6f)
    }

    @Test fun instrumentalIsNeverSuggestedAndVoiceStillReads() {
        // An instrumental track: nothing above the maybe line except what the model heard, and no "Instrumental".
        val quiet = scores(148 to 0.35f)
        val tags = TagVocabulary.tags(quiet)
        assertNull(tags.of(TagVocabulary.INSTRUMENTAL))
        assertTrue(tags.all { it.confidence <= 0.5f })
        // The voice evidence itself is still there for the sleep score's vocals factor.
        assertEquals(0f, TagVocabulary.voice(quiet), 0f)
        val sung = scores(24 to 0.7f)
        assertEquals(0.7f, TagVocabulary.voice(sung), 1e-6f)
        assertNull(TagVocabulary.tags(sung).of(TagVocabulary.INSTRUMENTAL))
    }

    @Test fun aStyleSaysItsGenreAndVocalsRuleOutInstrumental() {
        val tags = TagVocabulary.tags(scores(215 to 0.5f, 136 to 0.7f, 24 to 0.6f))
        assertEquals(TagStatus.AUTO, tags.of("style:heavy-metal")!!.status)
        // Heavy metal says Metal even where the genre class itself is quiet.
        assertEquals(TagStatus.AUTO, tags.of("genre:Metal")!!.status)
        assertEquals(TagStatus.AUTO, tags.of("vocals:singing")!!.status)
        assertNull(tags.of(TagVocabulary.INSTRUMENTAL))
        assertNull(tags.of(TagVocabulary.ACOUSTIC))
    }

    @Test fun everyTagHasValidClassesAndAStableUniqueKey() {
        val keys = TagVocabulary.all.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        TagVocabulary.all.forEach { d ->
            assertTrue(d.key, d.classes.isNotEmpty() && d.classes.all { it in 0 until YamnetTagger.CLASSES })
            if (d.category == TagCategory.STYLE && d.label == null) assertNotNull(d.key, d.parent)
            d.parent?.let { p -> assertNotNull("$p is a genre", TagVocabulary.def("genre:$p")) }
        }
        assertEquals(TagVocabulary.all.size, TagVocabulary.profile(scores()).size)
    }

    @Test fun poolingTakesTheTopQuarterOfFrames() {
        // A voice in 2 frames of 8: the top quarter (2 frames) sees it fully; a one-frame blip in 8 counts at half.
        val frames = List(8) { i -> FloatArray(3).also { it[0] = if (i < 2) 1f else 0f; it[1] = if (i == 0) 1f else 0f; it[2] = 0.5f } }
        val pooled = YamnetTagger.pool(frames)
        assertEquals(1f, pooled[0], 1e-6f)
        assertEquals(0.5f, pooled[1], 1e-6f)
        assertEquals(0.5f, pooled[2], 1e-6f)
    }

    @Test fun theTapKeepsEightWindowsResampledTo16kHz() {
        val rate = 44_100
        val seconds = 180
        val tap = TaggerTap(rate, seconds * 1000L)
        for (i in 0 until rate * seconds) tap.sample(kotlin.math.sin(i * 0.01).toFloat())
        val w = tap.windows()
        assertEquals(TaggerTap.WINDOWS, w.size)
        w.forEach { assertEquals(TaggerTap.WINDOW_S * 16_000, it.size.toDouble(), 2.0) }
        // A song too short for eight windows gets fewer, never overlapping ones.
        val short = TaggerTap(rate, 20_000)
        for (i in 0 until rate * 20) short.sample(0f)
        assertTrue(short.windows().size in 1..3)
    }

    @Test fun scoresAndEmbeddingsPackSmallAndComeBackClose() {
        val s = FloatArray(521) { it / 520f }
        val back = AnalysisStore.unpackScores(AnalysisStore.packScores(s))
        for (i in s.indices) assertEquals(s[i], back[i], 1f / 255)
        val e = FloatArray(1024) { kotlin.math.sin(it * 0.37).toFloat() + 1.2f }
        val packed = AnalysisStore.packEmbedding(e)
        assertEquals(1024, packed.size)
        assertTrue(Similarity.cosine(e, AnalysisStore.unpackEmbedding(packed)) > 0.999)
    }

    @Test fun statusesKeepTheirStoredNumbers() {
        // Stored as ordinals in song_auto_tags (the DAO's queries name 0, 1 and 2): never reordered.
        assertEquals(listOf(TagStatus.AUTO, TagStatus.REVIEW, TagStatus.ACCEPTED, TagStatus.REJECTED), TagStatus.entries.toList())
        assertTrue(TagStatus.ACCEPTED.listenerDecided && TagStatus.REJECTED.listenerDecided)
        assertFalse(TagStatus.REVIEW.listenerDecided)
    }
}
