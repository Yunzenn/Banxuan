package com.aiwatch.probe.memory

import com.aiwatch.memory.CanonicalMemory
import com.aiwatch.memory.CharacterScope
import com.aiwatch.memory.EditOutcome
import com.aiwatch.memory.Importance
import com.aiwatch.memory.MemoryEdit
import com.aiwatch.memory.MemoryGateway
import com.aiwatch.memory.MemoryId
import com.aiwatch.memory.MemoryIdentityConflictException
import com.aiwatch.memory.MemoryIdentity
import com.aiwatch.memory.MemoryQuery
import com.aiwatch.memory.MemorySource
import com.aiwatch.memory.MemoryStatus
import com.aiwatch.memory.ProfileEdit
import com.aiwatch.memory.ProfileMemory
import com.aiwatch.memory.Provenance
import com.aiwatch.memory.RememberOutcome
import com.aiwatch.memory.ScopedMemoryIdentity
import com.aiwatch.memory.cache.CachedMemorySnapshot
import com.aiwatch.memory.cache.MemoryCache
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * The composition layer's contract, as fast JVM tests.
 *
 * The repository is pure logic over two interfaces, so nothing here needs a database, a network or an
 * emulator - which means these run in CI rather than only on a device. The fakes record the order in
 * which they are called, because the ordering is the contract: remote first, cache second, and nothing
 * written locally when the authority refuses.
 */
class MemoryTrustRepositoryTest {

    private val journal = mutableListOf<String>()
    private val cache = FakeCache(journal)
    private val gateway = FakeGateway(journal)

    private fun repository(
        gateway: MemoryGateway? = this.gateway,
        cache: MemoryCache? = this.cache,
        clock: () -> Instant = { SYNCED_AT },
    ) = MemoryTrustRepository(SUBJECT, gateway, cache, clock)

    // ---------------------------------------------------------------- it is not a memory service

    @Test
    fun theRepositoryIsNotAMemoryGateway() {
        // `MemoryGateway` is what decides what a memory means. A repository that implemented it would put
        // those decisions back on the watch, which is the architecture this project chose against.
        assertFalse(MemoryGateway::class.java.isAssignableFrom(MemoryTrustRepository::class.java))
    }

    // ---------------------------------------------------------------- reading

    @Test
    fun theCachedSnapshotIsEmittedBeforeTheAuthorityAnswers() = runTest {
        cache.seed(SUBJECT, listOf(profile("m1")), SYNCED_AT)
        val gate = CompletableDeferred<Unit>()
        gateway.blockList = gate
        val emitted = mutableListOf<MemoryTrustSnapshot>()

        val collector = launch { repository().snapshots().collect { emitted += it } }
        testScheduler.advanceUntilIdle()

        assertEquals(1, emitted.size, "the cached view must be rendered before the remote call returns")
        assertEquals(
            Freshness.CACHED,
            emitted[0].freshness,
            "before the refresh concludes there is nothing to report as failed",
        )
        assertEquals(SYNCED_AT, emitted[0].lastFullSyncAt, "the screen must be able to say how old this is")
        assertEquals(listOf("m1"), emitted[0].records.map { it.id.value })

        gate.complete(Unit)
        collector.join()

        assertEquals(2, emitted.size)
        assertEquals(Freshness.FRESH, emitted[1].freshness)
    }

    @Test
    fun aRefreshAsksForTheCompleteSetAndWritesItBack() = runTest {
        gateway.records = listOf(profile("c1"), profile("s1", MemoryStatus.STAGED))

        val snapshot = repository().refresh()

        // Every status and every character scope: a filtered list here would be handed to
        // replaceFullSnapshot and would delete everything the filter excluded.
        assertEquals(setOf("list:ALL:null"), gateway.calls.toSet())
        assertEquals(Freshness.FRESH, snapshot.freshness)
        assertEquals(1, cache.replaced.size, "the full snapshot must be written back exactly once")
        assertEquals(setOf("c1", "s1"), cache.stored(SUBJECT).map { it.id.value }.toSet())
        assertEquals(SYNCED_AT, cache.lastFullSyncAt(SUBJECT))
    }

