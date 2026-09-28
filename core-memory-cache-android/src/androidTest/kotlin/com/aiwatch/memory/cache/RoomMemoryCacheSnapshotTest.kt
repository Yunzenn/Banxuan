package com.aiwatch.memory.cache

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.aiwatch.memory.CharacterScope
import com.aiwatch.memory.Importance
import com.aiwatch.memory.MemoryId
import com.aiwatch.memory.MemorySource
import com.aiwatch.memory.MemoryStatus
import com.aiwatch.memory.ProfileMemory
import com.aiwatch.memory.Provenance
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before

/**
 * The read-coherence contract of the cache's snapshot.
 *
 * The writer was already atomic, but reading the records and the freshness as two separate queries was
 * not: a reader could take the records before a `replaceFullSnapshot` committed and the timestamp after
 * it, and then hand the caller yesterday's memories labelled with the current sync time. On the trust
 * surface that is the false claim the freshness field exists to prevent.
 *
 * The generation is encoded in **both** halves - N records always carry the timestamp of generation N -
 * so a torn read is detectable rather than merely suspected. With the read inside one transaction the
 * pair always comes from the same commit and the invariant cannot break; a regression that split the
 * read again would eventually show up as a mismatch here.
 */
class RoomMemoryCacheSnapshotTest {

    private lateinit var database: MemoryCacheDatabase
    private lateinit var cache: MemoryCache

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            MemoryCacheDatabase::class.java,
        ).build()
        cache = RoomMemoryCache(database)
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun recordsAndFreshnessAlwaysComeFromTheSameCommit() = runBlocking {
        val generations = 60
        val writerDone = AtomicBoolean(false)
        val failures = mutableListOf<String>()

        val reader = launch(Dispatchers.IO) {
            while (!writerDone.get()) {
                val snapshot = cache.snapshot(SUBJECT)
                val generation = snapshot.lastFullSyncAt?.epochSecond ?: continue
                if (generation.toInt() != snapshot.records.size) {
                    failures += "records=${snapshot.records.size} but freshness=generation $generation"
                }
            }
        }

        withContext(Dispatchers.IO) {
            for (generation in 1..generations) {
                cache.replaceFullSnapshot(
                    subjectId = SUBJECT,
                    records = (1..generation).map { profile("m$it") },
                    syncedAt = Instant.ofEpochSecond(generation.toLong()),
                )
            }
        }

        writerDone.set(true)
        reader.join()

        assertEquals(emptyList(), failures, "a snapshot mixed records and freshness from different commits")
    }

    @Test
    fun anEmptySubjectReportsNoFreshnessRatherThanAnInheritedOne() = runBlocking {
        val snapshot = cache.snapshot(SUBJECT)

        assertEquals(emptyList(), snapshot.records)
        assertNull(snapshot.lastFullSyncAt)
    }

    @Test
    fun replacingASubjectKeepsItsRecordsAndFreshnessTogetherForOtherSubjects() = runBlocking {
        cache.replaceFullSnapshot(SUBJECT, listOf(profile("a1")), Instant.ofEpochSecond(1))
        cache.replaceFullSnapshot(OTHER, listOf(profile("b1"), profile("b2")), Instant.ofEpochSecond(2))

        val first = cache.snapshot(SUBJECT)
        val second = cache.snapshot(OTHER)

        assertEquals(1, first.records.size)
        assertEquals(1L, first.lastFullSyncAt?.epochSecond)
        assertEquals(2, second.records.size)
        assertEquals(2L, second.lastFullSyncAt?.epochSecond)
    }

    private fun profile(id: String) = ProfileMemory(
        id = MemoryId(id),
        importance = Importance.NORMAL,
        status = MemoryStatus.CONFIRMED,
        recordedAt = Instant.parse("2026-09-27T10:00:00Z"),
        source = MemorySource.CONVERSATION,
        provenance = Provenance("session-1", "message-1", "我不喜欢香菜", "canonical-memory-v1"),
        characterScope = CharacterScope("xiaozhi"),
        attribute = "food.dislike",
        value = "香菜",
    )

    private companion object {
        const val SUBJECT = "device-1"
        const val OTHER = "device-2"
    }
}
