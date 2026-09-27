package com.aiwatch.memory.cache

import com.aiwatch.memory.CanonicalMemory
import com.aiwatch.memory.MemoryId
import java.time.Instant

/**
 * A last-known snapshot of one subject's memory, with the freshness of its own data.
 *
 * [lastFullSyncAt] is deliberately part of the value rather than something a caller has to ask for
 * separately. A cache that hands back records without saying how old they are invites exactly the
 * failure this module exists to avoid: an offline screen showing yesterday's beliefs as if they were
 * current. "What does she believe about me" is a trust surface, and a trust surface that quietly shows
 * stale data is worse than one that says it is offline.
 *
 * It is not `CanonicalMemory.recordedAt`. That is when the companion learned a fact; this is when the
 * watch last saw the whole set. Two different questions, two different fields.
 */
data class CachedMemorySnapshot(
    val records: List<CanonicalMemory>,
    val lastFullSyncAt: Instant?,
)

/**
 * A durable last-known copy of the canonical store, for one subject at a time.
 *
 * **This is not a memory service and must never become one.** It has no `remember`, no `edit`, no
 * `confirm`, no `reject`, no de-duplication, no identity-conflict rule and no recall policy. Those live
 * in the canonical authority, and a cache that grew them would move the decisions that define what the
 * companion knows back onto the watch - which is the architecture this project deliberately chose
 * against.
 *
 * For the same reason this module does not implement `MemoryStore`. Doing so would make
 * `DefaultMemoryGateway(RoomMemoryStore)` compile, and a local gateway executing canonical semantics is
 * a second authority that no review would necessarily catch.
 *
 * Every method here is mechanical: read what was stored, replace it wholesale, patch single rows, or
 * forget a subject. Nothing interprets.
 *
 * **No offline mutation queue.** A confirmed, rejected, edited or deleted memory is only real once the
 * authority has accepted it. There is no pending table, no dirty flag and no retry worker, because
 * "she will stop believing this eventually" is not a state a product about trust can be in.
 */
interface MemoryCache {

    /** Everything stored for this subject, plus when its full set was last seen. */
    suspend fun snapshot(subjectId: String): CachedMemorySnapshot

    /**
     * Replace this subject's entire cached set.
     *
     * The name is explicit because the operation is destructive: any cached record absent from [records]
     * is deleted. It is only correct for a **complete** result - every status, every character scope,
     * the whole set for the subject. Handing it a filtered list, or a `recall` result, would silently
     * delete everything the filter excluded, and a recall result would leave three to eight memories as
     * the entire cached world.
     *
     * Delete, upsert and the timestamp update happen in one transaction, so a reader never observes a
     * half-replaced cache.
     */
    suspend fun replaceFullSnapshot(
        subjectId: String,
        records: List<CanonicalMemory>,
        syncedAt: Instant,
    )

    /** Patch individual records, for a change the authority already reported. Mechanical, no merging. */
    suspend fun upsert(subjectId: String, records: List<CanonicalMemory>)

    /** Drop one record, for a deletion the authority already reported. */
    suspend fun delete(subjectId: String, id: MemoryId)

    /**
     * Forget everything about a subject.
     *
     * Needed whenever identity is reset or rebound: without it the next subject to use this device would
     * be shown the previous one's last-known memories.
     */
    suspend fun clearSubject(subjectId: String)
}
