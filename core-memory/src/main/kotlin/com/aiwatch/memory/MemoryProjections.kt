package com.aiwatch.memory

import java.time.Instant

/**
 * Derived views over a [CanonicalMemory] that the gateway needs and the model deliberately does not
 * expose.
 *
 * These stay `internal` on purpose. [CanonicalMemory]'s public surface is the schema itself; "the string
 * this record compares equal by", "which instant this record sits at on a timeline" and "the text a
 * substring filter may look at" are implementation details of one particular gateway. Widening the
 * public model to carry them would make every future implementation inherit this one's choices.
 */

/**
 * Everything about the record that makes its *content*, identity included.
 *
 * [CanonicalMemory.identity] cannot serve as a content comparison, because for a
 * [ProfileMemory] it deliberately excludes the value. `food.dislike = 香菜` and `food.dislike = 芹菜`
 * share an identity and are not the same content; without this second projection, correcting a
 * preference would be reported as "unchanged" and the correction would be silently dropped.
 */
internal val CanonicalMemory.contentFingerprint: String
    get() = when (this) {
        is ProfileMemory ->
            normalizeAttributePath(attribute) + "|" + normalizeFact(value)

        is EventMemory ->
            identity.key + "|" + normalizeFact(location ?: "")

        is EpisodeMemory ->
            identity.key + "|" + normalizeFact(emotionalTone ?: "") + "|" +
                relations.map(::normalizeFact).sorted().joinToString(",")

        is RelationMemory ->
            identity.key + "|" + normalizeFact(note ?: "")
    }

/**
 * Where this record sits on a timeline, used to order results newest first.
 *
 * There is no scoring here and that is intentional: relevance ranking is v0.5. This is only enough to
 * make recall's output deterministic and readable.
 */
internal val CanonicalMemory.timelineAt: Instant
    get() = when (this) {
        is EventMemory -> scheduledFor ?: recordedAt
        is EpisodeMemory -> occurredAt
        is ProfileMemory, is RelationMemory -> recordedAt
    }

/**
 * The instant a time window is applied to, or null when the type has no time of its own.
 *
 * A [ProfileMemory] and a [RelationMemory] are timeless facts: they are not "at" any moment, so they are
 * neither inside nor outside a window. Callers see them excluded from a windowed query rather than
 * matched arbitrarily, which keeps `from`/`to` meaning "when" instead of "sometimes when".
 */
internal val CanonicalMemory.windowedAt: Instant?
    get() = when (this) {
        is EventMemory -> scheduledFor
        is EpisodeMemory -> occurredAt
        is ProfileMemory, is RelationMemory -> null
    }

/**
 * The text a substring filter may inspect.
 *
 * Recall's text filter in v0.4 is a plain normalised substring match and nothing more. It is written down
 * here so the limitation is explicit: this is not retrieval, and it is not allowed to grow into a
 * ranking system inside the gateway.
 */
internal val CanonicalMemory.searchableText: String
    get() = when (this) {
        is ProfileMemory -> attribute + " " + value
        is EventMemory -> listOfNotNull(title, location).joinToString(" ")
        is EpisodeMemory ->
            (listOfNotNull(summary, emotionalTone) + relations).joinToString(" ")

        is RelationMemory -> listOfNotNull(name, role, note).joinToString(" ")
    }

/** Same record, different id. Used to keep an id stable when a fact is corrected. */
internal fun CanonicalMemory.withId(id: MemoryId): CanonicalMemory = when (this) {
    is ProfileMemory -> copy(id = id)
    is EventMemory -> copy(id = id)
    is EpisodeMemory -> copy(id = id)
    is RelationMemory -> copy(id = id)
}

/**
 * The extractor label recorded when the user corrects a fact herself.
 *
 * Versioned because it is audit data: it should be possible to tell later which generation of the
 * editor produced a correction. The shared contract in
 * `evidence/contracts/canonical-memory-v2.json` spells this value out, so both implementations write
 * the same string rather than each inventing its own.
 */
const val USER_EDIT_EXTRACTOR: String = "user-edit-v1"

/**
 * The record with its audit envelope replaced.
 *
 * Used by an edit, which is a new *reason* to believe something rather than new evidence from the same
 * source: the fact now comes from the user, not from the sentence it was originally extracted from, so
 * `recordedAt`, `source` and `provenance` all move together. Content is untouched.
 */
internal fun CanonicalMemory.withAudit(
    source: MemorySource,
    recordedAt: Instant,
    provenance: Provenance,
): CanonicalMemory = when (this) {
    is ProfileMemory -> copy(source = source, recordedAt = recordedAt, provenance = provenance)
    is EventMemory -> copy(source = source, recordedAt = recordedAt, provenance = provenance)
    is EpisodeMemory -> copy(source = source, recordedAt = recordedAt, provenance = provenance)
    is RelationMemory -> copy(source = source, recordedAt = recordedAt, provenance = provenance)
}
