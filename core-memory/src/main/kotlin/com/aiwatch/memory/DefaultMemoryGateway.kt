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
        if (existing == null) {
            val confirmed = candidate.withStatus(MemoryStatus.CONFIRMED)
            store.put(confirmed)
            return confirmed
        }

        // A proposal about a fact she already treats as true. Whether it changes anything depends on
        // content, not on identity: a ProfileMemory's identity deliberately excludes its value, so an
        // identity comparison here would report a genuine correction as no change at all.
        if (existing.contentFingerprint == candidate.contentFingerprint) {
            // The user confirmed something already true. Drop the redundant proposal and leave the
            // confirmed record - provenance and recordedAt included - exactly as it was. Overwriting it
            // with the candidate would refresh recordedAt and destroy the provenance that records where
            // the fact actually came from; this is the same case remember() reports as Unchanged.
            store.delete(candidate.id)
            return existing
        }

        // The user has just accepted a change to a fact the companion already treats as true. The
        // confirmed record is corrected in place and keeps its id: the alternative - a second confirmed
        // record, or a new id for the same fact - would either duplicate her memory or break any
        // reference she is already holding.
        val corrected = candidate.withId(existing.id).withStatus(MemoryStatus.CONFIRMED)
        store.put(corrected)
        store.delete(candidate.id)
        return corrected
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

    override suspend fun edit(id: MemoryId, edit: MemoryEdit): EditOutcome {
        val existing = store.getById(id) ?: throw MemoryNotFoundException(id)

        if (existing.status == MemoryStatus.REJECTED) {
            throw MemoryNotEditableException(id, existing.status)
        }
        if (existing.type != edit.type) {
            throw MemoryTypeMismatchException(id, edit.type, existing.type)
        }

        val edited = applyEdit(existing, edit)

        // A Save the user pressed without changing anything. Writing would replace the audit envelope
        // and erase where the fact originally came from, for no change at all.
        if (edited.contentFingerprint == existing.contentFingerprint) {
            return EditOutcome.Unchanged(existing)
        }

        // A correction frequently moves the identity - a new date, a different role, another attribute.
        // The id travels with it. Colliding with a *different* confirmed memory is refused rather than
        // merged: merging would consume one id and overwrite the other.
        if (existing.status == MemoryStatus.CONFIRMED &&
            edited.scopedIdentity != existing.scopedIdentity
        ) {
            val conflicting = confirmedFor(edited.scopedIdentity)
            if (conflicting != null && conflicting.id != existing.id) {
                throw MemoryIdentityConflictException(id, conflicting.id, edited.scopedIdentity)
            }
        }

        // The fact now comes from the user rather than from the sentence it was extracted from, so the
        // audit envelope moves with it and the old excerpt goes.
        val written = edited.withAudit(
            source = MemorySource.USER_EDIT,
            recordedAt = edit.editedAt,
            provenance = Provenance(
                sessionId = null,
                messageId = null,
                excerpt = "",
                extractor = USER_EDIT_EXTRACTOR,
            ),
        )
        store.put(written)
        return EditOutcome.Updated(existing, written)
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
     * The edited content, with the envelope left exactly as it was.
     *
     * The casts are safe because [edit] has already refused a type mismatch, and they are written as
     * `when` branches over a sealed hierarchy so a new memory type cannot be added without this failing
     * to compile.
     */
    private fun applyEdit(memory: CanonicalMemory, edit: MemoryEdit): CanonicalMemory = when (edit) {
        is ProfileEdit ->
            (memory as ProfileMemory).copy(attribute = edit.attribute, value = edit.value)

        is EventEdit ->
            (memory as EventMemory).copy(
                title = edit.title,
                scheduledFor = edit.scheduledFor,
                location = edit.location,
            )

        is EpisodeEdit ->
            (memory as EpisodeMemory).copy(
                summary = edit.summary,
                occurredAt = edit.occurredAt,
                emotionalTone = edit.emotionalTone,
                relations = edit.relations,
            )

        is RelationEdit ->
            (memory as RelationMemory).copy(
                name = edit.name,
                role = edit.role,
                note = edit.note,
            )
    }

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
