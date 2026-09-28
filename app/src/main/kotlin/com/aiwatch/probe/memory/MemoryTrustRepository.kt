package com.aiwatch.probe.memory

import com.aiwatch.memory.CanonicalMemory
import com.aiwatch.memory.EditOutcome
import com.aiwatch.memory.MemoryEdit
import com.aiwatch.memory.MemoryGateway
import com.aiwatch.memory.MemoryId
import com.aiwatch.memory.MemoryStatus
import com.aiwatch.memory.cache.MemoryCache
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * How current the records in a [MemoryTrustSnapshot] are.
 *
 * [CACHED] and [STALE] are separate states, and conflating them would put a false statement on screen on
 * every ordinary launch: a cache-first read happens *before* the network has had a chance to fail, so
 * labelling that frame "offline" would show "当前离线，显示上次同步内容" even when the refresh is about to
 * succeed. The screen must be able to tell "this is a cache, a refresh is in flight" apart from "the
 * refresh already failed and this is all there is".
 */
enum class Freshness {
    /** No gateway is configured at all. Distinct from a failed connection. */
    UNAVAILABLE,

    /** Rendered from the cache; no refresh has concluded yet. Not a claim that anything is wrong. */
    CACHED,

    /** Read from the authority just now. */
    FRESH,

    /** The refresh has concluded and failed, and this device has synced before. Now genuinely offline. */
    STALE,

    /**
     * The refresh failed and this device has never completed a full sync.
     *
     * Deliberately distinct from an empty result: "we have never reached the memory service" and "she
     * knows nothing about you" are different statements, and only one of them is true here.
     */
    NEVER_SYNCED,
}

/**
 * What the trust surface renders: the records, and how old they are.
 *
 * [lastFullSyncAt] travels with the records so the screen cannot show one without the other, which is
 * what a silent offline fallback would do.
 */
data class MemoryTrustSnapshot(
    val records: List<CanonicalMemory>,
    val lastFullSyncAt: Instant?,
    val freshness: Freshness,
) {
    /** True when the screen must say that it is showing an older state. */
    val isStale: Boolean get() = freshness == Freshness.STALE
}

/**
 * The outcome of a mutation that the authority has already accepted.
 *
 * [cacheUpdated] is `false` only when the authority accepted the change and writing it to the local
 * cache then failed. That is **not** an operation failure, and the screen must not report it as one:
 * saying "confirm failed" after the authority confirmed would be a lie told on a trust surface. The
 * honest message is that the change took effect and the local copy will catch up at the next sync.
 *
 * **A thrown failure is not automatically "the authority refused" either.** The exception's type says
 * which:
 *
 * * a canonical typed rejection (`MemoryIdentityConflictException`, `MemoryTransitionException`,
 *   `MemoryNotFoundException`, ...) is a definite refusal - the authority answered and did not accept;
 * * `com.aiwatch.memory.remote.MemoryTransportException` is *indeterminate*. A `POST /confirm` whose
 *   response is lost after the server committed leaves the operation applied and the client uninformed,
 *   so the screen may not say "确认失败". The honest message is that it could not be confirmed whether
 *   the change took effect, followed by a reconciliation through a plain `refresh()` - never a retried
 *   mutation, for the same reason the transport disables automatic retries.
 *
 * The cache is not written in either case, which is correct for both: it holds what the authority is
 * known to hold, and an indeterminate outcome is not knowledge.
 */
data class MutationResult<T>(val value: T, val cacheUpdated: Boolean)

