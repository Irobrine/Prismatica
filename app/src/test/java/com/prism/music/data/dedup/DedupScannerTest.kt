package com.prism.music.data.dedup

import androidx.room.Room
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** A manual walk reprocesses only what changed, keeps failed files visible and unindexed, and can be stopped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DedupScannerTest {
    private fun ref(id: String, size: Long, modified: Long) = DedupScanner.FileRef(
        key = "local:$id", songId = id, uri = "content://media/$id", sizeBytes = size, modifiedMs = modified, durationMs = 30_000,
    )

    @Test
    fun onlyUnindexedStaleOrChangedFilesAreWalked() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DedupDatabase::class.java)
            .allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = DedupStore(db.dao())
            store.setMeta(DedupStore.META_ALGORITHM, AudioFingerprint.VERSION.toString())
            val a = ref("a", 100, 10)
            val b = ref("b", 100, 10)
            val fp = AudioFingerprint(AudioFingerprint.VERSION, 30_000, 16_000, longArrayOf(1, 2, 3, 4))
            store.putFingerprint(a.key, fp, a.sizeBytes, a.modifiedMs, 1L)
            store.markStale(b.key)
            var files = listOf(a, b, ref("c", 100, 10))
            var processed = emptyList<String>()
            val scanner = DedupScanner(scope, store, { files }, { r -> processed += r.songId; fp }, { })
            assertEquals(setOf("b", "c"), scanner.pending().map { it.songId }.toSet())
            assertTrue(scanner.indexOutdated.value)

            // The new file is excluded, so it is no longer work; the stale one still is.
            store.setExclusion("local:c", true, "a cover I keep", 2L)
            assertEquals(setOf("b"), scanner.pending().map { it.songId }.toSet())

            scanner.start()
            repeat(50) { if (!scanner.running.value) kotlinx.coroutines.delay(20) }
            assertEquals(setOf("b"), processed.toSet())
            assertEquals("ok", store.fingerprints().first { it.key == b.key }.state)
            assertEquals("false", store.meta(DedupStore.META_INDEX_OUTDATED))
        } finally {
            scope.cancel()
            db.close()
        }
    }

    @Test
    fun failedFilesStayUnindexedAndAreSkippedNextTime() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DedupDatabase::class.java)
            .allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = DedupStore(db.dao())
            val files = listOf(ref("a", 100, 10), ref("b", 100, 10))
            val fp = AudioFingerprint(AudioFingerprint.VERSION, 30_000, 16_000, longArrayOf(1, 2, 3, 4))
            var handed = emptyList<String>()
            val scanner = DedupScanner(scope, store, { files }, { r ->
                handed += r.songId
                if (r.songId == "b") error("can't decode") else fp
            }, { })
            scanner.start()
            repeat(100) { if (!scanner.running.value) kotlinx.coroutines.delay(20) }
            assertEquals(setOf("a", "b"), handed.toSet())
            assertEquals("failed", store.fingerprints().first { it.key == "local:b" }.state)
            assertEquals("ok", store.fingerprints().first { it.key == "local:a" }.state)
            // A failed file is still pending on the next run; a successful one is not.
            store.setMeta(DedupStore.META_ALGORITHM, AudioFingerprint.VERSION.toString())
            assertEquals(setOf("b"), scanner.pending().map { it.songId }.toSet())
        } finally {
            scope.cancel()
            db.close()
        }
    }

    @Test
    fun aStoppedWalkKeepsItsProgressAndTheNextPressResumesTheSameSet() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DedupDatabase::class.java)
            .allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = DedupStore(db.dao())
            val files = listOf(ref("a", 100, 10), ref("b", 100, 10), ref("c", 100, 10))
            val fp = AudioFingerprint(AudioFingerprint.VERSION, 30_000, 16_000, longArrayOf(1, 2, 3, 4))
            val atB = CompletableDeferred<Unit>()
            val releaseB = CompletableDeferred<Unit>()
            var handed = emptyList<String>()
            val scanner = DedupScanner(scope, store, { files }, { r ->
                handed += r.songId
                if (r.songId == "b") { atB.complete(Unit); releaseB.await() }
                fp
            }, { })

            scanner.start()
            atB.await() // a is done, b is in progress, c not started
            scanner.stop()
            releaseB.complete(Unit)
            repeat(100) { if (scanner.running.value) kotlinx.coroutines.delay(20) }
            // Stopping keeps the count and the original total on screen.
            assertEquals(1 to 3, scanner.progress.value)
            assertEquals(false, scanner.running.value)

            handed = emptyList() // only the files still to do may be handed to the fingerprinter on resume
            scanner.start() // resumes b and c, over the same total of three
            repeat(200) { if (scanner.progress.value != null) kotlinx.coroutines.delay(20) }
            assertEquals(null, scanner.progress.value) // finished walks clear the row
            assertEquals(listOf("b", "c"), handed) // a must not be fingerprinted again: that is the reset
            assertEquals(3, store.fingerprints().count { it.state == "ok" })
        } finally {
            scope.cancel()
            db.close()
        }
    }
}
