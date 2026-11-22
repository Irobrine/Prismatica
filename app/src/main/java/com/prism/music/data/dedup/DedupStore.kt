package com.prism.music.data.dedup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The listener's persistent dedup state: cached fingerprints, pairwise decisions, pinned versions and exclusions.
 *
 * A scan only ever replaces the AUTO rows ([RelationSet.replaceAuto]); confirmations, manual additions and separations
 * are loaded and saved whole, so a rescan cannot undo a person's edit.
 */
class DedupStore(private val dao: DedupDao) {
    suspend fun relationSet(): RelationSet = withContext(Dispatchers.IO) {
        RelationSet(dao.relations().map { row ->
            Relation(
                a = row.a, b = row.b,
                source = runCatching { RelationSource.valueOf(row.source) }.getOrDefault(RelationSource.AUTO),
                score = row.score, changedA = row.changedA, changedB = row.changedB, at = row.at,
            )
        }.toMutableSet())
    }

    suspend fun saveRelations(set: RelationSet) = withContext(Dispatchers.IO) {
        dao.replaceAutomatic(set.auto().map { it.toRow() })
        val manual = set.all().filter { it.source != RelationSource.AUTO }
        manual.forEach { dao.putRelation(it.toRow()) }
        // A pair that used to be manual but was removed has to go as well.
        val rows = dao.relations().associateBy { it.a to it.b }
        set.all().map { it.pair }.let { keep ->
            rows.filterKeys { it !in keep }.filter { it.value.source != "AUTO" }.forEach { dao.deletePair(it.key.first, it.key.second) }
        }
    }

    suspend fun relationsFlow(): Flow<List<RelationRow>> = dao.relationsFlow()

    suspend fun fingerprints(): List<FingerprintRow> = withContext(Dispatchers.IO) { dao.fingerprints() }

    /** Change-detection fields only — safe to call on every launch with a large cache. */
    suspend fun fingerprintMetas(): List<FingerprintMeta> = withContext(Dispatchers.IO) { dao.fingerprintMetas() }

    /** Drops blobs made by an older fingerprint format; pairwise decisions are left alone. */
    suspend fun dropOldVersions() = withContext(Dispatchers.IO) { dao.deleteOldVersions(AudioFingerprint.VERSION) }

    suspend fun fingerprint(key: String): AudioFingerprint? = withContext(Dispatchers.IO) {
        dao.fingerprint(key)?.let { AudioFingerprint.decode(it.blob) }
    }

    suspend fun putFingerprint(
        key: String,
        fingerprint: AudioFingerprint,
        sizeBytes: Long,
        modifiedMs: Long,
        now: Long,
        error: String? = null,
    ) = withContext(Dispatchers.IO) {
        dao.putFingerprint(
            FingerprintRow(
                key = key, blob = fingerprint.encode(), version = fingerprint.version,
                sizeBytes = sizeBytes, modifiedMs = modifiedMs, durationMs = fingerprint.durationMs,
                state = if (error == null) "ok" else "failed", error = error, updatedAt = now,
            ),
        )
    }

    suspend fun markStale(key: String) = withContext(Dispatchers.IO) {
        dao.fingerprint(key)?.let { dao.putFingerprint(it.copy(state = "stale")) }
    }

    /** A file that was deleted or moved out of scope: its cached fingerprint is no longer of any use. */
    suspend fun forget(key: String) = withContext(Dispatchers.IO) { dao.deleteFingerprint(key) }

    suspend fun exclusions(): Set<String> = withContext(Dispatchers.IO) { dao.exclusions().map { it.key }.toSet() }

    suspend fun setExclusion(key: String, on: Boolean, reason: String, now: Long) = withContext(Dispatchers.IO) {
        if (on) dao.putExclusion(ExclusionRow(key, reason, now)) else dao.deleteExclusion(key)
    }

    suspend fun pins(): Set<String> = withContext(Dispatchers.IO) { dao.pins().map { it.songId }.toSet() }

    suspend fun setPin(songId: String, on: Boolean, now: Long) = withContext(Dispatchers.IO) {
        if (on) dao.putPin(PinRow(songId, now)) else dao.deletePin(songId)
    }

    suspend fun meta(key: String): String? = withContext(Dispatchers.IO) { dao.meta(key) }

    suspend fun setMeta(key: String, value: String) = withContext(Dispatchers.IO) { dao.putMeta(MetaRow(key, value)) }

    companion object {
        const val META_ALGORITHM = "algorithm"
        const val META_INDEX_OUTDATED = "index_outdated"
    }
}

private fun Relation.toRow() = RelationRow(a, b, source.name, score, changedA, changedB, at)
