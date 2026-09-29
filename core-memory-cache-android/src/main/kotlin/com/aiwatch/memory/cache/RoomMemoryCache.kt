package com.aiwatch.memory.cache

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.aiwatch.memory.CanonicalMemory
import com.aiwatch.memory.CharacterScope
import com.aiwatch.memory.Importance
import com.aiwatch.memory.MemoryId
import com.aiwatch.memory.MemorySource
import com.aiwatch.memory.MemoryStatus
import com.aiwatch.memory.ProfileMemory
import com.aiwatch.memory.Provenance
import com.aiwatch.memory.EpisodeMemory
import com.aiwatch.memory.EventMemory
import com.aiwatch.memory.RelationMemory
import java.time.Instant

/**
 * The Room-backed [MemoryCache].
 *
 * Its whole job is the two mappings in this file plus passing calls through to the DAO. No rule about
 * what a memory means appears here, and none should ever be added: the moment this class decides
 * something, the watch is deciding it, and the canonical authority is no longer the authority.
 *
 * ### Why construction lives here and not in `:app`
 *
 * Room is an `implementation` dependency of this module, so `:app` has no Room on its compile
 * classpath - and it must stay that way. A composition root that called `Room.databaseBuilder`
 * directly would force a second, product-layer Room dependency, which is precisely how a persistence
 * choice leaks upward out of the module that owns it.
 *
 * [open] is therefore the only way production constructs this cache, and it returns the
 * [MemoryCache] interface rather than this class: `:app` learns that a cache exists and nothing about
 * how it is stored. The constructor stays `internal` so this module's own tests can still build one
 * over an in-memory database, which `:app` must never be able to do.
 */
class RoomMemoryCache internal constructor(
    private val database: MemoryCacheDatabase,
) : MemoryCache {

    private val dao = database.memoryCacheDao()

    /**
     * One read, not two.
     *
     * Both queries run inside a single `withTransaction`, because reading them separately is not the same
     * thing even though the writer is atomic: a reader can take the records before a
     * `replaceFullSnapshot` commits and the timestamp after it, and then hand the caller yesterday's
     * records labelled with the current sync time. On the trust surface that is the false claim the
     * freshness field exists to prevent.
     *
     * Room-KTX's `withTransaction` rather than a DAO method returning a wrapper type: a public DAO
     * function cannot expose an internal row type, and making that type public would widen this module's
     * API for no reason. The transaction is the same either way.
     */
    override suspend fun snapshot(subjectId: String): CachedMemorySnapshot = database.withTransaction {
        CachedMemorySnapshot(
            records = dao.records(subjectId).map { it.toMemory() },
            lastFullSyncAt = dao.lastFullSyncAt(subjectId)?.let(Instant::parse),
        )
    }

    override suspend fun replaceFullSnapshot(
        subjectId: String,
        records: List<CanonicalMemory>,
        syncedAt: Instant,
    ) {
        require(subjectId.isNotBlank()) { "a snapshot must name its subject" }
        dao.replaceSubject(
            subjectId = subjectId,
            records = records.map { it.toEntity(subjectId) },
            syncedAt = syncedAt.toString(),
        )
    }

    override suspend fun upsert(subjectId: String, records: List<CanonicalMemory>) {
        require(subjectId.isNotBlank()) { "an upsert must name its subject" }
        if (records.isEmpty()) return
        dao.upsert(records.map { it.toEntity(subjectId) })
    }

    override suspend fun delete(subjectId: String, id: MemoryId) {
        dao.deleteById(subjectId, id.value)
    }

    override suspend fun clearSubject(subjectId: String) {
        dao.clearSubject(subjectId)
    }

    companion object {

        /**
         * Open the one cache this process uses.
         *
         * Returns [MemoryCache] rather than [RoomMemoryCache] on purpose: the caller needs to store
         * last-known state, not to know that Room is how it is stored.
         *
         * `build()` does not touch the database file - Room opens it on first use - so holding this
         * instance for the process lifetime costs nothing until the cache is actually read.
         */
        fun open(
            context: Context,
            databaseName: String = "memory-cache.db",
        ): MemoryCache {
            val database = Room.databaseBuilder(
                context.applicationContext,
                MemoryCacheDatabase::class.java,
                databaseName,
            ).build()

            return RoomMemoryCache(database)
        }
    }
}