    @Test
    fun aFailedRefreshLeavesTheCacheUntouchedAndSaysItIsStale() = runTest {
        cache.seed(SUBJECT, listOf(profile("m1")), SYNCED_AT)
        gateway.failList = true

        val snapshot = repository().refresh()

        assertEquals(Freshness.STALE, snapshot.freshness)
        assertTrue(snapshot.isStale)
        assertTrue(cache.writes.isEmpty(), "a failed refresh wrote to the cache: ${cache.writes}")
        assertEquals(listOf("m1"), snapshot.records.map { it.id.value })
    }

    @Test
    fun neverHavingSyncedIsNotTheSameAsKnowingNothing() = runTest {
        gateway.failList = true

        val snapshot = repository().refresh()

        assertEquals(Freshness.NEVER_SYNCED, snapshot.freshness)
        assertTrue(snapshot.records.isEmpty())
        assertFalse(snapshot.isStale, "NEVER_SYNCED is its own state, not a stale one")
        assertEquals(null, snapshot.lastFullSyncAt)
    }

    @Test
    fun withoutAGatewayTheStateIsUnavailableRatherThanNeverSynced() = runTest {
        val snapshot = repository(gateway = null).refresh()

        assertEquals(Freshness.UNAVAILABLE, snapshot.freshness)
    }

    @Test
    fun aRefreshSurvivesACacheWriteFailureAndStillReportsTheAuthoritativeAnswer() = runTest {
        gateway.records = listOf(profile("m1"))
        cache.failWrites = true

        val snapshot = repository().refresh()

        // The authority answered; a local copy that could not be written does not make that answer wrong.
        assertEquals(Freshness.FRESH, snapshot.freshness)
        assertEquals(listOf("m1"), snapshot.records.map { it.id.value })
    }

    // ---------------------------------------------------------------- mutations are remote-first

    @Test
    fun confirmReachesTheAuthorityFirstAndThenTheCache() = runTest {
        gateway.confirmResult = profile("m1", MemoryStatus.CONFIRMED)

        val result = repository().confirm(MemoryId("m1"))

        assertEquals(MemoryStatus.CONFIRMED, result.value.status)
        assertTrue(result.cacheUpdated)
        assertEquals(listOf("confirm", "cache:upsert"), journal)
    }

    @Test
    fun rejectReachesTheAuthorityFirstAndThenTheCache() = runTest {
        gateway.rejectResult = profile("m1", MemoryStatus.REJECTED)

        repository().reject(MemoryId("m1"))

        assertEquals(listOf("reject", "cache:upsert"), journal)
        assertEquals(MemoryStatus.REJECTED, cache.stored(SUBJECT).single().status)
    }

    @Test
    fun editWritesTheRecordTheAuthorityReturned() = runTest {
        gateway.editResult = EditOutcome.Updated(
            previous = profile("m1", value = "香菜"),
            memory = profile("m1", value = "芹菜", source = MemorySource.USER_EDIT),
        )

        val result = repository().edit(MemoryId("m1"), ProfileEdit(SYNCED_AT, "food.dislike", "芹菜"))

        assertEquals(listOf("edit", "cache:upsert"), journal)
        assertIs<EditOutcome.Updated>(result.value)
        assertEquals("芹菜", (cache.stored(SUBJECT).single() as ProfileMemory).value)
    }

    @Test
    fun editWritesBackEvenWhenTheAuthorityReportsNoChange() = runTest {
        val existing = profile("m1", value = "香菜")
        gateway.editResult = EditOutcome.Unchanged(existing)

        repository().edit(MemoryId("m1"), ProfileEdit(SYNCED_AT, "food.dislike", "香菜"))

        assertEquals(listOf("edit", "cache:upsert"), journal)
        assertEquals("香菜", (cache.stored(SUBJECT).single() as ProfileMemory).value)
    }

    @Test
    fun forgetDeletesLocallyEvenWhenTheAuthoritySaysItHeldNothing() = runTest {
        cache.seed(SUBJECT, listOf(profile("m1")), SYNCED_AT)
        gateway.forgetResult = false

        val result = repository().forget(MemoryId("m1"))

        // `false` means the authority confirmed it does not hold the record. Keeping the local row would
        // make the cache claim to be more authoritative than the authority.
        assertFalse(result.value)
        assertTrue(cache.stored(SUBJECT).isEmpty(), "the cache kept a record the authority does not hold")
    }

    // ---------------------------------------------------------------- failures never reach the cache

