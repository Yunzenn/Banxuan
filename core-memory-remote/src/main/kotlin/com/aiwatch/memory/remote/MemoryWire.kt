package com.aiwatch.memory.remote

import com.aiwatch.memory.CanonicalMemory
import com.aiwatch.memory.CharacterScope
import com.aiwatch.memory.EpisodeEdit
import com.aiwatch.memory.EpisodeMemory
import com.aiwatch.memory.EventEdit
import com.aiwatch.memory.EventMemory
import com.aiwatch.memory.Importance
import com.aiwatch.memory.MemoryEdit
import com.aiwatch.memory.MemoryId
import com.aiwatch.memory.MemoryQuery
import com.aiwatch.memory.MemorySource
import com.aiwatch.memory.MemoryStatus
import com.aiwatch.memory.MemoryType
import com.aiwatch.memory.ProfileEdit
import com.aiwatch.memory.ProfileMemory
import com.aiwatch.memory.Provenance
import com.aiwatch.memory.RelationEdit
import com.aiwatch.memory.RelationMemory
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.time.Instant

/**
 * The wire form of a canonical memory.
 *
 * **This is a transport projection, not a third schema.** Field names, enum spellings and instant
 * formatting are the ones the shared contract `evidence/contracts/canonical-memory-v2.json` already
 * uses, and the mapping is one-to-one with [CanonicalMemory]: nothing is invented here, nothing is
 * dropped. A codec that introduced its own vocabulary would quietly create the second memory definition
 * this project has spent several increments avoiding.
 *
 * Both languages read the same golden payloads in
 * `evidence/contracts/memory-http-v1-golden.json`, so the boundary is verified rather than assumed.
 *
 * Note the two version numbers are different things and must not be conflated: `v1` names this HTTP
 * surface, `v2` names the canonical semantic contract it carries.
 */
object MemoryWire {

    // ---------------------------------------------------------------- records

    fun encodeRecord(memory: CanonicalMemory): JsonObject {
        val json = JsonObject()
        json.addProperty("id", memory.id.value)
        json.addProperty("type", memory.type.name)
        json.addProperty("status", memory.status.name)
        json.addProperty("recordedAt", memory.recordedAt.toString())
        json.addProperty("characterScope", memory.characterScope.id)
        json.addProperty("importance", memory.importance.name)
        json.addProperty("source", memory.source.name)
        json.add("provenance", JsonObject().apply {
            addProperty("sessionId", memory.provenance.sessionId)
            addProperty("messageId", memory.provenance.messageId)
            addProperty("excerpt", memory.provenance.excerpt)
            addProperty("extractor", memory.provenance.extractor)
        })
        when (memory) {
            is ProfileMemory -> {
                json.addProperty("attribute", memory.attribute)
                json.addProperty("value", memory.value)
            }

            is EventMemory -> {
                json.addProperty("title", memory.title)
                json.addProperty("scheduledFor", memory.scheduledFor?.toString())
                json.addProperty("location", memory.location)
            }

            is EpisodeMemory -> {
                json.addProperty("summary", memory.summary)
                json.addProperty("occurredAt", memory.occurredAt.toString())
                json.addProperty("emotionalTone", memory.emotionalTone)
                json.add("relations", JsonArray().apply { memory.relations.forEach { add(it) } })
            }

            is RelationMemory -> {
                json.addProperty("name", memory.name)
                json.addProperty("role", memory.role)
                json.addProperty("note", memory.note)
            }
        }
        return json
    }

    fun decodeRecord(json: JsonObject): CanonicalMemory {
        val id = MemoryId(json.string("id"))
        val status = enumOf<MemoryStatus>(json, "status")
        val recordedAt = instant(json.string("recordedAt"))
        val scope = CharacterScope(json.stringOrNull("characterScope") ?: "xiaozhi")
        val importance = enumOf<Importance>(json, "importance")
        val source = enumOf<MemorySource>(json, "source")
        val provenance = json.getAsJsonObject("provenance")?.let {
            Provenance(
                sessionId = it.stringOrNull("sessionId"),
                messageId = it.stringOrNull("messageId"),
                excerpt = it.stringOrNull("excerpt") ?: "",
                extractor = it.stringOrNull("extractor") ?: "",
            )
        } ?: Provenance(null, null, "", "")

        return when (val type = json.string("type")) {
            "PROFILE" -> ProfileMemory(
                id, importance, status, recordedAt, source, provenance, scope,
                attribute = json.string("attribute"),
                value = json.string("value"),
            )

            "EVENT" -> EventMemory(
                id, importance, status, recordedAt, source, provenance, scope,
                title = json.string("title"),
                scheduledFor = json.stringOrNull("scheduledFor")?.let(::instant),
                location = json.stringOrNull("location"),
            )

            "EPISODE" -> EpisodeMemory(
                id, importance, status, recordedAt, source, provenance, scope,
                summary = json.string("summary"),
                occurredAt = instant(json.string("occurredAt")),
                emotionalTone = json.stringOrNull("emotionalTone"),
                relations = json.getAsJsonArray("relations")?.map { it.asString }?.toSet() ?: emptySet(),
            )

            "RELATION" -> RelationMemory(
                id, importance, status, recordedAt, source, provenance, scope,
                name = json.string("name"),
                role = json.string("role"),
                note = json.stringOrNull("note"),
            )

            else -> throw MemoryWireFormatException("unknown memory type '$type'")
        }
    }

