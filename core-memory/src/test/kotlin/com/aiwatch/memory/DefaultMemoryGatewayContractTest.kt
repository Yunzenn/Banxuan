package com.aiwatch.memory

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The behavioural contract of [MemoryGateway], exercised against [DefaultMemoryGateway].
 *
 * The store underneath is a test double; the semantics on trial are real. Nothing here fakes a decision
 * the product must actually make - de-duplication, lifecycle and recall policy are the production
 * implementations.
 *
 * Scope limit, stated so it is not over-read: these tests show that a confirmed memory **can be retrieved
 * by a later recall**. They do not show that a conversation puts an old memory into the model's context
 * and answers from it. That claim needs the context builder and the conversation consumer, and it stays
 * `PENDING` until they exist.
 */
class DefaultMemoryGatewayContractTest {

    private val store = InMemoryMemoryStore()

    /**
     * Typed as the interface on purpose: the default arguments under test (`recall()`, `list()`) are
     * declared on [MemoryGateway], and an overriding function may not redeclare them.
     */
    private val gateway: MemoryGateway = DefaultMemoryGateway(store)

    // --- de-duplication ---

    @Test
    fun `same fact with the same content is reported unchanged and writes nothing`() = runTest {
        val first = gateway.remember(profile("food.dislike", "香菜"))
        assertIs<RememberOutcome.Created>(first)

        val again = gateway.remember(profile("food.dislike", "香菜"))

        val unchanged = assertIs<RememberOutcome.Unchanged>(again)
        assertEquals(1, confirmedProfiles().size, "a repeated fact must not become a second record")
        assertEquals("香菜", (unchanged.existing as ProfileMemory).value)
    }

    @Test
    fun `same fact with new content updates in place and keeps the stored id`() = runTest {
        val created = assertIs<RememberOutcome.Created>(gateway.remember(profile("food.dislike", "香菜")))
        val originalId = created.memory.id

        val corrected = gateway.remember(profile("food.dislike", "芹菜"))

        val updated = assertIs<RememberOutcome.Updated>(corrected)
        assertEquals(originalId, updated.memory.id, "the id must survive a correction")
        assertEquals("芹菜", (updated.memory as ProfileMemory).value)
        assertEquals(1, confirmedProfiles().size)
    }

    @Test
    fun `a correction takes the new provenance and recorded time, not the old ones`() = runTest {
        gateway.remember(profile("food.dislike", "香菜", recordedAt = EARLY, excerpt = "不喜欢香菜"))

        val corrected = assertIs<RememberOutcome.Updated>(
            gateway.remember(profile("food.dislike", "芹菜", recordedAt = LATE, excerpt = "现在爱吃芹菜")),
        )

        assertEquals(LATE, corrected.memory.recordedAt)
        assertEquals("现在爱吃芹菜", corrected.memory.provenance.excerpt)
    }

    // --- profile update ---

    @Test
    fun `correcting a preference keeps the memory id the user may already be holding`() = runTest {
        val id = assertIs<RememberOutcome.Created>(gateway.remember(profile("food.dislike", "香菜"))).memory.id

        gateway.remember(profile("food.dislike", "芹菜"))

        val recalled = assertNotNull(store.getById(id))
        assertEquals("芹菜", (recalled as ProfileMemory).value)
        assertEquals(id, recalled.id)
    }

    // --- event time ---

    @Test
    fun `the same event title at the same time is one fact`() = runTest {
        val when0 = Instant.parse("2026-10-05T07:00:00Z")
        gateway.remember(event("去医院", when0))

        val second = gateway.remember(event("去医院", when0))

        assertIs<RememberOutcome.Unchanged>(second)
        assertEquals(1, store.list().size)
    }

    @Test
    fun `the same event title at a different time is a second independent event`() = runTest {
        gateway.remember(event("去医院", Instant.parse("2026-10-05T07:00:00Z")))

        val second = gateway.remember(event("去医院", Instant.parse("2026-10-09T07:00:00Z")))

        assertIs<RememberOutcome.Created>(second)
        assertEquals(2, store.list().size)
    }

    @Test
    fun `a recall window filters events by their scheduled time`() = runTest {
        gateway.remember(event("取报告", Instant.parse("2026-10-01T02:00:00Z")))
        gateway.remember(event("复诊", Instant.parse("2026-10-05T02:00:00Z")))
        gateway.remember(event("复查", Instant.parse("2026-10-09T02:00:00Z")))

        val inWindow = gateway.recall(
            MemoryQuery(
                types = setOf(MemoryType.EVENT),
                from = Instant.parse("2026-10-04T00:00:00Z"),
                to = Instant.parse("2026-10-06T00:00:00Z"),
            ),
        )

        assertEquals(listOf("复诊"), inWindow.map { (it as EventMemory).title })
    }

