package com.aiwatch.memory

import java.time.Instant

/**
 * A content-only correction to an existing memory.
 *
 * Deliberately **not** a replacement [CanonicalMemory]. Handing the gateway a whole record would let a
 * caller carry along `id`, `status`, `characterScope`, `importance`, `source` and `provenance`, which
 * are exactly the fields the gateway exists to guarantee. None of them is part of an edit: an edit says
 * what the user now believes the fact is, and the gateway decides what that means for everything else.
 *
 * One subtype per memory type, and the subtype is part of the contract. A `ProfileEdit` applies only to
 * a [ProfileMemory]; turning a PROFILE into an EVENT is a delete plus a create, not an edit, and it is
 * refused rather than guessed at.
 *
 * These types carry **no behaviour**. What an edit means - which statuses allow one, what happens to the
 * audit envelope, whether an identity may move - belongs to [MemoryGateway.edit] and its single
 * implementation, so it can be reasoned about and tested in one place instead of being distributed
 * across the edit types.
 */
sealed interface MemoryEdit {

    /** The memory type this edit can be applied to. */
    val type: MemoryType

    /** When the user made the correction. Becomes the record's `recordedAt` when something changed. */
    val editedAt: Instant
}

data class ProfileEdit(
    override val editedAt: Instant,
    val attribute: String,
    val value: String,
) : MemoryEdit {
    override val type: MemoryType get() = MemoryType.PROFILE
}

data class EventEdit(
    override val editedAt: Instant,
    val title: String,
    val scheduledFor: Instant?,
    val location: String?,
) : MemoryEdit {
    override val type: MemoryType get() = MemoryType.EVENT
}

data class EpisodeEdit(
    override val editedAt: Instant,
    val summary: String,
    val occurredAt: Instant,
    val emotionalTone: String?,
    val relations: Set<String>,
) : MemoryEdit {
    override val type: MemoryType get() = MemoryType.EPISODE
}

data class RelationEdit(
    override val editedAt: Instant,
    val name: String,
    val role: String,
    val note: String?,
) : MemoryEdit {
    override val type: MemoryType get() = MemoryType.RELATION
}

/** What an [MemoryGateway.edit] call did. An edit that changes nothing is observable, not silent. */
sealed interface EditOutcome {

    /** The content really changed. [previous] is the record as it was. */
    data class Updated(val previous: CanonicalMemory, val memory: CanonicalMemory) : EditOutcome

    /**
     * The edit left the content identical, so nothing was written at all.
     *
     * Not merely an optimisation: writing would have replaced `recordedAt`, `source` and `provenance`
     * with the edit's, destroying the record of where the fact originally came from - for a Save button
     * the user pressed without changing anything.
     */
    data class Unchanged(val existing: CanonicalMemory) : EditOutcome
}
