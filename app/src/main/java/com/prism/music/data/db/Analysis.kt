package com.prism.music.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/**
 * A song's sound analysis (docs/audio-analysis.md): its descriptors ([com.prism.music.analysis.SongFeatures] as JSON),
 * the tagger's class probabilities and embedding, and the sleep score worked out from them with the default weights
 * (for filtering; the score shown is worked out again with the listener's own). Re-made whenever the descriptors' or
 * the tagger's version changes; never holds anything the listener decided (those are [SongAutoTagEntity]'s statuses
 * and [SleepVoteEntity]).
 */
@Entity(tableName = "song_analysis", indices = [Index("sleepScore")])
data class SongAnalysisEntity(
    @PrimaryKey val songId: String,
    /** [com.prism.music.analysis.Descriptors.VERSION]. */
    val algorithmVersion: Int,
    /** Which tagger heard it ([com.prism.music.analysis.AudioTagger.id]); null when none had. */
    val taggerId: String?,
    val taggerVersion: Int?,
    val analyzedAt: Long,
    val featuresJson: String,
    /** The tagger's pooled class probabilities, one byte each (0 … 255 for 0 … 1). */
    val scores: ByteArray?,
    /** The tagger's embedding, L2-normalized, one signed byte each (× 127). */
    val embedding: ByteArray?,
    /** The default-weights sleep score (0 … 100), for filtering. */
    val sleepScore: Int?,
)

/**
 * A machine tag on a song, kept apart from the listener's own tags (`song_tags`) and never written into them by itself.
 * [status] is [com.prism.music.analysis.TagStatus]'s ordinal: a listener's accept or reject outlives re-analysis.
 */
@Entity(tableName = "song_auto_tags", primaryKeys = ["songId", "tag"], indices = [Index("tag"), Index("status")])
data class SongAutoTagEntity(
    val songId: String,
    val tag: String,
    val category: String,
    val confidence: Float,
    /** Which tagger said it ("yamnet:1"). */
    val source: String,
    val status: Int,
    val updatedAt: Long,
)

/** The listener's own word on a song for sleep: +1 good for sleep, -1 not. */
@Entity(tableName = "sleep_votes")
data class SleepVoteEntity(@PrimaryKey val songId: String, val vote: Int, val at: Long)

/**
 * What listening says of a song beyond its plays: how often the listener queued it themselves (Play next, Add to
 * queue), and how it fared while the sleep timer ran (skipped, or played through).
 */
@Entity(tableName = "song_signals")
data class SongSignalEntity(
    @PrimaryKey val songId: String,
    val queued: Int,
    val sleepSkips: Int,
    val sleepFinishes: Int,
    val updatedAt: Long,
)

/** A review row: a machine tag waiting for the listener, with its song's name. */
data class TagReview(val songId: String, val tag: String, val category: String, val confidence: Float, val title: String?, val artistName: String?)

@Dao
abstract class AnalysisDao {
    @Query("SELECT * FROM song_analysis WHERE songId = :id")
    abstract suspend fun get(id: String): SongAnalysisEntity?

    @Query("SELECT * FROM song_analysis WHERE songId IN (:ids)")
    abstract suspend fun getAll(ids: List<String>): List<SongAnalysisEntity>

    @Query("SELECT * FROM song_analysis")
    abstract suspend fun all(): List<SongAnalysisEntity>

    /** What the analysis needs to know of every song at start: whether each is current. */
    @Query("SELECT songId, algorithmVersion, taggerId, taggerVersion FROM song_analysis")
    abstract suspend fun versions(): List<AnalysisVersion>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(e: SongAnalysisEntity)

    @Query("UPDATE song_analysis SET sleepScore = :score WHERE songId = :id")
    abstract suspend fun setSleepScore(id: String, score: Int?)

    @Query("SELECT COUNT(*) FROM song_analysis")
    abstract suspend fun count(): Int

    @Query("DELETE FROM song_analysis")
    abstract suspend fun clearAnalyses()

    // ---- machine tags

    @Query("SELECT * FROM song_auto_tags WHERE songId = :id")
    abstract suspend fun tags(id: String): List<SongAutoTagEntity>

    @Query("SELECT * FROM song_auto_tags WHERE songId = :id")
    abstract fun tagsFlow(id: String): kotlinx.coroutines.flow.Flow<List<SongAutoTagEntity>>

    @Query("DELETE FROM song_auto_tags WHERE songId = :id AND status IN (0, 1)")
    protected abstract suspend fun dropMachineTags(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun addTags(rows: List<SongAutoTagEntity>)

    /**
     * The tagger's new tags for a song in place of its old ones; tags the listener accepted or rejected stay as they
     * decided (the new guess for such a tag is dropped: IGNORE on the key).
     */
    @Transaction
    open suspend fun replaceMachineTags(id: String, rows: List<SongAutoTagEntity>) {
        dropMachineTags(id)
        addTags(rows)
    }

    @Query("UPDATE song_auto_tags SET status = :status, updatedAt = :at WHERE songId = :id AND tag = :tag")
    abstract suspend fun setStatus(id: String, tag: String, status: Int, at: Long)

    @Query(
        """SELECT t.songId, t.tag, t.category, t.confidence, s.title, s.artistName FROM song_auto_tags t
           LEFT JOIN songs s ON s.id = t.songId WHERE t.status = 1 ORDER BY t.confidence DESC LIMIT :limit""",
    )
    abstract suspend fun toReview(limit: Int): List<TagReview>

    @Query("SELECT COUNT(*) FROM song_auto_tags WHERE status = 1")
    abstract fun reviewCount(): kotlinx.coroutines.flow.Flow<Int>

    /** Songs carrying [tag] as their own (sure, or accepted). */
    @Query("SELECT songId FROM song_auto_tags WHERE tag = :tag AND status IN (0, 2)")
    abstract suspend fun songsTagged(tag: String): List<String>

    // ---- sleep votes

    @Query("SELECT * FROM sleep_votes")
    abstract suspend fun votes(): List<SleepVoteEntity>

    @Query("SELECT vote FROM sleep_votes WHERE songId = :id")
    abstract suspend fun vote(id: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putVote(e: SleepVoteEntity)

    @Query("DELETE FROM sleep_votes WHERE songId = :id")
    abstract suspend fun clearVote(id: String)

    // ---- listening signals

    @Query("SELECT * FROM song_signals")
    abstract suspend fun signals(): List<SongSignalEntity>

    @Query("SELECT * FROM song_signals WHERE songId = :id")
    abstract suspend fun signal(id: String): SongSignalEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putSignal(e: SongSignalEntity)

    /** Adds to a song's counts (a row made for it when it has none). */
    @Transaction
    open suspend fun bump(id: String, queued: Int = 0, sleepSkips: Int = 0, sleepFinishes: Int = 0, at: Long = System.currentTimeMillis()) {
        val old = signal(id)
        putSignal(
            SongSignalEntity(
                id, (old?.queued ?: 0) + queued, (old?.sleepSkips ?: 0) + sleepSkips, (old?.sleepFinishes ?: 0) + sleepFinishes, at,
            ),
        )
    }
}

data class AnalysisVersion(val songId: String, val algorithmVersion: Int, val taggerId: String?, val taggerVersion: Int?)
