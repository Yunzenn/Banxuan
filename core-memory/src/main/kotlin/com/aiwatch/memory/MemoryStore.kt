package com.aiwatch.memory

/**
 * The storage port underneath [MemoryGateway].
 *
 * Deliberately primitive. It can fetch, store, delete and enumerate, and that is all: it owns no
 * de-duplication, no lifecycle transition and no recall policy. Those are semantics, and they live in
 * exactly one place - [DefaultMemoryGateway] - so there is one implementation to reason about and one
 * implementation to test. A store that grew its own dedup rules would be a second, quietly different
 * definition of what the companion knows.
 *
 * This is also the boundary the canonical store sits behind. The canonical store is server-side, so the
 * production implementation of this port is a remote client. A local implementation is a cache for the
 * watch and must never become a second authority on memory.
 *
 * Every method returns records exactly as stored. Filters, ordering and limits are policy, so [list]
 * returns every record in every status for every character and lets the gateway decide.
 */
interface MemoryStore {

    /** The record with this id, or null. */
    suspend fun getById(id: MemoryId): CanonicalMemory?

    /**
     * Every record whose [CanonicalMemory.scopedIdentity] matches, **in any status**.
     *
     * Plural on purpose. A fact may exist as a confirmed memory and, at the same time, as a staged
     * proposal to change it; collapsing that to a single result would force the policy decision into the
     * store, which is the one thing this port must not do.
     */
    suspend fun findAllByScopedIdentity(identity: ScopedMemoryIdentity): List<CanonicalMemory>

    /** Insert or replace the record, keyed by [CanonicalMemory.id]. */
    suspend fun put(memory: CanonicalMemory)

    /** Remove the record. Returns true if something was removed. */
    suspend fun delete(id: MemoryId): Boolean

    /** Every record the store holds. */
    suspend fun list(): List<CanonicalMemory>
}
