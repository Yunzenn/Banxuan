package com.aiwatch.memory

import java.time.Instant
import java.util.UUID

/**
 * The single typed memory schema of this product.
 *
 * `REUSE_AUDIT.md` fixes the rule this file exists to enforce: no component other than
 * [CanonicalMemory] may describe itself as "the memory". Mem0, a vector store, or a future reranker is a
 * substrate underneath this schema, never a competing definition of what the companion knows.
 *
 * The canonical store is server-side (`ROADMAP.md`). This module holds the *semantics* - the types,
 * content identity, staged confirmation and the gateway contract - precisely so that they can be
 * verified without a network or a device. That is why `:core-memory` is a plain JVM module.
 *
 * v0.4 deliberately contains no embedding, no vector search, no time decay and no reranker. Those are
 * v0.5. This model is small on purpose: a research system is not the same product as a companion that
 * remembers.
 */

/** What kind of thing the companion believes it knows. Fixed at four; see the memory model in ROADMAP. */
enum class MemoryType {
    /** A durable attribute of the user: `food.dislike = 香菜`. */
    PROFILE,

    /** A commitment or appointment with a time: `下周三下午三点去医院`. */
    EVENT,

    /** Something that happened, with emotional context: `昨天和室友吵架了`. */
    EPISODE,

    /** A person and how they relate to the user: `室友`. */
    RELATION,
}

/** How much the companion should care. Set by the extractor, surfaced in "我的记忆". */
enum class Importance { LOW, NORMAL, HIGH }

/**
 * Where a memory came from.
 *
 * [USER_EDIT] exists because "我的记忆" lets the user correct her. A user-authored fact must stay
 * distinguishable from one the model inferred, otherwise the audit trail lies about who said it.
 */
enum class MemorySource { CONVERSATION, USER_EDIT, INFERRED, IMPORT }

/**
 * A memory's lifecycle. Only [CONFIRMED] memories reach the conversation.
 *
 * Staging is the trust mechanism, not a queue for tidiness: the user sees a candidate before it becomes
 * something the companion treats as true.
 */
enum class MemoryStatus { STAGED, CONFIRMED, REJECTED }

/** Identity of one memory record. Not derived from content: see [CanonicalMemory.identity] for that. */
@JvmInline
value class MemoryId(val value: String) {
    companion object {
        fun random(): MemoryId = MemoryId(UUID.randomUUID().toString())
    }
}

/**
 * Which character the memory belongs to.
 *
 * Companion scope, not user scope. A memory learned by one character is not automatically available to
 * another, and every query is scoped by this rather than by a global "the user's memories".
 */
@JvmInline
value class CharacterScope(val id: String)

/**
 * Where a memory came from, in enough detail to show the user and to audit later.
 *
 * [excerpt] is the literal text the fact was extracted from. The "我的记忆" screen shows it so the user
 * can judge the claim instead of being asked to trust a summary.
 */
data class Provenance(
    val sessionId: String?,
    val messageId: String?,
    val excerpt: String,
    val extractor: String,
)

/**
 * Content identity: what makes two memories "the same fact".
 *
 * This is what de-duplication keys on, and it is deliberately *not* the whole record. Two
 * [ProfileMemory] records with the same [ProfileMemory.attribute] but different values are the same
 * fact with a new value - that is an update, not a second memory. Two events with the same title at
 * different times are genuinely different facts.
 */
data class MemoryIdentity(val type: MemoryType, val key: String)

/**
 * Normalises a fact before it becomes part of a [MemoryIdentity].
 *
 * Case and whitespace folding is obvious. Trailing sentence punctuation is stripped as well: an
 * extractor that emits `香菜。` and one that emits `香菜` mean the same thing, and a de-duplication
 * policy that treats them as different facts produces exactly the duplicate memories v0.4 exists to
 * prevent.
 */
fun normalizeFact(text: String): String =
    text.trim()
        .lowercase()
        .replace(WHITESPACE, " ")
        .trimEnd(*TRAILING_PUNCTUATION)

/**
 * Normalises a dotted attribute path such as `food.dislike`.
 *
 * An attribute path is an identifier, not prose, so it gets one extra fold that [normalizeFact] must not
 * apply: whitespace around the `.` separators is insignificant. Two extractors that emit `food.dislike`
 * and `food. dislike` mean the same attribute, and treating them as different produces the duplicate
 * memories v0.4 exists to prevent. Free text keeps its internal spacing, which is why this is a separate
 * function rather than a stronger [normalizeFact].
 */
fun normalizeAttributePath(path: String): String =
    normalizeFact(path).replace(ATTRIBUTE_SEPARATOR_PADDING, ".")

