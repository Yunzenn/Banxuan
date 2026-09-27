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
 * The edit contract of [MemoryGateway], exercised against the production implementation.
 *
 * The store underneath is a test double; the rules on trial are real. The cases that matter most are the
 * ones where an editor is tempted to be helpful: promoting a staged record, resurrecting a rejected one,
 * or merging an identity collision. Each of those is refused here on purpose.
 */
class MemoryEditContractTest {

    private val store = InMemoryMemoryStore()
    private val gateway: MemoryGateway = DefaultMemoryGateway(store)

    // ---------------------------------------------------------------- who may be edited

    @Test
    fun `editing an unknown id fails`() = runTest {
        assertFailsWith<MemoryNotFoundException> {
            gateway.edit(MemoryId.random(), ProfileEdit(EDITED_AT, "food.dislike", "芹菜"))
        }
    }

    @Test
    fun `a rejected memory cannot be edited`() = runTest {
        val staged = profile("m1", MemoryStatus.STAGED, "food.dislike", "香菜")
        gateway.stage(listOf(staged))
        gateway.reject(staged.id)

        assertFailsWith<MemoryNotEditableException> {
            gateway.edit(staged.id, ProfileEdit(EDITED_AT, "food.dislike", "芹菜"))
        }
        // A rejected memory stays exactly as it was: editing it would be confirming it by the back door.
        assertEquals(MemoryStatus.REJECTED, store.getById(staged.id)?.status)
        assertEquals("香菜", (store.getById(staged.id) as ProfileMemory).value)
    }

    @Test
    fun `an edit of the wrong type is refused`() = runTest {
        val id = assertIs<RememberOutcome.Created>(
            gateway.remember(profile("m1", MemoryStatus.CONFIRMED, "food.dislike", "香菜")),
        ).memory.id

        assertFailsWith<MemoryTypeMismatchException> {
            gateway.edit(id, EventEdit(EDITED_AT, "去医院", AT, null))
        }
        assertEquals("香菜", (store.getById(id) as ProfileMemory).value)
    }

    // ---------------------------------------------------------------- lifecycle is preserved

    @Test
    fun `editing a staged memory leaves it staged and unconfirmed`() = runTest {
        val staged = profile("m1", MemoryStatus.STAGED, "food.dislike", "香菜")
        gateway.stage(listOf(staged))

        val outcome = assertIs<EditOutcome.Updated>(
            gateway.edit(staged.id, ProfileEdit(EDITED_AT, "food.dislike", "芹菜")),
        )

        assertEquals("m1", outcome.memory.id.value, "the id must not move")
        assertEquals(MemoryStatus.STAGED, outcome.memory.status, "editing is not confirming")
        assertEquals("芹菜", (outcome.memory as ProfileMemory).value)
        // The whole point: an edited candidate is still a candidate.
        assertTrue(gateway.recall().isEmpty(), "an edited candidate reached the conversation")
    }

    @Test
    fun `editing a confirmed memory leaves it confirmed`() = runTest {
        val created = assertIs<RememberOutcome.Created>(
            gateway.remember(profile("m1", MemoryStatus.CONFIRMED, "food.dislike", "香菜")),
        )

        val outcome = assertIs<EditOutcome.Updated>(
            gateway.edit(created.memory.id, ProfileEdit(EDITED_AT, "food.dislike", "芹菜")),
        )

        assertEquals("m1", outcome.memory.id.value)
        assertEquals(MemoryStatus.CONFIRMED, outcome.memory.status)
        assertEquals("芹菜", (outcome.memory as ProfileMemory).value)
        assertEquals(listOf("m1"), gateway.recall().map { it.id.value })
    }

    @Test
    fun `an edit never touches id, status, character scope or importance`() = runTest {
        val original = ProfileMemory(
            id = MemoryId("m1"),
            importance = Importance.HIGH,
            status = MemoryStatus.CONFIRMED,
            recordedAt = EARLY,
            source = MemorySource.CONVERSATION,
            provenance = provenance("我不喜欢香菜"),
            characterScope = CharacterScope("xiaozhi"),
            attribute = "food.dislike",
            value = "香菜",
        )
        gateway.remember(original)

        val outcome = assertIs<EditOutcome.Updated>(
            gateway.edit(original.id, ProfileEdit(EDITED_AT, "food.dislike", "芹菜")),
        )

        assertEquals(original.id, outcome.memory.id)
        assertEquals(original.characterScope, outcome.memory.characterScope)
        assertEquals(original.importance, outcome.memory.importance)
        assertEquals(MemoryStatus.CONFIRMED, outcome.memory.status)
    }