    @Test
    fun aFailedMutationWritesNothingLocally() = runTest {
        gateway.failConfirm = true

        assertFailsWith<IOException> { repository().confirm(MemoryId("m1")) }

        assertEquals(listOf("confirm"), journal, "a refused confirm reached the cache")
        assertTrue(cache.writes.isEmpty())
    }

    @Test
    fun anIdentityConflictWritesNothingLocally() = runTest {
        gateway.conflictOnEdit = true

        assertFailsWith<MemoryIdentityConflictException> {
            repository().edit(MemoryId("m1"), ProfileEdit(SYNCED_AT, "food.like", "香菜"))
        }

        assertEquals(listOf("edit"), journal)
        assertTrue(cache.writes.isEmpty())
    }

    @Test
    fun aFailedForgetWritesNothingLocally() = runTest {
        cache.seed(SUBJECT, listOf(profile("m1")), SYNCED_AT)
        journal.clear()
        gateway.failForget = true

        assertFailsWith<IOException> { repository().forget(MemoryId("m1")) }

        assertEquals(listOf("forget"), journal)
        assertTrue(cache.stored(SUBJECT).isNotEmpty(), "a failed forget deleted the local row")
    }

    @Test
    fun anAcceptedMutationThatCouldNotBeCachedIsReportedAsSuccessWithADirtyCache() = runTest {
        gateway.confirmResult = profile("m1", MemoryStatus.CONFIRMED)
        cache.failWrites = true

        val result = repository().confirm(MemoryId("m1"))

        // The authority confirmed. Reporting this as a failure would be a lie told on a trust surface.
        assertEquals(MemoryStatus.CONFIRMED, result.value.status)
        assertFalse(result.cacheUpdated, "the caller needs to know the local copy is behind")
    }

    @Test
    fun mutationsAreRefusedWhenThereIsNoGatewayRatherThanSilentlyCached() = runTest {
        assertFailsWith<IllegalStateException> { repository(gateway = null).confirm(MemoryId("m1")) }

        assertTrue(cache.writes.isEmpty(), "a mutation with no authority touched the cache")
    }

    // ---------------------------------------------------------------- ordering

    @Test
    fun refreshAndMutationAreSerializedSoTheNewerMutationWins() = runTest {
        // What this pins is serialisation and ordering, not one literal interleaving. Without the lock a
        // refresh that read the old set could finish after a mutation and replace the cache with the
        // stale projection, leaving the screen showing a candidate as staged while the authority already
        // holds it as confirmed.
        val gate = CompletableDeferred<Unit>()
        gateway.blockList = gate
        gateway.records = listOf(profile("m1", MemoryStatus.STAGED))
        gateway.confirmResult = profile("m1", MemoryStatus.CONFIRMED)
        val repo = repository()

        val refreshing = launch { repo.refresh() }
        testScheduler.runCurrent()

        val confirming = launch { repo.confirm(MemoryId("m1")) }
        testScheduler.runCurrent()

        assertTrue(
            gateway.calls.none { it == "confirm" },
            "a mutation ran while a refresh held the lock: ${gateway.calls}",
        )

        gate.complete(Unit)
        // runCurrent rather than join: draining the scheduler keeps this deterministic, and asserting
        // completion instead of joining cannot hang the test if something never resumes.
        testScheduler.runCurrent()

        assertTrue(refreshing.isCompleted, "the refresh did not finish")
        assertTrue(confirming.isCompleted, "the mutation did not finish")
        // cache:replace is necessarily between them: the refresh projects the old full snapshot into the
        // cache before it releases the lock, and only then does the mutation reach the authority. That
        // ordering is the whole point - the mutation's projection is the one that survives.
        assertEquals(
            listOf("list", "cache:replace", "confirm", "cache:upsert"),
            journal,
            "the refresh must finish its cache projection before the mutation reaches the authority",
        )
        assertEquals(MemoryStatus.CONFIRMED, cache.stored(SUBJECT).single().status)
    }

    @Test
    fun aFailedRefreshTurnsTheCachedViewStale() = runTest {
        cache.seed(SUBJECT, listOf(profile("m1")), SYNCED_AT)
        gateway.failList = true
        val emitted = mutableListOf<MemoryTrustSnapshot>()

        repository().snapshots().collect { emitted += it }

        // CACHED first, STALE once the refresh has actually concluded and failed. Labelling the first
        // frame STALE would put "offline" on screen on every ordinary launch.
        assertEquals(listOf(Freshness.CACHED, Freshness.STALE), emitted.map { it.freshness })
        assertEquals(SYNCED_AT, emitted.last().lastFullSyncAt)
    }