    // ---------------------------------------------------------------- query

    fun encodeQuery(query: MemoryQuery): JsonObject = JsonObject().apply {
        addProperty("text", query.text)
        add("types", JsonArray().apply { query.types.forEach { add(it.name) } })
        addProperty("characterScope", query.characterScope?.id)
        add("statuses", JsonArray().apply { query.statuses.forEach { add(it.name) } })
        addProperty("from", query.from?.toString())
        addProperty("to", query.to?.toString())
        addProperty("limit", query.limit)
    }

    fun decodeQuery(json: JsonObject): MemoryQuery = MemoryQuery(
        text = json.stringOrNull("text"),
        types = json.getAsJsonArray("types")?.map { MemoryType.valueOf(it.asString) }?.toSet()
            ?: MemoryType.entries.toSet(),
        characterScope = json.stringOrNull("characterScope")?.let(::CharacterScope),
        statuses = json.getAsJsonArray("statuses")?.map { MemoryStatus.valueOf(it.asString) }?.toSet()
            ?: setOf(MemoryStatus.CONFIRMED),
        from = json.stringOrNull("from")?.let(::instant),
        to = json.stringOrNull("to")?.let(::instant),
        limit = json.get("limit")?.asInt ?: MemoryQuery.DEFAULT_LIMIT,
    )

    // ---------------------------------------------------------------- edit

    fun encodeEdit(edit: MemoryEdit): JsonObject = JsonObject().apply {
        addProperty("type", edit.type.name)
        addProperty("editedAt", edit.editedAt.toString())
        when (edit) {
            is ProfileEdit -> {
                addProperty("attribute", edit.attribute)
                addProperty("value", edit.value)
            }

            is EventEdit -> {
                addProperty("title", edit.title)
                addProperty("scheduledFor", edit.scheduledFor?.toString())
                addProperty("location", edit.location)
            }

            is EpisodeEdit -> {
                addProperty("summary", edit.summary)
                addProperty("occurredAt", edit.occurredAt.toString())
                addProperty("emotionalTone", edit.emotionalTone)
                add("relations", JsonArray().apply { edit.relations.forEach { add(it) } })
            }

            is RelationEdit -> {
                addProperty("name", edit.name)
                addProperty("role", edit.role)
                addProperty("note", edit.note)
            }
        }
    }

    fun decodeEdit(json: JsonObject): MemoryEdit {
        val editedAt = instant(json.string("editedAt"))
        return when (val type = json.string("type")) {
            "PROFILE" -> ProfileEdit(editedAt, json.string("attribute"), json.string("value"))
            "EVENT" -> EventEdit(
                editedAt,
                json.string("title"),
                json.stringOrNull("scheduledFor")?.let(::instant),
                json.stringOrNull("location"),
            )

            "EPISODE" -> EpisodeEdit(
                editedAt,
                json.string("summary"),
                instant(json.string("occurredAt")),
                json.stringOrNull("emotionalTone"),
                json.getAsJsonArray("relations")?.map { it.asString }?.toSet() ?: emptySet(),
            )

            "RELATION" -> RelationEdit(
                editedAt,
                json.string("name"),
                json.string("role"),
                json.stringOrNull("note"),
            )

            else -> throw MemoryWireFormatException("unknown edit type '$type'")
        }
    }

    // ---------------------------------------------------------------- helpers

    private inline fun <reified T : Enum<T>> enumOf(json: JsonObject, key: String): T =
        try {
            enumValueOf<T>(json.string(key))
        } catch (_: IllegalArgumentException) {
            throw MemoryWireFormatException("unknown $key '${json.stringOrNull(key)}'")
        }

    private fun instant(text: String): Instant =
        try {
            Instant.parse(text)
        } catch (_: java.time.format.DateTimeParseException) {
            throw MemoryWireFormatException("unparseable instant '$text'")
        }

    private fun JsonObject.string(key: String): String =
        stringOrNull(key) ?: throw MemoryWireFormatException("missing '$key'")

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString
}

/**
 * A payload that does not match the contract.
 *
 * The client fails closed on this rather than guessing: a memory it cannot parse is a memory it must not
 * act on, and silently substituting a default would fabricate a fact.
 */
class MemoryWireFormatException(message: String) : IllegalArgumentException(message)