private val WHITESPACE = Regex("\\s+")
private val ATTRIBUTE_SEPARATOR_PADDING = Regex("\\s*\\.\\s*")
private val TRAILING_PUNCTUATION = charArrayOf('.', '。', '．', '!', '！', ',', '，', ';', '；')

/**
 * One typed memory record.
 *
 * Every implementation carries the same envelope - id, kind, importance, lifecycle status, when it was
 * recorded, where it came from, which character owns it - and then its own content. Code that only
 * needs the envelope never has to know which of the four types it is holding.
 */
sealed interface CanonicalMemory {
    val id: MemoryId
    val type: MemoryType
    val importance: Importance
    val status: MemoryStatus

    /** When the companion learned this. Distinct from when the event happens or happened. */
    val recordedAt: Instant
    val source: MemorySource
    val provenance: Provenance
    val characterScope: CharacterScope

    /** Content identity used for de-duplication. See [MemoryIdentity]. */
    val identity: MemoryIdentity

    /** Same memory, different lifecycle status. Lets confirm/reject work without type dispatch. */
    fun withStatus(status: MemoryStatus): CanonicalMemory
}

/** A durable attribute of the user. Identity ignores [value] so that a correction updates in place. */
data class ProfileMemory(
    override val id: MemoryId,
    override val importance: Importance,
    override val status: MemoryStatus,
    override val recordedAt: Instant,
    override val source: MemorySource,
    override val provenance: Provenance,
    override val characterScope: CharacterScope,
    /** Dotted attribute path produced by the extractor, e.g. `food.dislike`. */
    val attribute: String,
    val value: String,
) : CanonicalMemory {
    override val type: MemoryType get() = MemoryType.PROFILE
    override val identity: MemoryIdentity
        get() = MemoryIdentity(MemoryType.PROFILE, normalizeAttributePath(attribute))

    override fun withStatus(status: MemoryStatus): CanonicalMemory = copy(status = status)
}

/**
 * A commitment or appointment.
 *
 * [scheduledFor] is the *resolved* instant, not the phrase. "下周三下午三点" is resolved by the
 * extractor before it reaches this type; an unresolved phrase stays out of the identity rather than
 * being guessed at here.
 */
data class EventMemory(
    override val id: MemoryId,
    override val importance: Importance,
    override val status: MemoryStatus,
    override val recordedAt: Instant,
    override val source: MemorySource,
    override val provenance: Provenance,
    override val characterScope: CharacterScope,
    val title: String,
    val scheduledFor: Instant? = null,
    val location: String? = null,
) : CanonicalMemory {
    override val type: MemoryType get() = MemoryType.EVENT
    override val identity: MemoryIdentity
        get() = MemoryIdentity(
            MemoryType.EVENT,
            normalizeFact(title) + "@" + (scheduledFor?.toString() ?: UNDATED),
        )

    override fun withStatus(status: MemoryStatus): CanonicalMemory = copy(status = status)

    private companion object {
        const val UNDATED = "undated"
    }
}

/** Something that happened. Identity carries [occurredAt] so that a recurring phrase stays two facts. */
data class EpisodeMemory(
    override val id: MemoryId,
    override val importance: Importance,
    override val status: MemoryStatus,
    override val recordedAt: Instant,
    override val source: MemorySource,
    override val provenance: Provenance,
    override val characterScope: CharacterScope,
    val summary: String,
    val occurredAt: Instant,
    /** Free-form emotional context as reported, e.g. `委屈`. Not a classifier output. */
    val emotionalTone: String? = null,
    /** Names of the people involved. Resolving these to [RelationMemory] ids is v0.5 work. */
    val relations: Set<String> = emptySet(),
) : CanonicalMemory {
    override val type: MemoryType get() = MemoryType.EPISODE
    override val identity: MemoryIdentity
        get() = MemoryIdentity(MemoryType.EPISODE, normalizeFact(summary) + "@" + occurredAt)

    override fun withStatus(status: MemoryStatus): CanonicalMemory = copy(status = status)
}

/** A person in the user's life. [role] stays an open label: kinship terms do not fit an enum. */
data class RelationMemory(
    override val id: MemoryId,
    override val importance: Importance,
    override val status: MemoryStatus,
    override val recordedAt: Instant,
    override val source: MemorySource,
    override val provenance: Provenance,
    override val characterScope: CharacterScope,
    val name: String,
    val role: String,
    val note: String? = null,
) : CanonicalMemory {
    override val type: MemoryType get() = MemoryType.RELATION
    override val identity: MemoryIdentity
        get() = MemoryIdentity(MemoryType.RELATION, normalizeFact(name) + "/" + normalizeFact(role))

    override fun withStatus(status: MemoryStatus): CanonicalMemory = copy(status = status)
}
