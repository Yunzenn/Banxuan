package com.aiwatch.memory

import java.time.Instant

/**
 * The memory semantics engine.
 *
 * Everything that decides *what a memory means* lives here and nowhere else: de-duplication by scoped
 * identity, the staged/confirmed/rejected transitions, id stability, and what recall is allowed to
 * return. [MemoryStore] supplies storage primitives and is assumed to have no opinions, so this class can
 * be tested completely without a database, a network, or an Android device.
 *
 * Two invariants are maintained here rather than assumed of the store:
 *
 * * At most one record per [ScopedMemoryIdentity] may be `CONFIRMED`.
 * * A record's [MemoryId] survives a correction, so a reference the user is holding - an open edit, a
 *   delete she is about to confirm - still names the same fact afterwards.
 */
class DefaultMemoryGateway(private val store: MemoryStore) : MemoryGateway {

    override suspend fun stage(candidates: List<CanonicalMemory>): List<CanonicalMemory> {
        candidates.forEach {
            if (it.status != MemoryStatus.STAGED) {
                throw MemoryNotStageableException(it.id, it.status)
            }
        }
        candidates.forEach { store.put(it) }
        return candidates
    }

    override suspend fun confirm(id: MemoryId): CanonicalMemory {
        val candidate = store.getById(id) ?: throw MemoryNotFoundException(id)
        if (candidate.status != MemoryStatus.STAGED) {
            throw MemoryTransitionException(id, candidate.status, MemoryStatus.CONFIRMED)
        }

        val existing = confirmedFor(candidate.scopedIdentity)
        val confirmed = if (existing == null) {
            candidate.withStatus(MemoryStatus.CONFIRMED)
        } else {
            // The user has just accepted a change to a fact the companion already treats as true. The
            // confirmed record is corrected in place and keeps its id: the alternative - a second
            // confirmed record, or a new id for the same fact - would either duplicate her memory or
            // break any reference she is already holding.
            candidate.withId(existing.id).withStatus(MemoryStatus.CONFIRMED)
        }

        store.put(confirmed)
        if (existing != null) {
            store.delete(candidate.id)
        }
        return confirmed
    }

    override suspend fun reject(id: MemoryId): CanonicalMemory {
        val candidate = store.getById(id) ?: throw MemoryNotFoundException(id)
        if (candidate.status != MemoryStatus.STAGED) {
            throw MemoryTransitionException(id, candidate.status, MemoryStatus.REJECTED)
        }

        val rejected = candidate.withStatus(MemoryStatus.REJECTED)
        store.put(rejected)
        return rejected
    }

    override suspend fun remember(memory: CanonicalMemory): RememberOutcome {
        if (memory.status != MemoryStatus.CONFIRMED) {
            throw MemoryNotConfirmedException(memory.id, memory.status)
        }

        val existing = confirmedFor(memory.scopedIdentity)
            ?: return RememberOutcome.Created(memory).also { store.put(memory) }

        if (existing.contentFingerprint == memory.contentFingerprint) {
            return RememberOutcome.Unchanged(existing)
        }

        // Keep the stored id and take the new content: the correction is a new value for a fact she
        // already knows, not a new fact.
        val updated = memory.withId(existing.id)
        store.put(updated)
        return RememberOutcome.Updated(existing, updated)
    }

    override suspend fun recall(query: MemoryQuery): List<CanonicalMemory> =
        store.list()
            .asSequence()
            .filter { it.status in query.statuses }
            .filter { it.type in query.types }
            .filter { query.characterScope == null || it.characterScope == query.characterScope }
            .filter { it.withinWindow(query.from, query.to) }
            .filter { it.matchesText(query.text) }
            .sortedByDescending { it.timelineAt }
            .take(query.limit)
            .toList()

    override suspend fun list(
        statuses: Set<MemoryStatus>,
        characterScope: CharacterScope?,
    ): List<CanonicalMemory> =
        store.list()
            .filter { it.status in statuses }
            .filter { characterScope == null || it.characterScope == characterScope }
            .sortedByDescending { it.recordedAt }

    override suspend fun forget(id: MemoryId): Boolean = store.delete(id)

    /**
     * The confirmed record for a fact, if there is one.
     *
     * This class maintains the "at most one confirmed record per scoped identity" invariant, so this
     * normally has a single candidate. The defensive maximum keeps the choice deterministic - the most
     * recently recorded one wins - if a store is ever handed records written by something else.
     */
    private suspend fun confirmedFor(identity: ScopedMemoryIdentity): CanonicalMemory? =
        store.findAllByScopedIdentity(identity)
            .filter { it.status == MemoryStatus.CONFIRMED }
            .maxByOrNull { it.recordedAt }

    private fun CanonicalMemory.withinWindow(from: Instant?, to: Instant?): Boolean {
        if (from == null && to == null) {
            return true
        }
        val at = windowedAt ?: return false
        if (from != null && at.isBefore(from)) {
            return false
        }
        if (to != null && at.isAfter(to)) {
            return false
        }
        return true
    }

    private fun CanonicalMemory.matchesText(text: String?): Boolean {
        val needle = normalizeFact(text ?: return true)
        return needle.isEmpty() || normalizeFact(searchableText).contains(needle)
    }
}