/**
 * Relation names are prose, so the column needs a separator that prose cannot contain.
 *
 * The unit separator is a control character; the UI separator `、` and the ASCII comma are both legal
 * inside a name and therefore unsuitable.
 */
internal object RelationsCodec {
    private const val SEPARATOR = '\u001F'

    fun encode(relations: Set<String>): String = relations.joinToString(SEPARATOR.toString())

    fun decode(encoded: String?): Set<String> =
        encoded?.takeIf { it.isNotEmpty() }
            ?.split(SEPARATOR)
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
}

// ---------------------------------------------------------------------------- mapping

internal fun CanonicalMemory.toEntity(subjectId: String): CachedMemoryEntity {
    val base = CachedMemoryEntity(
        subjectId = subjectId,
        id = id.value,
        type = type.name,
        status = status.name,
        recordedAt = recordedAt.toString(),
        characterScope = characterScope.id,
        importance = importance.name,
        source = source.name,
        provenanceSessionId = provenance.sessionId,
        provenanceMessageId = provenance.messageId,
        provenanceExcerpt = provenance.excerpt,
        provenanceExtractor = provenance.extractor,
        profileAttribute = null,
        profileValue = null,
        eventTitle = null,
        eventScheduledFor = null,
        eventLocation = null,
        episodeSummary = null,
        episodeOccurredAt = null,
        episodeEmotionalTone = null,
        episodeRelations = null,
        relationName = null,
        relationRole = null,
        relationNote = null,
    )
    return when (this) {
        is ProfileMemory -> base.copy(profileAttribute = attribute, profileValue = value)
        is EventMemory -> base.copy(
            eventTitle = title,
            eventScheduledFor = scheduledFor?.toString(),
            eventLocation = location,
        )

        is EpisodeMemory -> base.copy(
            episodeSummary = summary,
            episodeOccurredAt = occurredAt.toString(),
            episodeEmotionalTone = emotionalTone,
            episodeRelations = RelationsCodec.encode(relations),
        )

        is RelationMemory -> base.copy(relationName = name, relationRole = role, relationNote = note)
    }
}

internal fun CachedMemoryEntity.toMemory(): CanonicalMemory {
    val id = MemoryId(id)
    val importance = Importance.valueOf(importance)
    val status = MemoryStatus.valueOf(status)
    val recordedAt = Instant.parse(recordedAt)
    val source = MemorySource.valueOf(source)
    val scope = CharacterScope(characterScope)
    val provenance = Provenance(
        sessionId = provenanceSessionId,
        messageId = provenanceMessageId,
        excerpt = provenanceExcerpt,
        extractor = provenanceExtractor,
    )

    return when (type) {
        "PROFILE" -> ProfileMemory(
            id = id, importance = importance, status = status, recordedAt = recordedAt, source = source,
            provenance = provenance, characterScope = scope,
            attribute = requireColumn(profileAttribute, "profileAttribute"),
            value = requireColumn(profileValue, "profileValue"),
        )

        "EVENT" -> EventMemory(
            id = id, importance = importance, status = status, recordedAt = recordedAt, source = source,
            provenance = provenance, characterScope = scope,
            title = requireColumn(eventTitle, "eventTitle"),
            scheduledFor = eventScheduledFor?.let(Instant::parse),
            location = eventLocation,
        )

        "EPISODE" -> EpisodeMemory(
            id = id, importance = importance, status = status, recordedAt = recordedAt, source = source,
            provenance = provenance, characterScope = scope,
            summary = requireColumn(episodeSummary, "episodeSummary"),
            occurredAt = Instant.parse(requireColumn(episodeOccurredAt, "episodeOccurredAt")),
            emotionalTone = episodeEmotionalTone,
            relations = RelationsCodec.decode(episodeRelations),
        )

        "RELATION" -> RelationMemory(
            id = id, importance = importance, status = status, recordedAt = recordedAt, source = source,
            provenance = provenance, characterScope = scope,
            name = requireColumn(relationName, "relationName"),
            role = requireColumn(relationRole, "relationRole"),
            note = relationNote,
        )

        else -> throw IllegalStateException("cached record $id has unknown type '$type'")
    }
}

/**
 * A column a record's type requires but which is null.
 *
 * Throwing rather than substituting an empty string: a cache row that cannot be reconstructed faithfully
 * must not be presented as a fact the companion holds. This is the cache's only "decision", and it is a
 * refusal.
 */
private fun requireColumn(value: String?, column: String): String =
    value ?: throw IllegalStateException("cached record is missing required column '$column'")