    // ---------------------------------------------------------------- the audit envelope

    @Test
    fun `a real edit records the user as the new source`() = runTest {
        val created = assertIs<RememberOutcome.Created>(
            gateway.remember(profile("m1", MemoryStatus.CONFIRMED, "food.dislike", "香菜")),
        )

        val outcome = assertIs<EditOutcome.Updated>(
            gateway.edit(created.memory.id, ProfileEdit(EDITED_AT, "food.dislike", "芹菜")),
        )

        assertEquals(MemorySource.USER_EDIT, outcome.memory.source)
        assertEquals(EDITED_AT, outcome.memory.recordedAt)
        assertEquals(USER_EDIT_EXTRACTOR, outcome.memory.provenance.extractor)
        assertNull(outcome.memory.provenance.sessionId)
        assertNull(outcome.memory.provenance.messageId)
        assertEquals(
            "",
            outcome.memory.provenance.excerpt,
            "the sentence the fact was extracted from is no longer why she believes it",
        )
    }

    @Test
    fun `an edit that changes nothing writes nothing and keeps the original provenance`() = runTest {
        val created = assertIs<RememberOutcome.Created>(
            gateway.remember(profile("m1", MemoryStatus.CONFIRMED, "food.dislike", "香菜")),
        )

        val outcome = assertIs<EditOutcome.Unchanged>(
            gateway.edit(created.memory.id, ProfileEdit(EDITED_AT, "food.dislike", "香菜")),
        )

        assertEquals(EARLY, outcome.existing.recordedAt, "a no-op Save refreshed recordedAt")
        assertEquals(MemorySource.CONVERSATION, outcome.existing.source)
        assertEquals("我不喜欢香菜", outcome.existing.provenance.excerpt)
        assertEquals(
            "test-fixture",
            outcome.existing.provenance.extractor,
            "a no-op Save must not relabel the original provenance as a user edit",
        )
    }

    // ---------------------------------------------------------------- identity may move

    @Test
    fun `a confirmed memory may move to a new identity and keeps its id`() = runTest {
        val created = assertIs<RememberOutcome.Created>(
            gateway.remember(profile("m1", MemoryStatus.CONFIRMED, "food.dislike", "香菜")),
        )

        val outcome = assertIs<EditOutcome.Updated>(
            gateway.edit(created.memory.id, ProfileEdit(EDITED_AT, "food.like", "香菜")),
        )

        assertEquals("m1", outcome.memory.id.value)
        assertEquals("food.like", (outcome.memory as ProfileMemory).attribute)
        // The old identity is gone: one record, moved, not two.
        assertEquals(1, store.list().size)
        assertEquals(
            listOf("food.like"),
            gateway.recall().map { (it as ProfileMemory).attribute },
        )
    }

    @Test
    fun `editing an event time moves the identity so it is no longer the same fact`() = runTest {
        val first = event("e1", Instant.parse("2026-10-05T07:00:00Z"))
        gateway.remember(first)

        val outcome = assertIs<EditOutcome.Updated>(
            gateway.edit(first.id, EventEdit(EDITED_AT, "去医院", Instant.parse("2026-10-09T07:00:00Z"), null)),
        )

        assertEquals("e1", outcome.memory.id.value)
        assertEquals(Instant.parse("2026-10-09T07:00:00Z"), (outcome.memory as EventMemory).scheduledFor)
        assertEquals(1, store.list().size)
    }

    @Test
    fun `an edit colliding with another confirmed fact is refused and touches neither record`() = runTest {
        gateway.remember(profile("m1", MemoryStatus.CONFIRMED, "food.dislike", "香菜"))
        gateway.remember(profile("m2", MemoryStatus.CONFIRMED, "food.like", "芹菜"))

        assertFailsWith<MemoryIdentityConflictException> {
            gateway.edit(MemoryId("m1"), ProfileEdit(EDITED_AT, "food.like", "香菜"))
        }

        // Neither record moved. Merging would have consumed one id and overwritten the other.
        assertEquals("food.dislike", (store.getById(MemoryId("m1")) as ProfileMemory).attribute)
        assertEquals("food.like", (store.getById(MemoryId("m2")) as ProfileMemory).attribute)
        assertEquals(2, store.list().size)
    }