    @Test
    fun aSuccessfulRefreshReplacesTheCachedViewWithFreshRecords() = runTest {
        cache.seed(SUBJECT, listOf(profile("old")), SYNCED_AT)
        gateway.records = listOf(profile("new"))
        val emitted = mutableListOf<MemoryTrustSnapshot>()

        repository().snapshots().collect { emitted += it }

        assertEquals(listOf(Freshness.CACHED, Freshness.FRESH), emitted.map { it.freshness })
        assertEquals(listOf("old"), emitted.first().records.map { it.id.value })
        assertEquals(listOf("new"), emitted.last().records.map { it.id.value })
    }

    @Test
    fun withoutAGatewaySnapshotsAreUnavailableWithoutReadingOrPresentingCache() = runTest {
        // A cached frame followed by an empty one would show the user memories and then erase them, and
        // the screen cannot tell that flicker apart from data that was just deleted. "Not connected to a
        // memory service" is one statement, so it must be one emission - and the cache must not be read
        // to produce it, or the records would be available to present by accident.
        cache.seed(SUBJECT, listOf(profile("old")), SYNCED_AT)

        val emitted = mutableListOf<MemoryTrustSnapshot>()
        repository(gateway = null).snapshots().collect { emitted += it }

        assertEquals(
            listOf(Freshness.UNAVAILABLE),
            emitted.map { it.freshness },
            "an unconfigured service must not flash a cached frame before becoming unavailable",
        )
        assertTrue(
            emitted.single().records.isEmpty(),
            "cached records were presented without an authority",
        )
        assertEquals(null, emitted.single().lastFullSyncAt)
        assertEquals(
            0,
            cache.snapshotReads,
            "the cache was read even though no gateway is configured",
        )
    }

    // ---------------------------------------------------------------- cancellation is not swallowed

    @Test
    fun aCancelledCacheReadPropagates() = runTest {
        // `runCatching` would swallow this and let work continue after the coroutine that owned it is
        // gone, which breaks structured concurrency in the one class whose job is ordering. The gateway
        // must fail first: only the fallback path reads the cache, so a successful refresh never
        // exercises this.
        gateway.failList = true
        cache.cancelOnRead = true

        assertFailsWith<CancellationException> { repository().refresh() }
    }

    @Test
    fun aCancelledCacheReplacePropagates() = runTest {
        gateway.records = listOf(profile("m1"))
        cache.cancelOnWrite = true

        assertFailsWith<CancellationException> { repository().refresh() }
    }

    @Test
    fun aCancelledMutationCacheWritePropagates() = runTest {
        gateway.confirmResult = profile("m1", MemoryStatus.CONFIRMED)
        cache.cancelOnWrite = true

        assertFailsWith<CancellationException> { repository().confirm(MemoryId("m1")) }
    }

    // ---------------------------------------------------------------- fakes

    private class FakeGateway(private val journal: MutableList<String>) : MemoryGateway {
        val calls = mutableListOf<String>()
        var records: List<CanonicalMemory> = emptyList()
        var blockList: CompletableDeferred<Unit>? = null
        var failList = false
        var failConfirm = false
        var failForget = false
        var conflictOnEdit = false
        var confirmResult: CanonicalMemory? = null
        var rejectResult: CanonicalMemory? = null
        var editResult: EditOutcome? = null
        var forgetResult = true

        private fun record(call: String) {
            journal += call.substringBefore(':')
            calls += call
        }

        override suspend fun list(statuses: Set<MemoryStatus>, characterScope: CharacterScope?): List<CanonicalMemory> {
            // The repository must ask for everything; the call is recorded so the assertion can see it.
            record("list:${if (statuses.size == MemoryStatus.entries.size) "ALL" else "PARTIAL"}:$characterScope")
            blockList?.await()
            if (failList) throw IOException("offline")
            return records
        }

        override suspend fun confirm(id: MemoryId): CanonicalMemory {
            record("confirm")
            if (failConfirm) throw java.io.IOException("offline")
            return confirmResult ?: error("no confirmResult")
        }

