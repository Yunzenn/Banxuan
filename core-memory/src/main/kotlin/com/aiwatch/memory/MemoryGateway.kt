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
 * * [confirm] and [reject] throw [MemoryNotFoundException] for an unknown id, and
 *   [MemoryTransitionException] for a record that is not staged.
 * * [remember] de-duplicates by **scoped** identity - [CanonicalMemory.scopedIdentity], never
 *   [CanonicalMemory.identity] alone - so two characters never overwrite each other's memory of the same
 *   fact. It never creates a second confirmed record for a fact that is already known: the stored record
 *   keeps its [MemoryId] and takes the new content. It throws [MemoryNotConfirmedException] if handed a
 *   non-confirmed record.
 * * [forget] removes the record. After it returns true, no later [recall] or [list] may return it.
 * * [edit] changes content only. It preserves `id`, `status`, `characterScope` and `importance`, never
 *   promotes a staged record to confirmed, refuses a rejected one, and refuses an edited identity that
 *   already belongs to a different confirmed memory. An edit that changes nothing writes nothing.
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

    /** Write a confirmed memory, de-duplicating by scoped content identity. */
    suspend fun remember(memory: CanonicalMemory): RememberOutcome

    /**
     * Correct the content of an existing memory.
     *
     * This is the only way a memory's content changes after it exists, so the rules live here rather
     * than in whichever screen happens to offer an editor.
     *
     * **The envelope is not editable.** `id`, `status`, `characterScope` and `importance` are preserved,
     * because they are guarantees of this interface rather than things a user edits; a caller able to
     * carry them along could quietly move a memory to another character or promote a candidate. See
     * [MemoryEdit] for why an edit is not a replacement record.
     *
     * Lifecycle:
     * * a `CONFIRMED` memory stays confirmed;
     * * a `STAGED` memory stays staged - **editing is not confirming**, and the user must still accept
     *   it. Anything else would let one keystroke turn an unapproved candidate into something the
     *   companion treats as true, which is the trust model this product is built on;
     * * a `REJECTED` memory cannot be edited at all ([MemoryNotEditableException]). It stays a negative
     *   signal, and resurrecting it through an editor would be confirming by the back door.
     *
     * **Audit.** A real change sets `source` to [MemorySource.USER_EDIT], `recordedAt` to
     * [MemoryEdit.editedAt], and replaces the provenance with a user-edit one. The old `excerpt` is
     * dropped on purpose: it is the sentence the fact was originally extracted from, and after a
     * correction it is no longer the reason she believes it. The new reason is that the user said so.
     *
     * **No change means no write.** An edit whose content is identical returns [EditOutcome.Unchanged]
     * and touches nothing, so a Save pressed without changing anything cannot erase where the fact came
     * from.
     *
     * **Identity may move**, because that is what a correction often is: an event's date, a relation's
     * role, a preference's attribute. The same [MemoryId] moves with it, so any reference the user holds
     * still names the same memory. If the new identity is already held by a *different* confirmed
     * memory, the edit is refused with [MemoryIdentityConflictException] and **neither record is
     * touched**. Merging would delete one id and overwrite the other, breaking both the stable reference
     * and the user's idea of what she was editing; the honest answer is that a memory for that fact
     * already exists and she should deal with that one. A staged record is exempt from the check,
     * because "one known fact plus one pending correction" is a legitimate state.
     */
    suspend fun edit(id: MemoryId, edit: MemoryEdit): EditOutcome

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

/**
 * Thrown when a lifecycle transition is attempted from a status that does not allow it.
 *
 * Illegal transitions are refused rather than tolerated. Confirming something already rejected, or
 * rejecting something already confirmed, is not a no-op with a friendly face: silently accepting it would
 * let a decision the user made be undone without anyone recording that it was.
 */
class MemoryTransitionException(
    val id: MemoryId,
    val from: MemoryStatus,
    val to: MemoryStatus,
) : IllegalStateException("memory ${id.value} is $from and cannot become $to")

/**
 * Thrown when an edit names a memory whose status does not allow one.
 *
 * A rejected memory is a decision the user already made. Being able to edit it would make the editor a
 * way to confirm it without ever saying so.
 */
class MemoryNotEditableException(val id: MemoryId, val status: MemoryStatus) :
    IllegalStateException("memory ${id.value} is $status, which cannot be edited")

/**
 * Thrown when an edit's kind does not match the record it is applied to.
 *
 * Changing a PROFILE into an EVENT is a delete plus a create, not an edit, and this interface will not
 * pretend otherwise.
 */
class MemoryTypeMismatchException(
    val id: MemoryId,
    val expected: MemoryType,
    val actual: MemoryType,
) : IllegalArgumentException("memory ${id.value} is $actual but the edit is for $expected")

/**
 * Thrown when an edit would move a confirmed memory onto a fact another confirmed memory already holds.
 *
 * Nothing is written when this is thrown: neither the edited record nor the one it collided with. The
 * alternative - merging - would consume one [MemoryId] and overwrite the other, which destroys both the
 * stable reference and whatever the user thought she was editing.
 */
class MemoryIdentityConflictException(
    val id: MemoryId,
    val conflictingId: MemoryId,
    val identity: ScopedMemoryIdentity,
) : IllegalStateException(
    "memory ${id.value} would become the same fact as ${conflictingId.value} (${identity.identity.key})",
)