    @Test
    fun `an undated event is outside every window`() = runTest {
        gateway.remember(event("买药", scheduledFor = null))

        val inWindow = gateway.recall(
            MemoryQuery(
                types = setOf(MemoryType.EVENT),
                from = Instant.parse("2026-01-01T00:00:00Z"),
                to = Instant.parse("2027-01-01T00:00:00Z"),
            ),
        )

        assertTrue(inWindow.isEmpty(), "an undated event cannot be said to fall inside a window")
    }

    @Test
    fun `a windowed recall excludes timeless profile facts`() = runTest {
        gateway.remember(profile("food.dislike", "香菜"))
        gateway.remember(event("复诊", Instant.parse("2026-10-05T02:00:00Z")))

        val inWindow = gateway.recall(
            MemoryQuery(
                from = Instant.parse("2026-10-04T00:00:00Z"),
                to = Instant.parse("2026-10-06T00:00:00Z"),
            ),
        )

        assertEquals(1, inWindow.size)
        assertEquals(MemoryType.EVENT, inWindow.single().type)
    }

    // --- forget ---

    @Test
    fun `forget removes the memory from recall and from list`() = runTest {
        val id = assertIs<RememberOutcome.Created>(gateway.remember(profile("food.dislike", "香菜"))).memory.id

        assertTrue(gateway.forget(id))

        assertTrue(gateway.recall().isEmpty())
        assertTrue(gateway.list().isEmpty())
        assertNull(store.getById(id))
    }

    @Test
    fun `forgetting the same memory twice reports the second attempt as a no-op`() = runTest {
        val id = assertIs<RememberOutcome.Created>(gateway.remember(profile("food.dislike", "香菜"))).memory.id

        assertTrue(gateway.forget(id))
        assertTrue(!gateway.forget(id))
    }

    // --- scoped identity ---

    @Test
    fun `two characters do not de-duplicate the same fact against each other`() = runTest {
        gateway.remember(profile("food.dislike", "香菜", scope = XIAOZHI))
        gateway.remember(profile("food.dislike", "香菜", scope = OTHER_CHARACTER))

        val all = gateway.list(statuses = setOf(MemoryStatus.CONFIRMED))

        assertEquals(2, all.size, "each character owns its own memory of the same fact")
        assertEquals(
            setOf(XIAOZHI, OTHER_CHARACTER),
            all.map { it.characterScope }.toSet(),
        )
    }

    @Test
    fun `one character correcting a fact leaves another character's value alone`() = runTest {
        gateway.remember(profile("food.dislike", "香菜", scope = XIAOZHI))
        val other = assertIs<RememberOutcome.Created>(
            gateway.remember(profile("food.dislike", "香菜", scope = OTHER_CHARACTER)),
        )

        val corrected = assertIs<RememberOutcome.Updated>(
            gateway.remember(profile("food.dislike", "芹菜", scope = XIAOZHI)),
        )

        assertTrue(corrected.memory.id != other.memory.id)
        val otherAfter = assertNotNull(store.getById(other.memory.id))
        assertEquals("香菜", (otherAfter as ProfileMemory).value, "the other character was overwritten")
    }

    @Test
    fun `recall can be scoped to one character`() = runTest {
        gateway.remember(profile("food.dislike", "香菜", scope = XIAOZHI))
        gateway.remember(profile("food.dislike", "香菜", scope = OTHER_CHARACTER))

        val scoped = gateway.recall(MemoryQuery(characterScope = XIAOZHI))

        assertEquals(1, scoped.size)
        assertEquals(XIAOZHI, scoped.single().characterScope)
    }

    // --- lifecycle ---

    @Test
    fun `a staged candidate is invisible to default recall and visible to list`() = runTest {
        val candidate = profile("food.dislike", "香菜", status = MemoryStatus.STAGED)
        gateway.stage(listOf(candidate))

        assertTrue(gateway.recall().isEmpty(), "an unconfirmed candidate reached the conversation")

        val forTheUser = gateway.list(statuses = setOf(MemoryStatus.STAGED))
        assertEquals(listOf(candidate.id), forTheUser.map { it.id })
    }

    @Test
    fun `confirming a candidate makes it visible to recall`() = runTest {
        val candidate = profile("food.dislike", "香菜", status = MemoryStatus.STAGED)
        gateway.stage(listOf(candidate))

        val confirmed = gateway.confirm(candidate.id)

        assertEquals(MemoryStatus.CONFIRMED, confirmed.status)
        assertEquals(listOf(candidate.id), gateway.recall().map { it.id })
    }

    @Test
    fun `rejecting a candidate keeps it out of recall but leaves it visible as rejected`() = runTest {
        val candidate = profile("food.dislike", "香菜", status = MemoryStatus.STAGED)
        gateway.stage(listOf(candidate))

        val rejected = gateway.reject(candidate.id)

        assertEquals(MemoryStatus.REJECTED, rejected.status)
        assertTrue(gateway.recall().isEmpty())
        assertEquals(listOf(candidate.id), gateway.list(statuses = setOf(MemoryStatus.REJECTED)).map { it.id })
    }