        override suspend fun reject(id: MemoryId): CanonicalMemory {
            record("reject")
            return rejectResult ?: error("no rejectResult")
        }

        override suspend fun edit(id: MemoryId, edit: MemoryEdit): EditOutcome {
            record("edit")
            if (conflictOnEdit) {
                throw MemoryIdentityConflictException(
                    id,
                    MemoryId("m2"),
                    ScopedMemoryIdentity(CharacterScope("xiaozhi"), MemoryIdentity(com.aiwatch.memory.MemoryType.PROFILE, "food.like")),
                )
            }
            return editResult ?: error("no editResult")
        }

        override suspend fun forget(id: MemoryId): Boolean {
            record("forget")
            if (failForget) throw java.io.IOException("offline")
            return forgetResult
        }

        override suspend fun recall(query: MemoryQuery): List<CanonicalMemory> = records
        override suspend fun stage(candidates: List<CanonicalMemory>): List<CanonicalMemory> = candidates
        override suspend fun remember(memory: CanonicalMemory): RememberOutcome =
            RememberOutcome.Created(memory)
    }

    private class FakeCache(private val journal: MutableList<String>) : MemoryCache {
        private val bySubject = mutableMapOf<String, MutableList<CanonicalMemory>>()
        private val syncAt = mutableMapOf<String, Instant>()
        val writes = mutableListOf<String>()
        var replaced = mutableListOf<String>()
        var failWrites = false
        var snapshotReads = 0
        var cancelOnRead = false
        var cancelOnWrite = false

        fun seed(subjectId: String, records: List<CanonicalMemory>, syncedAt: Instant) {
            bySubject[subjectId] = records.toMutableList()
            syncAt[subjectId] = syncedAt
        }

        fun stored(subjectId: String): List<CanonicalMemory> = bySubject[subjectId].orEmpty()
        fun lastFullSyncAt(subjectId: String): Instant? = syncAt[subjectId]

        override suspend fun snapshot(subjectId: String): CachedMemorySnapshot {
            snapshotReads += 1
            if (cancelOnRead) throw CancellationException("screen went away")
            return CachedMemorySnapshot(records = stored(subjectId), lastFullSyncAt = syncAt[subjectId])
        }

        override suspend fun replaceFullSnapshot(
            subjectId: String,
            records: List<CanonicalMemory>,
            syncedAt: Instant,
        ) {
            journal += "cache:replace"
            writes += "replace"
            if (cancelOnWrite) throw CancellationException("screen went away")
            if (failWrites) throw IllegalStateException("disk full")
            replaced += "cached_memory"
            bySubject[subjectId] = records.toMutableList()
            syncAt[subjectId] = syncedAt
        }

        override suspend fun upsert(subjectId: String, records: List<CanonicalMemory>) {
            journal += "cache:upsert"
            writes += "upsert"
            if (cancelOnWrite) throw CancellationException("screen went away")
            if (failWrites) throw IllegalStateException("disk full")
            val bucket = bySubject.getOrPut(subjectId) { mutableListOf() }
            records.forEach { record ->
                bucket.removeAll { it.id == record.id }
                bucket += record
            }
        }

        override suspend fun delete(subjectId: String, id: MemoryId) {
            journal += "cache:delete"
            writes += "delete"
            if (cancelOnWrite) throw CancellationException("screen went away")
            if (failWrites) throw IllegalStateException("disk full")
            bySubject[subjectId]?.removeAll { it.id == id }
        }

        override suspend fun clearSubject(subjectId: String) {
            bySubject.remove(subjectId)
            syncAt.remove(subjectId)
        }
    }

    private companion object {
        const val SUBJECT = "device-1"
        val SYNCED_AT: Instant = Instant.parse("2026-09-27T21:34:00Z")
    }
}

private fun profile(
    id: String,
    status: MemoryStatus = MemoryStatus.CONFIRMED,
    value: String = "香菜",
    source: MemorySource = MemorySource.CONVERSATION,
) = ProfileMemory(
    id = MemoryId(id),
    importance = Importance.NORMAL,
    status = status,
    recordedAt = Instant.parse("2026-09-27T10:00:00Z"),
    source = source,
    provenance = Provenance("session-1", "message-1", "我不喜欢香菜", "canonical-memory-v1"),
    characterScope = CharacterScope("xiaozhi"),
    attribute = "food.dislike",
    value = value,
)
