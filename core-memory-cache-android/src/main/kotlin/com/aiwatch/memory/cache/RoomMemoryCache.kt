package com.aiwatch.memory.cache

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
 */
class RoomMemoryCache(private val database: MemoryCacheDatabase) : MemoryCache {

    private val dao = database.memoryCacheDao()

    /**
     * One read, not two.
     *
     * `dao.snapshot` takes the records and the freshness inside a single Room transaction. Calling
     * `records()` and `lastFullSyncAt()` separately would let a `replaceFullSnapshot` commit between
     * them, and the caller would receive the previous records labelled with the new sync time - telling
     * the user that yesterday's memories had just been synced.
     */
    override suspend fun snapshot(subjectId: String): CachedMemorySnapshot {
        val rows = dao.snapshot(subjectId)
        return CachedMemorySnapshot(
            records = rows.records.map { it.toMemory() },
            lastFullSyncAt = rows.lastFullSyncAt?.let(Instant::parse),
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
