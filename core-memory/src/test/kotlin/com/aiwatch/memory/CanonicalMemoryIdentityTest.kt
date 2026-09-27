package com.aiwatch.memory

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins the semantics that de-duplication and recall depend on.
 *
 * These are the assertions that make "记得住" a product property rather than a hope: if two recordings of
 * the same fact stop producing one identity, v0.4's whole point is broken, and this file says so before
 * anyone ships it.
 */
class CanonicalMemoryIdentityTest {

    // --- PROFILE: identity must ignore the value, or a correction becomes a second memory ---

    @Test
    fun `profile identity ignores the value so a correction updates one fact`() {
        val first = profile(attribute = "food.dislike", value = "香菜")
        val corrected = profile(attribute = "food.dislike", value = "芹菜")

        assertEquals(first.identity, corrected.identity)
        assertNotEquals(first.value, corrected.value)
    }

    @Test
    fun `profile identity folds case whitespace and trailing punctuation`() {
        val variants = listOf(
            "Food.Dislike",
            "  food.dislike  ",
            "food.dislike。",
            "food. dislike",
        )

        val keys = variants.map { profile(attribute = it, value = "香菜").identity.key }.toSet()

        assertEquals(1, keys.size, "expected one fact for $variants, got $keys")
    }

    @Test
    fun `different profile attributes stay different facts`() {
        assertNotEquals(
            profile(attribute = "food.dislike", value = "香菜").identity,
            profile(attribute = "food.like", value = "香菜").identity,
        )
    }

    @Test
    fun `free text keeps internal spacing that an attribute path folds away`() {
        // The two normalisations differ on purpose: an attribute path is an identifier, prose is not.
        assertEquals("food.dislike", normalizeAttributePath("food. dislike"))
        assertEquals("food. dislike", normalizeFact("food. dislike"))
    }

    // --- EVENT: the resolved time is part of the fact ---

    @Test
    fun `event identity separates the same title at different times`() {
        val monday = event(title = "去医院", scheduledFor = Instant.parse("2026-10-05T07:00:00Z"))
        val friday = event(title = "去医院", scheduledFor = Instant.parse("2026-10-09T07:00:00Z"))

        assertNotEquals(monday.identity, friday.identity)
    }

    @Test
    fun `event identity matches when title and time agree`() {
        val instant = Instant.parse("2026-10-05T07:00:00Z")

        assertEquals(event("去医院", instant).identity, event("  去医院 ", instant).identity)
    }

    @Test
    fun `undated events with the same title are one fact`() {
        assertEquals(event("买药", null).identity, event("买药", null).identity)
    }

    // --- EPISODE: a recurring phrase on another day is another episode ---

    @Test
    fun `episode identity carries the occurrence time`() {
        val yesterday = episode("和室友吵架了", Instant.parse("2026-09-26T13:00:00Z"))
        val today = episode("和室友吵架了", Instant.parse("2026-09-27T13:00:00Z"))

        assertNotEquals(yesterday.identity, today.identity)
    }

    @Test
    fun `episode identity ignores emotional tone and relation names`() {
        val calm = episode("和室友吵架了", AT, emotionalTone = "委屈", relations = setOf("室友"))
        val reframed = episode("和室友吵架了", AT, emotionalTone = null, relations = emptySet())

        assertEquals(calm.identity, reframed.identity)
    }

    // --- RELATION ---

    @Test
    fun `relation identity is name plus role`() {
        assertEquals(
            relation(name = "小李", role = "室友").identity,
            relation(name = " 小李 ", role = "室友。").identity,
        )
        assertNotEquals(
            relation(name = "小李", role = "室友").identity,
            relation(name = "小李", role = "同事").identity,
        )
    }

    // --- Lifecycle ---

