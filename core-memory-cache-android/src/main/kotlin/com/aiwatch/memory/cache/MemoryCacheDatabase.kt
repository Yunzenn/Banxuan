package com.aiwatch.memory.cache

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * One cached record.
 *
 * A plain persistence projection of `CanonicalMemory`, not a third copy of its semantics. The entity
 * has no identity, fingerprint, transition or de-duplication logic and must never grow any: those are
 * the authority's, and a projection that computed them would be a second definition of what a memory is.
 *
 * Deliberately not the HTTP wire JSON. Storing the transport payload would make the durable cache depend
 * on `:core-memory-remote` and therefore on the wire format, so a transport change would force a
 * database migration for data that has nothing to do with the transport.
 *
 * The many nullable columns are the honest shape of four different record types in one table. Splitting
 * them per type would add joins and a table-per-type hierarchy for no benefit at this size.
 *
 * **`subjectId` is part of the primary key.** It is local infrastructure, not a field of
 * `CanonicalMemory` - the same separation the server-side partition makes. Without it, a device whose
 * identity is reset or rebound would show the next subject the previous subject's last-known memories.
 */
@Entity(tableName = "cached_memory", primaryKeys = ["subjectId", "id"])
data class CachedMemoryEntity(
    val subjectId: String,
    val id: String,
    val type: String,
    val status: String,
    /** ISO-8601 text: exact to the nanosecond and readable in a database dump. */
    val recordedAt: String,
    val characterScope: String,
    val importance: String,
    val source: String,
    val provenanceSessionId: String?,
    val provenanceMessageId: String?,
    val provenanceExcerpt: String,
    val provenanceExtractor: String,
    val profileAttribute: String?,
    val profileValue: String?,
    val eventTitle: String?,
    val eventScheduledFor: String?,
    val eventLocation: String?,
    val episodeSummary: String?,
    val episodeOccurredAt: String?,
    val episodeEmotionalTone: String?,
    /** Unit-separator joined; see [RelationsCodec]. */
    val episodeRelations: String?,
    val relationName: String?,
    val relationRole: String?,
    val relationNote: String?,
)

/**
 * When this subject's full set was last seen.
 *
 * Its own table rather than a derived value, and deliberately not
 * `CanonicalMemory.recordedAt`: that says when the companion learned a fact, this says when the watch
 * last saw the whole set.
 */
@Entity(tableName = "memory_cache_meta")
data class MemoryCacheMetaEntity(
    @PrimaryKey val subjectId: String,
    val lastFullSyncAt: String,
)

@Dao
abstract class MemoryCacheDao {

    @Query("SELECT * FROM cached_memory WHERE subjectId = :subjectId")
    abstract suspend fun records(subjectId: String): List<CachedMemoryEntity>

    @Upsert
    abstract suspend fun upsert(records: List<CachedMemoryEntity>)

    @Query("DELETE FROM cached_memory WHERE subjectId = :subjectId AND id = :id")
    abstract suspend fun deleteById(subjectId: String, id: String)

    @Query("DELETE FROM cached_memory WHERE subjectId = :subjectId")
    abstract suspend fun deleteSubject(subjectId: String)

    @Query("SELECT lastFullSyncAt FROM memory_cache_meta WHERE subjectId = :subjectId")
    abstract suspend fun lastFullSyncAt(subjectId: String): String?

    @Upsert
    abstract suspend fun putMeta(meta: MemoryCacheMetaEntity)

    @Query("DELETE FROM memory_cache_meta WHERE subjectId = :subjectId")
    abstract suspend fun deleteMeta(subjectId: String)

    /**
     * The whole replacement, in one transaction.
     *
     * Room wraps a `@Transaction` method, so a reader cannot observe the delete without the upsert, or
     * the records without the timestamp.
     */
    @Transaction
    open suspend fun replaceSubject(subjectId: String, records: List<CachedMemoryEntity>, syncedAt: String) {
        deleteSubject(subjectId)
        upsert(records)
        putMeta(MemoryCacheMetaEntity(subjectId = subjectId, lastFullSyncAt = syncedAt))
    }

    @Transaction
    open suspend fun clearSubject(subjectId: String) {
        deleteSubject(subjectId)
        deleteMeta(subjectId)
    }
}

@Database(
    entities = [CachedMemoryEntity::class, MemoryCacheMetaEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MemoryCacheDatabase : RoomDatabase() {
    abstract fun memoryCacheDao(): MemoryCacheDao
}
