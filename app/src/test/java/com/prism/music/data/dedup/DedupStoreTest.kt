package com.prism.music.data.dedup

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** The cache's change detection and the rule that manual decisions survive a rescan. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DedupStoreTest {
    @Test
    fun fingerprintsAreCachedAndChangedFilesAreMarkedStale() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DedupDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val store = DedupStore(db.dao())
            val fp = AudioFingerprint(1, 12_000, 16_000, longArrayOf(1L, 2L, 3L))
            store.putFingerprint("local:1", fp, sizeBytes = 100, modifiedMs = 500, now = 1_000)
            assertTrue(store.fingerprint("local:1")!!.sameAs(fp))
            assertTrue(store.fingerprints().single().state == "ok")
            store.markStale("local:1")
            assertEquals("stale", store.fingerprints().single().state)
            assertEquals(1, db.dao().unindexed().size)

            // A failed file stays unindexed so a later scan tries it again.
            store.putFingerprint("local:2", fp, 100, 500, 1_001, error = "decode failed")
            assertEquals("failed", store.fingerprints().first { it.key == "local:2" }.state)
            assertEquals(2, db.dao().unindexed().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun rescanReplacesOnlyAutomaticRelationships() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DedupDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val store = DedupStore(db.dao())
            val set = RelationSet()
            set.confirm("a", "b", 9.0, 1L)
            set.separate("c", "d", 1L)
            set.replaceAuto(listOf(Relation("a", "c", RelationSource.AUTO, 30.0)))
            store.saveRelations(set)

            val loaded = store.relationSet()
            assertTrue(loaded.hasPair("a", "b", RelationSource.CONFIRMED))
            assertTrue(loaded.hasPair("c", "d", RelationSource.SEPARATED))
            assertTrue(loaded.hasPair("a", "c", RelationSource.AUTO))

            loaded.replaceAuto(listOf(Relation("b", "d", RelationSource.AUTO, 40.0)))
            store.saveRelations(loaded)
            val again = store.relationSet()
            assertFalse(again.hasPair("a", "c"))
            assertTrue(again.hasPair("b", "d", RelationSource.AUTO))
            assertTrue(again.hasPair("a", "b", RelationSource.CONFIRMED))
            assertTrue(again.hasPair("c", "d", RelationSource.SEPARATED))
            assertEquals(3, store.relationsFlow().first().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun exclusionsPinsAndMetaPersist() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DedupDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val store = DedupStore(db.dao())
            store.setExclusion("local:9", true, "a cover I want to keep", 10L)
            assertTrue("local:9" in store.exclusions())
            store.setExclusion("local:9", false, "", 11L)
            assertTrue(store.exclusions().isEmpty())
            store.setPin("video7", true, 12L)
            assertEquals(setOf("video7"), store.pins())
            store.setMeta(DedupStore.META_INDEX_OUTDATED, "true")
            assertEquals("true", store.meta(DedupStore.META_INDEX_OUTDATED))
        } finally {
            db.close()
        }
    }
}