    @Test
    fun `withStatus changes only the status`() {
        val staged = profile(attribute = "food.dislike", value = "香菜", status = MemoryStatus.STAGED)

        val confirmed = staged.withStatus(MemoryStatus.CONFIRMED) as ProfileMemory

        assertEquals(MemoryStatus.CONFIRMED, confirmed.status)
        assertEquals(staged.copy(status = MemoryStatus.CONFIRMED), confirmed)
    }

    @Test
    fun `identity is stable across a status change`() {
        val staged = profile(attribute = "food.dislike", value = "香菜", status = MemoryStatus.STAGED)

        assertEquals(staged.identity, staged.withStatus(MemoryStatus.CONFIRMED).identity)
    }

    // --- The safety property recall depends on ---

    @Test
    fun `default recall query cannot return a staged candidate`() {
        val query = MemoryQuery()

        assertTrue(MemoryStatus.CONFIRMED in query.statuses)
        assertFalse(
            MemoryStatus.STAGED in query.statuses,
            "an unconfirmed candidate must never reach the conversation by default",
        )
    }

    @Test
    fun `default recall query covers every type and stays small`() {
        val query = MemoryQuery()

        assertEquals(MemoryType.entries.toSet(), query.types)
        assertTrue(query.limit <= 10, "recall feeds a latency budget; limit was ${query.limit}")
    }

    @Test
    fun `every type reports its own kind`() {
        assertEquals(MemoryType.PROFILE, profile("food.dislike", "香菜").type)
        assertEquals(MemoryType.EVENT, event("去医院", AT).type)
        assertEquals(MemoryType.EPISODE, episode("和室友吵架了", AT).type)
        assertEquals(MemoryType.RELATION, relation("小李", "室友").type)
    }

    // --- fixtures ---

    private fun provenance(excerpt: String) = Provenance(
        sessionId = "session-1",
        messageId = "message-1",
        excerpt = excerpt,
        extractor = "test-fixture",
    )

    private fun profile(
        attribute: String,
        value: String,
        status: MemoryStatus = MemoryStatus.CONFIRMED,
    ) = ProfileMemory(
        id = MemoryId.random(),
        importance = Importance.NORMAL,
        status = status,
        recordedAt = RECORDED_AT,
        source = MemorySource.CONVERSATION,
        provenance = provenance("$attribute=$value"),
        characterScope = SCOPE,
        attribute = attribute,
        value = value,
    )

    private fun event(title: String, scheduledFor: Instant?) = EventMemory(
        id = MemoryId.random(),
        importance = Importance.HIGH,
        status = MemoryStatus.CONFIRMED,
        recordedAt = RECORDED_AT,
        source = MemorySource.CONVERSATION,
        provenance = provenance(title),
        characterScope = SCOPE,
        title = title,
        scheduledFor = scheduledFor,
    )

    private fun episode(
        summary: String,
        occurredAt: Instant,
        emotionalTone: String? = null,
        relations: Set<String> = emptySet(),
    ) = EpisodeMemory(
        id = MemoryId.random(),
        importance = Importance.NORMAL,
        status = MemoryStatus.CONFIRMED,
        recordedAt = RECORDED_AT,
        source = MemorySource.CONVERSATION,
        provenance = provenance(summary),
        characterScope = SCOPE,
        summary = summary,
        occurredAt = occurredAt,
        emotionalTone = emotionalTone,
        relations = relations,
    )

    private fun relation(name: String, role: String) = RelationMemory(
        id = MemoryId.random(),
        importance = Importance.NORMAL,
        status = MemoryStatus.CONFIRMED,
        recordedAt = RECORDED_AT,
        source = MemorySource.CONVERSATION,
        provenance = provenance("$name/$role"),
        characterScope = SCOPE,
        name = name,
        role = role,
    )

    private companion object {
        val RECORDED_AT: Instant = Instant.parse("2026-09-27T10:00:00Z")
        val AT: Instant = Instant.parse("2026-09-26T13:00:00Z")
        val SCOPE = CharacterScope("xiaozhi")
    }
}
