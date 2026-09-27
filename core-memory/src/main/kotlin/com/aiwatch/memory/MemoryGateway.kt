package com.aiwatch.memory

import java.time.Instant

/**
 * The only memory surface the rest of the product is allowed to depend on.
 *
 * The point of this interface is containment. Whatever actually stores memories - Mem0 today, something
 * else later, a server-side service the whole time - the app talks to this and never to a vendor SDK.
 * A Mem0 client object must not appear in `:app`, and no caller may construct a query in terms of
 * vectors, collections, or a vendor's filter syntax. Those are substrate concerns.
 *
 * The canonical store is server-side, so a production implementation of this interface is a remote
 * client. The interface stays `suspend`-only and free of transport types so that a local
 * implementation, a test double, and a future remote client are interchangeable.
 */

/** What a [MemoryGateway.remember] call actually did. De-duplication is observable, not silent. */
sealed interface RememberOutcome {
    /** No memory with this [MemoryIdentity] existed. */
    data class Created(val memory: CanonicalMemory) : RememberOutcome

    /** The same fact was already known with different content: one record, corrected in place. */
    data class Updated(val previous: CanonicalMemory, val memory: CanonicalMemory) : RememberOutcome

    /** The same fact was already known with identical content. Nothing was written. */
    data class Unchanged(val existing: CanonicalMemory) : RememberOutcome
}

/**
 * A recall request.
 *
 * [statuses] defaults to [MemoryStatus.CONFIRMED] alone, and that default is a safety property rather
 * than a convenience: a staged candidate the user has not accepted must never reach the conversation.
 * A caller that wants to see candidates asks for them explicitly through [MemoryGateway.list], which is
 * what the "我的记忆" screen does.
 *
 * [limit] is small on purpose. Recall feeds a latency budget, and in v0.4 it is a structured filter
 * over typed records - not a similarity search.
 */
data class MemoryQuery(
    /** Optional free-text filter. Absent means "everything matching the other filters". */
    val text: String? = null,
    val types: Set<MemoryType> = MemoryType.entries.toSet(),
    val characterScope: CharacterScope? = null,
    val statuses: Set<MemoryStatus> = setOf(MemoryStatus.CONFIRMED),
    /** Inclusive lower bound, applied to the type's own time: event schedule or episode occurrence. */
    val from: Instant? = null,
    /** Inclusive upper bound, applied the same way as [from]. */
    val to: Instant? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    companion object {
        const val DEFAULT_LIMIT: Int = 8
    }
}

/**
 * Read/write access to the companion's memory.
 *
 * Lifecycle is explicit. An extraction result is [stage]d, which makes it visible to the user and
 * invisible to the conversation; only [confirm] makes it something the companion may act on. [reject]
 * keeps the record rather than deleting it, so a rejected candidate is not re-proposed forever.
 *
 * Implementations must enforce these contracts:
 *
 * * [stage] accepts only [MemoryStatus.STAGED] records and stores them as staged.
 * * [confirm] and [reject] throw [MemoryNotFoundException] for an unknown id.
 * * [remember] de-duplicates by [CanonicalMemory.identity]; it never creates a second record for a fact
 *   that already exists. It throws [MemoryNotConfirmedException] if handed a non-confirmed record.
 * * [forget] removes the record. After it returns true, no later [recall] or [list] may return it.
 * * [recall] never returns a memory whose status is outside [MemoryQuery.statuses].
 */
interface MemoryGateway {

    /**
     * Propose extraction results for confirmation. Staged memories appear in "我的记忆" and never in
     * [recall]'s default result.
     */
    suspend fun stage(candidates: List<CanonicalMemory>): List<CanonicalMemory>

    /** Accept a staged memory: it becomes available to the conversation. */
    suspend fun confirm(id: MemoryId): CanonicalMemory

    /** Reject a staged memory. The record is retained as a negative signal, not deleted. */
    suspend fun reject(id: MemoryId): CanonicalMemory

    /** Write a confirmed memory, de-duplicating by content identity. */
    suspend fun remember(memory: CanonicalMemory): RememberOutcome

    /** Retrieve memories for the current turn. Staged candidates are excluded by default. */
    suspend fun recall(query: MemoryQuery = MemoryQuery()): List<CanonicalMemory>

    /**
     * Enumerate memories for "我的记忆". Unlike [recall] this defaults to *every* status, because the
     * screen's whole purpose is to show what the companion believes and what she is only proposing.
     */
    suspend fun list(
        statuses: Set<MemoryStatus> = MemoryStatus.entries.toSet(),
        characterScope: CharacterScope? = null,
    ): List<CanonicalMemory>

    /** Delete a memory permanently. Returns false if there was nothing to delete. */
    suspend fun forget(id: MemoryId): Boolean
}

/** Thrown when an operation names a memory that does not exist. */
class MemoryNotFoundException(val id: MemoryId) :
    IllegalArgumentException("no memory with id ${id.value}")

/**
 * Thrown when a record is written through a path that requires confirmation.
 *
 * Reaching this is a bug in the caller, not user input: staging exists so that nothing unconfirmed can
 * be written as truth.
 */
class MemoryNotConfirmedException(val id: MemoryId, val status: MemoryStatus) :
    IllegalArgumentException("memory ${id.value} is $status, expected CONFIRMED")

/** Thrown when a record is staged through a path that requires a candidate produced by extraction. */
class MemoryNotStageableException(val id: MemoryId, val status: MemoryStatus) :
    IllegalArgumentException("memory ${id.value} is $status, expected STAGED")