    @Test
    fun `a staged memory may move onto a confirmed fact because that is a pending correction`() = runTest {
        gateway.remember(profile("m1", MemoryStatus.CONFIRMED, "food.dislike", "香菜"))
        val staged = profile("s1", MemoryStatus.STAGED, "food.dislike", "芹菜")
        gateway.stage(listOf(staged))

        val outcome = assertIs<EditOutcome.Updated>(
            gateway.edit(staged.id, ProfileEdit(EDITED_AT, "food.like", "芹菜")),
        )

        assertEquals(MemoryStatus.STAGED, outcome.memory.status)
        assertEquals("food.like", (outcome.memory as ProfileMemory).attribute)
        assertEquals(2, store.list().size)
    }

    // ---------------------------------------------------------------- every type can be edited

    @Test
    fun `an episode can be edited`() = runTest {
        val episode = EpisodeMemory(
            id = MemoryId("p1"),
            importance = Importance.NORMAL,
            status = MemoryStatus.CONFIRMED,
            recordedAt = EARLY,
            source = MemorySource.CONVERSATION,
            provenance = provenance("和室友吵架了"),
            characterScope = CharacterScope("xiaozhi"),
            summary = "和室友吵架了",
            occurredAt = AT,
            emotionalTone = "委屈",
            relations = setOf("室友"),
        )
        gateway.remember(episode)

        val outcome = assertIs<EditOutcome.Updated>(
            gateway.edit(
                episode.id,
                EpisodeEdit(EDITED_AT, "和室友和好了", AT, "轻松", setOf("室友")),
            ),
        )

        val edited = outcome.memory as EpisodeMemory
        assertEquals("和室友和好了", edited.summary)
        assertEquals("轻松", edited.emotionalTone)
        assertEquals(edited, store.getById(episode.id))
    }

    @Test
    fun `a relation can be edited and its role change moves the identity`() = runTest {
        val relation = RelationMemory(
            id = MemoryId("r1"),
            importance = Importance.NORMAL,
            status = MemoryStatus.CONFIRMED,
            recordedAt = EARLY,
            source = MemorySource.CONVERSATION,
            provenance = provenance("小李是我室友"),
            characterScope = CharacterScope("xiaozhi"),
            name = "小李",
            role = "室友",
        )
        gateway.remember(relation)

        val outcome = assertIs<EditOutcome.Updated>(
            gateway.edit(relation.id, RelationEdit(EDITED_AT, "小李", "同事", "换工作了")),
        )

        val edited = outcome.memory as RelationMemory
        assertEquals("同事", edited.role)
        assertEquals("换工作了", edited.note)
        assertNotNull(store.getById(relation.id))
    }

    // ---------------------------------------------------------------- fixtures

    private fun provenance(excerpt: String) = Provenance(
        sessionId = "s1",
        messageId = "m1",
        excerpt = excerpt,
        extractor = "test-fixture",
    )

    private fun profile(id: String, status: MemoryStatus, attribute: String, value: String) =
        ProfileMemory(
            id = MemoryId(id),
            importance = Importance.NORMAL,
            status = status,
            recordedAt = EARLY,
            source = MemorySource.CONVERSATION,
            provenance = provenance(if (attribute == "food.dislike") "我不喜欢香菜" else "我爱吃芹菜"),
            characterScope = CharacterScope("xiaozhi"),
            attribute = attribute,
            value = value,
        )

    private fun event(id: String, scheduledFor: Instant) = EventMemory(
        id = MemoryId(id),
        importance = Importance.HIGH,
        status = MemoryStatus.CONFIRMED,
        recordedAt = EARLY,
        source = MemorySource.CONVERSATION,
        provenance = provenance("下周三去医院"),
        characterScope = CharacterScope("xiaozhi"),
        title = "去医院",
        scheduledFor = scheduledFor,
    )

    private companion object {
        val EARLY: Instant = Instant.parse("2026-09-27T10:00:00Z")
        val EDITED_AT: Instant = Instant.parse("2026-09-28T10:00:00Z")
        val AT: Instant = Instant.parse("2026-09-26T13:00:00Z")
    }
}
