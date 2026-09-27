package com.aiwatch.memory.cache

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.aiwatch.memory.CanonicalMemory
import com.aiwatch.memory.CharacterScope
import com.aiwatch.memory.EpisodeMemory
import com.aiwatch.memory.EventMemory
import com.aiwatch.memory.Importance
import com.aiwatch.memory.MemoryGateway
import com.aiwatch.memory.MemoryId
import com.aiwatch.memory.MemorySource
import com.aiwatch.memory.MemoryStatus
import com.aiwatch.memory.MemoryStore
import com.aiwatch.memory.ProfileMemory
import com.aiwatch.memory.Provenance
import com.aiwatch.memory.RelationMemory
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Host-side verification of the durable cache.
 *
 * Runs on the emulator because a cache's whole value is what a real SQLite does across a real process,
 * which an in-memory substitute cannot demonstrate. Most cases use Room's in-memory database; the reopen
 * case uses a database file on disk, because otherwise this suite would only prove that Room remembers
 * while it is still running.
 */
class RoomMemoryCacheTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: MemoryCacheDatabase
    private lateinit var cache: MemoryCache

    @Before
    fun openInMemory() {
        database = Room.inMemoryDatabaseBuilder(context, MemoryCacheDatabase::class.java).build()
        cache = RoomMemoryCache(database)
    }

    @After
    fun close() {
        database.close()
    }

    // ------------------------------------------------------------------ round trips, exactly

    @Test
    fun everyCanonicalTypeSurvivesTheCacheExactly() = runTest {
        val expected = listOf(profile(), event(), episode(), relation())

        cache.replaceFullSnapshot(SUBJECT, expected, SYNCED_AT)
        val restored = cache.snapshot(SUBJECT).records.associateBy { it.id.value }

        assertEquals(expected.size, restored.size)
        expected.forEach { original ->
            assertEquals(original, restored.getValue(original.id.value), "${original.id.value} changed")
        }
    }

    @Test
    fun theEnvelopeSurvivesExactlyIncludingProvenanceAndImportance() = runTest {
        val original = profile(
            status = MemoryStatus.REJECTED,
            source = MemorySource.IMPORT,
            importance = Importance.HIGH,
            excerpt = "她说她不喜欢香菜",
            sessionId = "session-9",
            messageId = "message-9",
            extractor = "user-edit-v1",
        )

        cache.upsert(SUBJECT, listOf(original))

        val restored = cache.snapshot(SUBJECT).records.single()
        assertEquals(MemoryStatus.REJECTED, restored.status)
        assertEquals(MemorySource.IMPORT, restored.source)
        assertEquals(Importance.HIGH, restored.importance)
        assertEquals("session-9", restored.provenance.sessionId)
        assertEquals("message-9", restored.provenance.messageId)
        assertEquals("她说她不喜欢香菜", restored.provenance.excerpt)
        assertEquals("user-edit-v1", restored.provenance.extractor)
    }

    @Test
    fun instantPrecisionSurvivesToTheNanosecond() = runTest {
        // A cache that rounded an instant would silently change when an event is, or make an edit look
        // like a change when nothing moved.
        val exact = "2026-10-05T07:00:37.123456789Z"
        val original = event(scheduledFor = Instant.parse(exact))

        cache.upsert(SUBJECT, listOf(original))

        val restored = restoredEvent()
        assertEquals(exact, restored.scheduledFor.toString())
        assertEquals(Instant.parse(exact), restored.scheduledFor)
        assertEquals(Instant.parse("2026-09-27T23:59:59.987654321Z"), restored.recordedAt)
    }

    @Test
    fun relationNamesMayContainTheSeparatorsProseUses() = runTest {
        val original = episode(relations = setOf("室友、小李", "a,b", "同事"))

        cache.upsert(SUBJECT, listOf(original))

        assertEquals(
            setOf("室友、小李", "a,b", "同事"),
            (cache.snapshot(SUBJECT).records.single() as EpisodeMemory).relations,
        )
    }

    // ------------------------------------------------------------------ subject partition

    @Test
    fun oneSubjectCannotReadAnothersCachedMemory() = runTest {
        cache.upsert(SUBJECT_A, listOf(profile(id = "a1", value = "香菜")))
        cache.upsert(SUBJECT_B, listOf(profile(id = "b1", value = "芹菜")))

        assertEquals(listOf("a1"), cache.snapshot(SUBJECT_A).records.map { it.id.value })
        assertEquals(listOf("b1"), cache.snapshot(SUBJECT_B).records.map { it.id.value })
        assertTrue(cache.snapshot("subject-C").records.isEmpty())
        assertNull(cache.snapshot("subject-C").lastFullSyncAt)
    }

    @Test
    fun theSameMemoryIdInTwoSubjectsStaysTwoRows() = runTest {
        // Identity reset and rebinding is the concrete risk: without a subject in the key, the second
        // subject's row would overwrite the first's and then be shown to whoever came next.
        cache.upsert(SUBJECT_A, listOf(profile(id = "m1", value = "香菜")))
        cache.upsert(SUBJECT_B, listOf(profile(id = "m1", value = "芹菜")))

        assertEquals("香菜", (cache.snapshot(SUBJECT_A).records.single() as ProfileMemory).value)
        assertEquals("芹菜", (cache.snapshot(SUBJECT_B).records.single() as ProfileMemory).value)
    }

    @Test
    fun clearingASubjectRemovesItsRecordsAndItsFreshness() = runTest {
        cache.replaceFullSnapshot(SUBJECT_A, listOf(profile(id = "a1")), SYNCED_AT)
        cache.replaceFullSnapshot(SUBJECT_B, listOf(profile(id = "b1")), SYNCED_AT)

        cache.clearSubject(SUBJECT_A)

        val cleared = cache.snapshot(SUBJECT_A)
        assertTrue(cleared.records.isEmpty())
        assertNull(cleared.lastFullSyncAt, "the freshness of forgotten data must not survive it")
        assertEquals(listOf("b1"), cache.snapshot(SUBJECT_B).records.map { it.id.value })
    }

    // ------------------------------------------------------------------ full snapshot replacement

    @Test
    fun replacingAFullSnapshotRemovesRowsThatAreGoneAndRecordsTheSyncTime() = runTest {
        cache.replaceFullSnapshot(SUBJECT, listOf(profile(id = "old1"), profile(id = "old2")), SYNCED_AT)
        cache.replaceFullSnapshot(SUBJECT, listOf(profile(id = "new1")), LATER)

        val after = cache.snapshot(SUBJECT)
        assertEquals(listOf("new1"), after.records.map { it.id.value })
        assertEquals(LATER, after.lastFullSyncAt)
    }

    @Test
    fun aFullSnapshotKeepsEveryStatusAndScopeItWasGiven() = runTest {
        // The failure this guards is a caller passing a filtered list: a confirmed-only or recall result
        // would delete every staged candidate and every other character's memory. The cache cannot
        // detect a filtered list, so the operation is named for what it does and this test records the
        // consequence rather than leaving it implicit.
        val everything = listOf(
            profile(id = "c1", status = MemoryStatus.CONFIRMED),
            profile(id = "s1", status = MemoryStatus.STAGED),
            profile(id = "r1", status = MemoryStatus.REJECTED),
            profile(id = "x1", scope = "second-character"),
        )

        cache.replaceFullSnapshot(SUBJECT, everything, SYNCED_AT)

        assertEquals(
            setOf("c1", "s1", "r1", "x1"),
            cache.snapshot(SUBJECT).records.map { it.id.value }.toSet(),
        )
    }

    @Test
    fun replacingOneSubjectLeavesAnotherIntact() = runTest {
        cache.replaceFullSnapshot(SUBJECT_A, listOf(profile(id = "a1")), SYNCED_AT)
        cache.replaceFullSnapshot(SUBJECT_B, listOf(profile(id = "b1")), SYNCED_AT)

        cache.replaceFullSnapshot(SUBJECT_A, emptyList(), LATER)

        assertTrue(cache.snapshot(SUBJECT_A).records.isEmpty())
        assertEquals(listOf("b1"), cache.snapshot(SUBJECT_B).records.map { it.id.value })
        assertEquals(SYNCED_AT, cache.snapshot(SUBJECT_B).lastFullSyncAt)
    }

    @Test
    fun aSnapshotMustNameItsSubject() = runTest {
        assertFailsWith<IllegalArgumentException> { cache.replaceFullSnapshot("", emptyList(), SYNCED_AT) }
        assertFailsWith<IllegalArgumentException> { cache.upsert("  ", listOf(profile())) }
    }

    // ------------------------------------------------------------------ mechanical increments

    @Test
    fun anUpsertPatchesOneRecordAndTouchesNothingElse() = runTest {
        cache.replaceFullSnapshot(SUBJECT, listOf(profile(id = "m1"), profile(id = "m2")), SYNCED_AT)

        cache.upsert(SUBJECT, listOf(profile(id = "m1", value = "苦瓜")))

        val byId = cache.snapshot(SUBJECT).records.associateBy { it.id.value }
        assertEquals("苦瓜", (byId.getValue("m1") as ProfileMemory).value)
        assertEquals("香菜", (byId.getValue("m2") as ProfileMemory).value)
        // An upsert is not a sync: it must not claim the full set is fresh.
        assertEquals(SYNCED_AT, cache.snapshot(SUBJECT).lastFullSyncAt)
    }

    @Test
    fun anUpsertOfNothingIsANoOp() = runTest {
        cache.replaceFullSnapshot(SUBJECT, listOf(profile(id = "m1")), SYNCED_AT)

        cache.upsert(SUBJECT, emptyList())

        assertEquals(listOf("m1"), cache.snapshot(SUBJECT).records.map { it.id.value })
    }

    @Test
    fun deletingOneRecordLeavesTheRestAndTheFreshness() = runTest {
        cache.replaceFullSnapshot(SUBJECT, listOf(profile(id = "m1"), profile(id = "m2")), SYNCED_AT)

        cache.delete(SUBJECT, MemoryId("m1"))

        assertEquals(listOf("m2"), cache.snapshot(SUBJECT).records.map { it.id.value })
        assertEquals(SYNCED_AT, cache.snapshot(SUBJECT).lastFullSyncAt)
    }

    @Test
    fun deletingFromTheWrongSubjectDeletesNothing() = runTest {
        cache.upsert(SUBJECT_A, listOf(profile(id = "a1")))

        cache.delete(SUBJECT_B, MemoryId("a1"))

        assertEquals(listOf("a1"), cache.snapshot(SUBJECT_A).records.map { it.id.value })
    }

    // ------------------------------------------------------------------ durability across processes

    @Test
    fun theLastKnownStateSurvivesClosingAndReopeningTheDatabase() = runTest {
        val name = "memory-cache-reopen-${System.nanoTime()}.db"
        deleteDatabaseFiles(name)
        try {
            // Written through one open database and read through another: an in-memory substitute would
            // only prove that Room remembers while it is still running. Explicit try/finally rather than
            // `use`, because RoomDatabase's closeable type does not resolve to a `use` extension here.
            val first = openOnDisk(name)
            try {
                RoomMemoryCache(first).replaceFullSnapshot(
                    SUBJECT,
                    listOf(profile(id = "m1"), event(), episode(relations = setOf("室友、小李"))),
                    SYNCED_AT,
                )
            } finally {
                first.close()
            }

            val second = openOnDisk(name)
            try {
                val restored = RoomMemoryCache(second).snapshot(SUBJECT)
                assertEquals(setOf("m1", "e1", "x1"), restored.records.map { it.id.value }.toSet())
                assertEquals(SYNCED_AT, restored.lastFullSyncAt)
                assertEquals(
                    setOf("室友、小李"),
                    (restored.records.first { it.id.value == "x1" } as EpisodeMemory).relations,
                )
            } finally {
                second.close()
            }
        } finally {
            deleteDatabaseFiles(name)
        }
    }

    // ------------------------------------------------------------------ what must be absent

    @Test
    fun theCacheIsNotAMemoryStoreAndNotAGateway() {
        // The concrete way this architecture could be undone: `DefaultMemoryGateway(RoomMemoryStore)`
        // compiling, which would move de-duplication, lifecycle and identity-conflict decisions onto the
        // watch. Asserted structurally so the claim is enforced rather than merely documented.
        assertFalse(MemoryStore::class.java.isAssignableFrom(RoomMemoryCache::class.java))
        assertFalse(MemoryGateway::class.java.isAssignableFrom(RoomMemoryCache::class.java))
    }

    @Test
    fun theCacheContractOffersNoSemanticOperation() {
        // Synthetic and bridge methods are Kotlin's, not the contract's. Names are also truncated at the
        // first '-': the compiler mangles a suspend function that takes a value class, so `delete` with
        // a MemoryId appears as `delete-tstf5_k` on the JVM.
        val operations = MemoryCache::class.java.methods
            .filterNot { it.isSynthetic || it.isBridge }
            .map { it.name.substringBefore('-').lowercase() }
            .toSet()
        assertEquals(
            setOf("snapshot", "replacefullsnapshot", "upsert", "delete", "clearsubject"),
            operations,
            "the cache contract grew an operation; anything that interprets belongs to the authority",
        )
        listOf("remember", "edit", "confirm", "reject", "recall", "dedup", "merge", "stage").forEach { banned ->
            assertTrue(operations.none { it.contains(banned) }, "MemoryCache exposes '$banned'")
        }
    }

    @Test
    fun theDatabaseHoldsNoOfflineMutationQueue() {
        // Read from the real schema rather than the @Database annotation: Room's annotation is not
        // retained at runtime, and a table that actually exists is what matters anyway.
        val cursor = database.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND name NOT LIKE 'room_%'",
        )
        val tables = try {
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        } finally {
            cursor.close()
        }

        assertEquals(
            setOf("cached_memory", "memory_cache_meta"),
            tables,
            "a pending-mutation table appeared; a memory decision is only real once the authority accepts it",
        )
    }

    // ------------------------------------------------------------------ harness

    private fun openOnDisk(name: String): MemoryCacheDatabase =
        Room.databaseBuilder(context, MemoryCacheDatabase::class.java, name).build()

    private fun deleteDatabaseFiles(name: String) {
        val dir = context.getDatabasePath(name).parentFile
        listOf(name, "$name-wal", "$name-shm").forEach { File(dir, it).delete() }
    }

    private suspend fun restoredEvent(): EventMemory =
        cache.snapshot(SUBJECT).records.single() as EventMemory

    private fun profile(
        id: String = "m1",
        value: String = "香菜",
        status: MemoryStatus = MemoryStatus.CONFIRMED,
        source: MemorySource = MemorySource.CONVERSATION,
        importance: Importance = Importance.NORMAL,
        scope: String = "xiaozhi",
        excerpt: String = "我不喜欢香菜",
        sessionId: String? = "session-1",
        messageId: String? = "message-1",
        extractor: String = "canonical-memory-v1",
    ) = ProfileMemory(
        id = MemoryId(id),
        importance = importance,
        status = status,
        recordedAt = Instant.parse("2026-09-27T23:59:59.987654321Z"),
        source = source,
        provenance = Provenance(sessionId, messageId, excerpt, extractor),
        characterScope = CharacterScope(scope),
        attribute = "food.dislike",
        value = value,
    )

    private fun event(scheduledFor: Instant = Instant.parse("2026-10-05T07:00:00Z")) = EventMemory(
        id = MemoryId("e1"),
        importance = Importance.NORMAL,
        status = MemoryStatus.CONFIRMED,
        recordedAt = Instant.parse("2026-09-27T23:59:59.987654321Z"),
        source = MemorySource.CONVERSATION,
        provenance = Provenance("session-1", "message-2", "下周三去医院", "canonical-memory-v1"),
        characterScope = CharacterScope("xiaozhi"),
        title = "去医院",
        scheduledFor = scheduledFor,
        location = "浙一",
    )

    private fun episode(relations: Set<String> = setOf("室友")) = EpisodeMemory(
        id = MemoryId("x1"),
        importance = Importance.NORMAL,
        status = MemoryStatus.CONFIRMED,
        recordedAt = Instant.parse("2026-09-27T23:59:59.987654321Z"),
        source = MemorySource.CONVERSATION,
        provenance = Provenance("session-1", "message-3", "昨天和室友吵架了", "canonical-memory-v1"),
        characterScope = CharacterScope("xiaozhi"),
        summary = "和室友吵架了",
        occurredAt = Instant.parse("2026-09-26T13:00:00Z"),
        emotionalTone = "委屈",
        relations = relations,
    )

    private fun relation() = RelationMemory(
        id = MemoryId("n1"),
        importance = Importance.NORMAL,
        status = MemoryStatus.CONFIRMED,
        recordedAt = Instant.parse("2026-09-27T23:59:59.987654321Z"),
        source = MemorySource.CONVERSATION,
        provenance = Provenance("session-1", "message-4", "小李是我室友", "canonical-memory-v1"),
        characterScope = CharacterScope("xiaozhi"),
        name = "小李",
        role = "室友",
        note = null,
    )

    private companion object {
        const val SUBJECT = "device-1"
        const val SUBJECT_A = "device-A"
        const val SUBJECT_B = "device-B"
        val SYNCED_AT: Instant = Instant.parse("2026-09-27T21:34:00Z")
        val LATER: Instant = Instant.parse("2026-09-28T21:34:00Z")
    }
}
