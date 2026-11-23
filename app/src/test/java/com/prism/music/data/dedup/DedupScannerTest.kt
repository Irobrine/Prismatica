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
            repeat(100) { if (scanner.running.value) kotlinx.coroutines.delay(20) }
            assertEquals(setOf("b"), processed.toSet())
            assertEquals("ok", store.fingerprints().first { it.key == b.key }.state)
            assertEquals("false", store.meta(DedupStore.META_INDEX_OUTDATED))
        } finally {
            scope.cancel()
            db.close()
        }
    }

    /**
     * "New files — scan to find their duplicates" means the index doesn't match the phone. A file whose last scan
     * failed (its row is there, and the next press retries it) and one the listener set aside are not new, so the hint
     * stays off for them instead of showing for the life of the install; a file that changed since does ask for a scan.
     */
    @Test
    fun onlyAFileTheIndexHasNeverSeenIsNew() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DedupDatabase::class.java)
            .allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = DedupStore(db.dao())
            val scanned = ref("a", 100, 10)
            val failed = ref("b", 100, 10)
            val setAside = ref("c", 100, 10)
            val fresh = ref("d", 100, 10)
            val fp = AudioFingerprint(AudioFingerprint.VERSION, 30_000, 16_000, longArrayOf(1, 2, 3, 4))
            store.putFingerprint(scanned.key, fp, scanned.sizeBytes, scanned.modifiedMs, 1L)
            db.dao().putFingerprint(
                FingerprintRow(failed.key, fp.encode(), AudioFingerprint.VERSION, failed.sizeBytes, failed.modifiedMs, 30_000, "failed", "could not decode", 1L),
            )
            store.setExclusion(setAside.key, true, "a cover I keep", 2L)
            var files = listOf(scanned, failed, setAside)
            val scanner = DedupScanner(scope, store, { files }, { fp }, { })

            assertEquals("the failed file is still work", listOf("b"), scanner.pending().map { it.songId })
            assertEquals("but not a new file", false, scanner.indexOutdated.value)
            assertEquals("false", store.meta(DedupStore.META_INDEX_OUTDATED))

            // The same file, re-encoded: the index no longer matches it, so a scan is wanted, as before.
            files = listOf(ref("a", 999, 10), failed, setAside)
            assertEquals("a changed file is work", setOf("a", "b"), scanner.pending().map { it.songId }.toSet())
            assertEquals("and it asks for a scan", true, scanner.indexOutdated.value)

            files = listOf(scanned, failed, setAside, fresh)
            assertEquals("the new one is work too", setOf("b", "d"), scanner.pending().map { it.songId }.toSet())
            assertEquals("and the hint says there are new files", true, scanner.indexOutdated.value)
            assertEquals("true", store.meta(DedupStore.META_INDEX_OUTDATED))
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
            repeat(100) { if (scanner.running.value) kotlinx.coroutines.delay(20) }
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

    /**
     * A walk stopped after its last file (or after everything it had left was measured some other way) must finish:
     * the count clears and the next press takes a new set, instead of sitting on "N of N" and resuming an empty one
     * forever. Its grouping may never have run, so it still compares what it has.
     */
    @Test
    fun aStoppedWalkWithNothingLeftFinishes() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DedupDatabase::class.java)
            .allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = DedupStore(db.dao())
            val a = ref("a", 100, 10)
            val b = ref("b", 100, 10)
            var files = listOf(a, b)
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
            atB.await()
            scanner.stop()
            releaseB.complete(Unit)
            repeat(100) { if (scanner.running.value) kotlinx.coroutines.delay(20) }
            assertEquals(1 to 2, scanner.progress.value)
            // What was left is measured meanwhile (a file scanned by another press, say).
            store.putFingerprint(b.key, fp, b.sizeBytes, b.modifiedMs, 5L)
            handed = emptyList()

            scanner.start() // nothing left: it finishes rather than holding "1 of 2"
            repeat(100) { if (scanner.running.value) kotlinx.coroutines.delay(20) }
            assertEquals(null, scanner.progress.value)
            assertEquals(emptyList<String>(), handed)

            // And the next press is a new set over the files as they are: one added since is taken in.
            files = listOf(a, b, ref("c", 100, 10))
            scanner.start()
            repeat(100) { if (scanner.running.value) kotlinx.coroutines.delay(20) }
            assertEquals(listOf("c"), handed)
            assertEquals(null, scanner.progress.value)
        } finally {
            scope.cancel()
            db.close()
        }
    }

    /**
     * A file added while a walk is stopped is not part of that walk's set: carrying it on would finish without the new
     * file and mark the index up to date, so the press starts over what the phone has now.
     */
    @Test
    fun aFileAddedWhileAWalkWasStoppedIsStillScanned() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DedupDatabase::class.java)
            .allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = DedupStore(db.dao())
            val a = ref("a", 100, 10)
            val b = ref("b", 100, 10)
            var files = listOf(a, b)
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
            atB.await()
            scanner.stop()
            releaseB.complete(Unit)
            repeat(100) { if (scanner.running.value) kotlinx.coroutines.delay(20) }
            assertEquals(1 to 2, scanner.progress.value)

            // A file arrives while the walk is stopped.
            val c = ref("c", 100, 10)
            files = listOf(a, b, c)
            handed = emptyList()
            scanner.start() // the set changed: a new walk over what is there, not a resume of the old two
            repeat(200) { if (scanner.running.value) kotlinx.coroutines.delay(20) }
            assertEquals("the new file is fingerprinted too", listOf("b", "c"), handed)
            assertEquals("and the index is up to date again", false, scanner.indexOutdated.value)
            assertEquals("false", store.meta(DedupStore.META_INDEX_OUTDATED))
            assertEquals(3, store.fingerprints().count { it.state == "ok" })
        } finally {
            scope.cancel()
            db.close()
        }
    }

    /**
     * One cached row whose blob won't decode (a write cut short, a damaged database) is skipped: it must not take the
     * walk — and, on a phone, the app — down with it.
     */
    @Test
    fun aCorruptCachedFingerprintDoesNotStopTheWalk() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DedupDatabase::class.java)
            .allowMainThreadQueries().build()
        val problems = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> problems += e },
        )
        try {
            val store = DedupStore(db.dao())
            val a = ref("a", 100, 10)
            val b = ref("b", 100, 10)
            val c = ref("c", 100, 10)
            val fp = AudioFingerprint(AudioFingerprint.VERSION, 30_000, 16_000, longArrayOf(1, 2, 3, 4))
            store.putFingerprint(a.key, fp, a.sizeBytes, a.modifiedMs, 1L)
            // Not a fingerprint: the header is missing, so decoding it throws.
            db.dao().putFingerprint(
                FingerprintRow(b.key, byteArrayOf(1, 2, 3), AudioFingerprint.VERSION, b.sizeBytes, b.modifiedMs, 30_000, "ok", null, 1L),
            )
            var handed = emptyList<String>()
            val scanner = DedupScanner(scope, store, { listOf(a, b, c) }, { r -> handed += r.songId; fp }, { })

            scanner.start()
            repeat(200) { if (scanner.running.value) kotlinx.coroutines.delay(20) }
            assertEquals(listOf("c"), handed) // only the unindexed file needed a fingerprint
            assertEquals(null, scanner.progress.value)
            assertEquals(false, scanner.running.value)
            assertTrue("the walk must not fail on one bad row: $problems", problems.isEmpty())
        } finally {
            scope.cancel()
            db.close()
        }
    }

    /** A stop is a cancel, not a file that couldn't be decoded: no "failed" row is written for it. */
    @Test
    fun aStoppedFileIsNotRecordedAsAFailure() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DedupDatabase::class.java)
            .allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = DedupStore(db.dao())
            val scanner = DedupScanner(
                scope, store, { listOf(ref("a", 100, 10)) },
                { throw kotlinx.coroutines.CancellationException("the scan was stopped") }, { },
            )
            scanner.start()
            repeat(100) { if (scanner.running.value) kotlinx.coroutines.delay(20) }
            assertTrue("no failed row for a stop: ${store.fingerprints()}", store.fingerprints().isEmpty())
            assertEquals(false, scanner.running.value)
        } finally {
            scope.cancel()
            db.close()
        }
    }
}
