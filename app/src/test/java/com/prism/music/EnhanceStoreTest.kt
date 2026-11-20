package com.prism.music

import com.prism.music.data.enhance.AlignmentQuality
import com.prism.music.data.enhance.AlignmentRecord
import com.prism.music.data.enhance.AlignedLine
import com.prism.music.data.enhance.AlignedPart
import com.prism.music.data.enhance.AlignedWord
import com.prism.music.data.enhance.EnhanceKeys
import com.prism.music.data.enhance.EnhanceStore
import com.prism.music.data.enhance.ExternalInstrumentalRecord
import com.prism.music.data.enhance.SeparationRecord
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.lyrics.LyricWord
import com.prism.music.data.lyrics.Lyrics
import com.prism.music.data.prefs.LyricsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Generated lyrics/audio storage: overlays preserve the original, caches are keyed, and results survive a restart. */
class EnhanceStoreTest {
    private fun temp(): File = Files.createTempDirectory("prism-enhance-test").toFile().apply { deleteOnExit() }

    /**
     * The room Settings promises: over it, the least recently used generated audio goes; 0 is no limit, and what's
     * playing or queued is never taken.
     */
    @Test fun theRoomForGeneratedAudioKeepsWhatIsInUseAndNoLimitKeepsEverything() {
        val store = EnhanceStore(temp())
        val ids = listOf("old", "keep", "new")
        ids.forEachIndexed { i, id ->
            store.vocals.pcm(id).apply { writeBytes(ByteArray(500)); setLastModified(1_000L + i) }
            store.generated.pcm(id).apply { writeBytes(ByteArray(500)); setLastModified(1_000L + i) }
        }
        assertEquals(3_000, store.bytes())
        store.trim(0, emptySet())
        assertEquals("0 is no limit: nothing is taken", 3_000, store.bytes())
        // 2,000 across the two stores means 1,000 each; the oldest goes first, but never the one being played.
        store.trim(2_000, setOf("old"))
        assertTrue("the song in use was taken", store.vocals.pcm("old").isFile)
        assertFalse("the oldest of the rest stayed", store.vocals.pcm("keep").isFile)
        assertTrue("the newest was taken too", store.vocals.pcm("new").isFile)
    }

    @Test fun theSyncedWordsCanBeTurnedOffAndAreKept() {
        val dir = temp()
        val store = EnhanceStore(dir)
        assertTrue("on until the listener turns it off", store.lyricsEnabled("song"))
        val lyrics = plain()
        store.putAlignment(record(lyrics, words = false))
        store.setLyricsEnabled("song", false)
        assertFalse(store.lyricsEnabled("song"))
        // A restart reads the switch back, and the alignment is still there to turn on again.
        val again = EnhanceStore(dir)
        assertFalse(again.lyricsEnabled("song"))
        assertNotNull(again.alignment("song"))
        again.setLyricsEnabled("song", true)
        assertTrue(again.lyricsEnabled("song"))
    }

    @Test fun aLineWhoseWordsCarryNoTimeGetsRoomToMoveIn() {
        val store = EnhanceStore(temp())
        val lyrics = plain()
        // An unreliable alignment: every word of every line sits on one instant, and a plain line takes that as its span.
        val blocky = record(lyrics).let { r ->
            r.copy(
                lines = r.lines.mapIndexed { i, l ->
                    val at = 1_000L + i * 1_000L
                    l.copy(timeMs = at, endMs = at, words = l.words.map { it.copy(startMs = at, endMs = at) })
                },
            )
        }
        val applied = store.apply(lyrics, blocky)
        assertTrue("the first line still has no span", applied.lines[0].endMs > applied.lines[0].timeMs)
        // It runs up to the next line, so the two don't overlap.
        assertEquals(applied.lines[1].timeMs, applied.lines[0].endMs)
        // The last line has no next one: it gets a little room of its own rather than none.
        assertTrue("the last line still has no span", applied.lines[1].endMs > applied.lines[1].timeMs)
    }

    private fun plain() = Lyrics(LyricsSource.LRCLIB, false, listOf(
        LyricLine(-1, "Hello, world!"),
        LyricLine(-1, "It's a test."),
    ))

    private fun timed() = Lyrics(LyricsSource.LRCLIB, true, listOf(
        LyricLine(1_000, "Hello, world!", 2_000),
        LyricLine(2_500, "It's a test.", 4_000),
    ))

    private fun record(lyrics: Lyrics, words: Boolean = true) = AlignmentRecord(
        songId = "song", lyricsHash = EnhanceKeys.lyricsHash(lyrics), audioKey = "song|false|10",
        audioFingerprint = "abc", modelVersion = "q4-2026-03-31", createdAt = 1,
        quality = AlignmentQuality(reliable = true),
        lines = listOf(
            AlignedLine(0, "Hello, world!", 1_000, 2_000, words = listOf(
                AlignedWord(1_000, 1_500, "Hello, ", listOf(AlignedPart(1_000, 1_500, 7))),
                AlignedWord(1_500, 2_000, "world!"),
            )),
            AlignedLine(1, "It's a test.", 2_500, 4_000, words = listOf(
                AlignedWord(2_500, 3_000, "It's "),
                AlignedWord(3_000, 3_400, "a "),
                AlignedWord(3_400, 4_000, "test."),
            )),
        ),
    ).takeIf { words } ?: AlignmentRecord("song", EnhanceKeys.lyricsHash(lyrics), "song|false|10", "abc", "q4-2026-03-31", createdAt = 1)