/**
 * The data layer for "我的记忆".
 *
 * **This is not a memory service.** It does not implement [MemoryGateway] and must not: the authority
 * decides what a memory means, and a repository that re-derived de-duplication, lifecycle or
 * identity rules would move those decisions back onto the watch. It composes two things that already
 * exist - the remote authority and a local last-known copy - and its own logic is limited to ordering
 * and freshness.
 *
 * Two rules it does enforce, and one invariant its callers must hold:
 *
 * * **Remote first.** A mutation reaches the authority before anything is written locally, and any
 *   remote failure leaves the cache untouched. There is no optimistic local confirm, no dirty flag and
 *   no retry queue, because "she has stopped believing this" must not be a state the product can be in
 *   before the authority has agreed.
 * * **Serialised.** Every operation takes one [Mutex]. Without it a refresh that read the old state can
 *   finish after a mutation and overwrite the newer projection, leaving the cache showing a candidate
 *   as staged while the authority already holds it as confirmed. That would not change the authority,
 *   but it would make this screen show a wrong answer about what she believes.
 *
 * ### Composition owner invariant (frozen; enforced at production wiring)
 *
 * ```text
 * For one authenticated subject, every remote -> cache memory projection performed by the trust UI
 * must go through one long-lived MemoryTrustRepository instance owned by the production composition
 * scope. The Activity must not construct a repository per refresh or per mutation.
 * ```
 *
 * The [Mutex] above serialises operations **within one instance**. It cannot serialise two instances, so
 * a per-action repository would silently reintroduce the ordering race this class exists to close - and
 * would do it while every test still passed, because tests hold a single instance.
 *
 * The owner may be the composition root of the Activity's own lifetime, or a genuinely higher scope if
 * one exists later. **No manager, coordinator or service-locator layer is introduced for this.** All
 * that is required is that the same subject's trust surface receives the same instance for its lifetime.
 *
 * This is a statement about wiring, not behaviour, so it has no test here: with no production wiring yet,
 * a guard would only prove that nobody has made the mistake so far. It is enforced when the Activity is
 * wired, by showing that one lifetime's refresh/confirm/reject/edit/forget share an instance and that no
 * action handler constructs one.
 */
