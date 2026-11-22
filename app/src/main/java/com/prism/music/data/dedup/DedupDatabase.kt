package com.prism.music.data.dedup

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Persistent state for duplicate hiding, in its own database. It is deliberately separate from the library database:
 * every piece of it is a cache or a listener decision that can be recomputed or dropped without touching a play, like
 * or playlist, and the library's migrations (and its exported schemas) stay exactly as they were.
 */

/** A cached audio fingerprint, keyed by a stable file identity, with the file metadata used to detect a change. */
@Entity(tableName = "dedup_fingerprints")
data class FingerprintRow(
    @PrimaryKey val key: String,
    val blob: ByteArray,
    val version: Int,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val durationMs: Long,
    /** "ok", "stale" or "failed". */
    val state: String,
    val error: String? = null,
    val updatedAt: Long,
)

/** One persisted pairwise statement. Only AUTO rows are rewritten by a scan. */
@Entity(
    tableName = "dedup_relations",
    primaryKeys = ["a", "b"],
    indices = [Index("a"), Index("b")],
)
data class RelationRow(
    val a: String,
    val b: String,
    /** [RelationSource.name]. */
    val source: String,
    val score: Double = 0.0,
    val changedA: Boolean = false,
    val changedB: Boolean = false,
    val at: Long = 0L,
)

/** A file the listener set aside permanently, so a scan never fingerprints it. */
@Entity(tableName = "dedup_exclusions")
data class ExclusionRow(@PrimaryKey val key: String, val reason: String, val at: Long)

/** A version pinned as its group's representative, globally. */
@Entity(tableName = "dedup_pins")
data class PinRow(@PrimaryKey val songId: String, val at: Long)

/** Small key/value rows: algorithm version, last scan, "new files since the last scan", etc. */
@Entity(tableName = "dedup_meta")
data class MetaRow(@PrimaryKey val key: String, val value: String)

@Dao
interface DedupDao {
    @Query("SELECT * FROM dedup_fingerprints")
    fun fingerprints(): List<FingerprintRow>

    @Query("SELECT * FROM dedup_fingerprints WHERE `key` = :key")
    fun fingerprint(key: String): FingerprintRow?

    @Query("SELECT * FROM dedup_fingerprints WHERE state != 'ok'")
    fun unindexed(): List<FingerprintRow>

    @Upsert
    fun putFingerprint(row: FingerprintRow)

    @Query("DELETE FROM dedup_fingerprints WHERE `key` = :key")
    fun deleteFingerprint(key: String)

    @Query("DELETE FROM dedup_fingerprints")
    fun clearFingerprints()

    @Query("SELECT * FROM dedup_relations ORDER BY at")
    fun relations(): List<RelationRow>

    @Query("SELECT * FROM dedup_relations ORDER BY at")
    fun relationsFlow(): Flow<List<RelationRow>>

    @Upsert
    fun putRelation(row: RelationRow)

    @Upsert
    fun putRelations(rows: List<RelationRow>)

    @Query("DELETE FROM dedup_relations WHERE source = 'AUTO'")
    fun clearAutomatic()

    @Query("DELETE FROM dedup_relations WHERE (a = :a AND b = :b) OR (a = :b AND b = :a)")
    fun deletePair(a: String, b: String)

    @Query("SELECT * FROM dedup_exclusions")
    fun exclusions(): List<ExclusionRow>

    @Upsert
    fun putExclusion(row: ExclusionRow)

    @Query("DELETE FROM dedup_exclusions WHERE `key` = :key")
    fun deleteExclusion(key: String)

    @Query("SELECT * FROM dedup_pins")
    fun pins(): List<PinRow>

    @Upsert
    fun putPin(row: PinRow)

    @Query("DELETE FROM dedup_pins WHERE songId = :songId")
    fun deletePin(songId: String)

    @Query("SELECT value FROM dedup_meta WHERE `key` = :key")
    fun meta(key: String): String?

    @Upsert
    fun putMeta(row: MetaRow)

    /** Replaces the automatic statements in one transaction, preserving every manual decision. */
    @Transaction
    fun replaceAutomatic(rows: List<RelationRow>) {
        clearAutomatic()
        putRelations(rows)
    }
}

@Database(
    entities = [FingerprintRow::class, RelationRow::class, ExclusionRow::class, PinRow::class, MetaRow::class],
    version = 1,
    exportSchema = false,
)
abstract class DedupDatabase : RoomDatabase() {
    abstract fun dao(): DedupDao

    companion object {
        fun build(context: Context): DedupDatabase =
            Room.databaseBuilder(context, DedupDatabase::class.java, "prism_dedup.db").build()
    }
}