    @Test fun plainLyricsBecomeTimedWithoutChangingText() {
        val store = EnhanceStore(temp())
        val l = plain()
        val out = store.apply(l, record(l))
        assertTrue(out.synced)
        assertEquals(l.plain, out.plain)
        assertEquals(3, out.lines[0].words.size + out.lines[1].words.size - 2)
        assertEquals(1_000, out.lines[0].timeMs)
    }

    @Test fun lineTimestampsAreKeptWhenWordsAreAdded() {
        val store = EnhanceStore(temp())
        val l = timed()
        val out = store.apply(l, record(l))
        assertEquals(1_000, out.lines[0].timeMs)
        assertEquals(2_000, out.lines[0].endMs)
        assertEquals("Hello, world!", out.lines[0].text)
        assertTrue(out.lines[0].isWordSynced)
    }

    @Test fun generatedWordsApplyToAWordTimedSourceToo() {
        val store = EnhanceStore(temp())
        val worded = timed().copy(lines = timed().lines.map { it.copy(words = listOf(LyricWord(1_000, 1_900, it.text))) })
        val out = store.apply(worded, record(timed()))
        assertEquals("Hello, world!", out.lines[0].text)
        assertEquals(2, out.lines[0].words.size)
        assertTrue(out.lines[0].words.none { it.text == worded.lines[0].text })
    }

    @Test fun existingWordsAreReplacedNotDuplicated() {
        val store = EnhanceStore(temp())
        val l = timed().copy(lines = timed().lines.map { it.copy(words = listOf(LyricWord(0, 1, "old"))) })
        val out = store.apply(l, record(l))
        assertFalse(out.lines[0].words.any { it.text == "old" })
    }

    @Test fun aDifferentSourceTextDoesNotTakeTheOverlay() {
        val store = EnhanceStore(temp())
        val rec = record(timed())
        val other = timed().copy(lines = listOf(LyricLine(1_000, "Completely different", 2_000), timed().lines[1]))
        val out = store.apply(other, rec)
        assertFalse(out.lines[0].isWordSynced)
    }

    @Test fun theSourceWordsDoNotChangeTheCacheKey() {
        // A word-timed source and the same source with its words stripped are the same original lyrics for alignment:
        // the alignment is shown on whichever the listener has.
        val with = timed().copy(lines = timed().lines.map { it.copy(words = listOf(LyricWord(1_000, 2_000, it.text))) })
        val without = with.copy(lines = with.lines.map { it.copy(words = emptyList(), background = emptyList()) })
        assertEquals(EnhanceKeys.lyricsHash(with), EnhanceKeys.lyricsHash(without))
    }

    @Test fun cacheKeysMoveWithTheLyricsAndTheAudio() {
        val a = timed()
        val b = a.copy(lines = a.lines.map { it.copy(text = it.text + "!") })
        assertNotEquals(EnhanceKeys.lyricsHash(a), EnhanceKeys.lyricsHash(b))
        val f = File(temp(), "audio.bin").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        val one = EnhanceKeys.fingerprint(f)
        f.appendBytes(ByteArray(4096) { (it * 3).toByte() })
        assertNotEquals(one, EnhanceKeys.fingerprint(f))
        assertNotNull(one)
    }

    @Test fun theAudioFingerprintIsKeptWithTheSong() {
        val store = EnhanceStore(temp())
        org.junit.Assert.assertNull(store.currentFingerprint("song"))
        store.putCurrentFingerprint("song", "abc123")
        assertEquals("abc123", store.currentFingerprint("song"))
    }

    @Test fun recordsAndStemsSurviveANewStore() {
        val dir = temp()
        val store = EnhanceStore(dir)
        store.putAlignment(record(timed()))
        store.putExternalInstrumental(ExternalInstrumentalRecord("song", "mine.wav", "content://x", "fp", 1, 44_100, 100))
        store.putSeparation(SeparationRecord("song", "song|false|10", "fp", "htdemucs", 1, 44_100, 100, 100, 100))
        // The PCM files: the same 16-bit layout the player reads.
        store.external.pcm("song").writeBytes(ByteArray(100 * 4))
        store.external.writeMeta("song", com.prism.music.playback.sing.StemStore.Meta(44_100, 100, complete = true))
        store.vocals.pcm("song").writeBytes(ByteArray(100 * 4))
        store.vocals.writeMeta("song", com.prism.music.playback.sing.StemStore.Meta(44_100, 100, complete = true))
        val again = EnhanceStore(dir)
        assertNotNull(again.alignment("song"))
        assertTrue(again.hasExternalInstrumental("song"))
        assertTrue(again.hasVocals("song"))
        assertEquals("mine.wav", again.externalInstrumental("song")?.name)
    }

    @Test fun clearingGeneratedAudioLeavesAnImportedInstrumental() {
        val dir = temp()
        val store = EnhanceStore(dir)
        store.vocals.pcm("song").writeBytes(ByteArray(4 * 10))
        store.vocals.writeMeta("song", com.prism.music.playback.sing.StemStore.Meta(44_100, 10, complete = true))
        store.external.pcm("song").writeBytes(ByteArray(4 * 10))
        store.external.writeMeta("song", com.prism.music.playback.sing.StemStore.Meta(44_100, 10, complete = true))
        store.clearAudio()
        assertFalse(store.hasVocals("song"))
        assertTrue("the listener's instrumental is still there", store.hasExternalInstrumental("song"))
    }

    @Test fun removingAModelFolderLeavesGeneratedWork() {
        val dir = temp()
        val store = EnhanceStore(dir)
        store.putAlignment(record(timed()))
        assertNotNull(store.alignment("song"))
        File(dir, "enhance-models").deleteRecursively()
        assertNotNull(EnhanceStore(dir).alignment("song"))
    }
}