    @Test
    fun `confirming an unknown id fails`() = runTest {
        assertFailsWith<MemoryNotFoundException> { gateway.confirm(MemoryId.random()) }
    }

    @Test
    fun `rejecting an unknown id fails`() = runTest {
        assertFailsWith<MemoryNotFoundException> { gateway.reject(MemoryId.random()) }
    }

    @Test
    fun `remembering a staged record is refused`() = runTest {
        val staged = profile("food.dislike", "香菜", status = MemoryStatus.STAGED)

        assertFailsWith<MemoryNotConfirmedException> { gateway.remember(staged) }
        assertTrue(store.list().isEmpty(), "a refused write must not have written anything")
    }

    @Test
    fun `staging a confirmed record is refused`() = runTest {
        val confirmed = profile("food.dislike", "香菜")

        assertFailsWith<MemoryNotStageableException> { gateway.stage(listOf(confirmed)) }
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `confirming a rejected record is refused rather than resurrected`() = runTest {
        val candidate = profile("food.dislike", "香菜", status = MemoryStatus.STAGED)
        gateway.stage(listOf(candidate))
        gateway.reject(candidate.id)

        assertFailsWith<MemoryTransitionException> { gateway.confirm(candidate.id) }
    }

    @Test
    fun `rejecting a confirmed record is refused`() = runTest {
        val id = assertIs<RememberOutcome.Created>(gateway.remember(profile("food.dislike", "香菜"))).memory.id

        assertFailsWith<MemoryTransitionException> { gateway.reject(id) }
    }

    @Test
    fun `accepting a change to a known fact corrects the confirmed record and keeps its id`() = runTest {
        val existing = assertIs<RememberOutcome.Created>(
            gateway.remember(profile("food.dislike", "香菜")),
        ).memory.id
        val candidate = profile("food.dislike", "芹菜", status = MemoryStatus.STAGED)
        gateway.stage(listOf(candidate))

        val confirmed = gateway.confirm(candidate.id)

        assertEquals(existing, confirmed.id, "the confirmed record's id must be the stable one")
        assertEquals("芹菜", (confirmed as ProfileMemory).value)
        assertEquals(
            1,
            confirmedProfiles().size,
            "accepting a change must not leave two confirmed records for one fact",
        )
        assertNull(store.getById(candidate.id), "the superseded candidate should not linger")
    }

    // --- the retrievability claim, and only that claim ---

    @Test
    fun `a memory confirmed earlier is retrievable by a recall from a later gateway`() = runTest {
        val earlier: MemoryGateway = DefaultMemoryGateway(store)
        earlier.remember(profile("food.dislike", "香菜"))

        // A different gateway instance over the same store: this is the persistence boundary, not a
        // conversation. It proves the memory survived and can be retrieved again. It does not prove that
        // a conversation put it into context or answered from it - that needs the context builder.
        val later: MemoryGateway = DefaultMemoryGateway(store)
        val recalled = later.recall()

        assertEquals(listOf("food.dislike"), recalled.map { (it as ProfileMemory).attribute })
    }

    // --- fixtures ---

    private suspend fun confirmedProfiles(): List<CanonicalMemory> =
        store.list().filter { it.status == MemoryStatus.CONFIRMED && it.type == MemoryType.PROFILE }

    private fun profile(
        attribute: String,
        value: String,
        status: MemoryStatus = MemoryStatus.CONFIRMED,
        scope: CharacterScope = XIAOZHI,
        recordedAt: Instant = EARLY,
        excerpt: String = "$attribute=$value",
    ) = ProfileMemory(
        id = MemoryId.random(),
        importance = Importance.NORMAL,
        status = status,
        recordedAt = recordedAt,
        source = MemorySource.CONVERSATION,
        provenance = provenance(excerpt),
        characterScope = scope,
        attribute = attribute,
        value = value,
    )

    private fun event(title: String, scheduledFor: Instant?) = EventMemory(
        id = MemoryId.random(),
        importance = Importance.HIGH,
        status = MemoryStatus.CONFIRMED,
        recordedAt = EARLY,
        source = MemorySource.CONVERSATION,
        provenance = provenance(title),
        characterScope = XIAOZHI,
        title = title,
        scheduledFor = scheduledFor,
    )

    private fun provenance(excerpt: String) = Provenance(
        sessionId = "session-1",
        messageId = "message-1",
        excerpt = excerpt,
        extractor = "test-fixture",
    )

    private companion object {
        val EARLY: Instant = Instant.parse("2026-09-27T10:00:00Z")
        val LATE: Instant = Instant.parse("2026-09-28T10:00:00Z")
        val XIAOZHI = CharacterScope("xiaozhi")
        val OTHER_CHARACTER = CharacterScope("second-character")
    }
}