class MemoryTrustRepository(
    private val subjectId: String,
    private val gateway: MemoryGateway?,
    private val cache: MemoryCache?,
    private val clock: () -> Instant = Instant::now,
) {

    private val mutex = Mutex()

    /**
     * What the screen should show, in order.
     *
     * **A configured authority** emits the cached view first and then the refresh:
     *
     * ```text
     * cached -> CACHED      (no refresh has concluded, so nothing may be reported as failed yet)
     * refresh -> FRESH      (the authority answered)
     *         -> STALE      (the refresh failed and this device has synced before)
     *         -> NEVER_SYNCED (the refresh failed and it never has)
     * ```
     *
     * **No configured authority** emits `UNAVAILABLE` and nothing else - the cache is neither read nor
     * presented. Emitting a cached frame first would show the user memories and then erase them a moment
     * later, and the screen cannot tell that flicker apart from data that was just deleted. "This device
     * is not connected to a memory service" is a single statement, so it is a single emission.
     */
    fun snapshots(): Flow<MemoryTrustSnapshot> = flow {
        if (gateway == null) {
            emit(unavailable())
            return@flow
        }

        cachedSnapshot(Freshness.CACHED)?.let { emit(it) }
        emit(refresh())
    }

    /** Re-read the whole set from the authority. */
    suspend fun refresh(): MemoryTrustSnapshot = mutex.withLock { refreshLocked() }

    private suspend fun refreshLocked(): MemoryTrustSnapshot {
        val gateway = gateway ?: return unavailable()

        return try {
            // The COMPLETE set: every status, every character scope. A filtered list here would be
            // handed to replaceFullSnapshot and would delete everything the filter excluded - every
            // staged candidate, or every other character's memory.
            val records = gateway.list(
                statuses = MemoryStatus.entries.toSet(),
                characterScope = null,
            )
            val syncedAt = clock()
            // A cache write that fails does not make the authority's answer wrong.
            writeToCache { cache?.replaceFullSnapshot(subjectId, records, syncedAt) }
            MemoryTrustSnapshot(records, syncedAt, Freshness.FRESH)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            cachedSnapshot(Freshness.STALE) ?: neverSynced()
        }
    }

    // ---------------------------------------------------------------- mutations

    suspend fun confirm(id: MemoryId): MutationResult<CanonicalMemory> =
        mutate(record = { it }, block = { it.confirm(id) })

    suspend fun reject(id: MemoryId): MutationResult<CanonicalMemory> =
        mutate(record = { it }, block = { it.reject(id) })

    /**
     * Both outcomes are written back. `Unchanged` still carries the authority's record for that fact, and
     * storing it costs nothing while keeping the local copy in step with what the authority actually
     * holds.
     */
    suspend fun edit(id: MemoryId, edit: MemoryEdit): MutationResult<EditOutcome> = mutate(
        record = { outcome ->
            when (outcome) {
                is EditOutcome.Updated -> outcome.memory
                is EditOutcome.Unchanged -> outcome.existing
            }
        },
        block = { it.edit(id, edit) },
    )

    /**
     * Delete through the authority, then locally.
     *
     * The local row is dropped **even when the authority answers `false`**. `false` means the authority
     * has confirmed it does not hold that record; a cache that kept it would be claiming to be more
     * authoritative than the authority.
     */
    suspend fun forget(id: MemoryId): MutationResult<Boolean> = mutex.withLock {
        val gateway = requireGateway()
        val deleted = gateway.forget(id)
        val updated = cache?.let { c -> writeToCache { c.delete(subjectId, id) } } ?: true
        MutationResult(deleted, updated)
    }

    /**
     * Remote first, cache second.
     *
     * [block] runs before anything is written locally, so any remote exception propagates with the cache
     * untouched. Only once the authority has answered is the record it returned written back - and a
     * failure of that write is reported through [MutationResult.cacheUpdated] rather than as a failure of
     * the operation.
     */
    private suspend fun <T> mutate(
        record: (T) -> CanonicalMemory,
        block: suspend (MemoryGateway) -> T,
    ): MutationResult<T> = mutex.withLock {
        val gateway = requireGateway()
        val value = block(gateway)
        MutationResult(value, upsertReturned(record(value)))
    }

    private suspend fun upsertReturned(memory: CanonicalMemory): Boolean {
        val cache = cache ?: return true
        return writeToCache { cache.upsert(subjectId, listOf(memory)) }
    }

    // ---------------------------------------------------------------- reading

    private suspend fun cachedSnapshot(freshness: Freshness): MemoryTrustSnapshot? {
        val snapshot = readCache { cache?.snapshot(subjectId) } ?: return null
        // Nothing has ever been synced and nothing is stored: there is nothing to show and no age to
        // report, so this is not a cached view at all.
        if (snapshot.lastFullSyncAt == null && snapshot.records.isEmpty()) return null
        return MemoryTrustSnapshot(snapshot.records, snapshot.lastFullSyncAt, freshness)
    }

    private fun unavailable() = MemoryTrustSnapshot(emptyList(), null, Freshness.UNAVAILABLE)

    private fun neverSynced() = MemoryTrustSnapshot(emptyList(), null, Freshness.NEVER_SYNCED)

    private fun requireGateway(): MemoryGateway =
        gateway ?: throw IllegalStateException(
            "no memory gateway is configured; the screen must not offer mutations in this state",
        )

    // ---------------------------------------------------------------- cache access

    /**
     * A cache write whose failure is not an authority failure.
     *
     * `CancellationException` is rethrown rather than caught. Kotlin's `runCatching` swallows it, which
     * would mean a cancelled screen keeps running Room work after the coroutine it belonged to is gone -
     * breaking structured concurrency, and doing it in a class whose whole job is ordering.
     */
    private suspend fun writeToCache(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    /** A cache read whose failure is not a reason to fabricate anything; null means "nothing to show". */
    private suspend fun <T> readCache(block: suspend () -> T?): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
