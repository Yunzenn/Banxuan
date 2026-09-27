package com.aiwatch.memory

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Drives the shared cross-language contract against the real [DefaultMemoryGateway].
 *
 * `evidence/contracts/canonical-memory-v1.json` is the source of truth for the cross-language
 * semantics, and neither implementation is. This class reads that file from the test classpath and
 * contains no fixture data of its own: adding a case to the contract adds a test here with no Kotlin
 * edit, and changing an expectation cannot leave the two languages quietly disagreeing.
 *
 * The operational interpretation deliberately matches the Python authority's harness - which keys are
 * compared, how records are serialised, how errors map to codes - because two harnesses that interpret
 * the same file differently would produce exactly the drift the file exists to prevent.
 *
 * What belongs here is only what is language-neutral. Kotlin API shape, nullability and exception
 * hierarchy stay in the module's native tests, which this class neither weakens nor replaces.
 */
class CanonicalMemoryConformanceTest {

    // Declared before `cases` and `errorCodes`, which read from it: Kotlin initialises properties in
    // declaration order, and a throw from the loader should be the first thing anyone sees.
    private val contract: JsonObject = loadContract()
    private val cases: List<JsonObject> = contract.array("cases").map { it.asJsonObject }
    private val errorCodes: Set<String> = contract.array("errorCodes").map { it.asString }.toSet()

    @Test
    fun `contract file is versioned and declares every language-neutral error code`() {
        assertEquals("canonical-memory", contract["contract"].asString)
        assertEquals(2, contract["version"].asInt)
        assertEquals(
            setOf(
                "MEMORY_NOT_FOUND",
                "MEMORY_NOT_CONFIRMED",
                "MEMORY_NOT_STAGEABLE",
                "INVALID_TRANSITION",
                "MEMORY_NOT_EDITABLE",
                "MEMORY_TYPE_MISMATCH",
                "MEMORY_IDENTITY_CONFLICT",
            ),
            errorCodes,
        )
        assertTrue(cases.size >= 27, "contract declares only ${cases.size} cases")
    }

    /**
     * One test per contract case, named after the case.
     *
     * `@TestFactory` rather than `@ParameterizedTest` because `junit-jupiter-params` is not in this
     * environment's offline cache while `junit-jupiter-api` is, and because a dynamic test per case
     * reports each contract case individually instead of collapsing them into one result.
     */
    @TestFactory
    fun `shared contract cases`(): List<DynamicTest> = cases.map { case ->
        val name = case["name"].asString
        DynamicTest.dynamicTest(name) { runCase(case) }
    }

    // --- case execution -------------------------------------------------------------------------

    private fun runCase(case: JsonObject) {
        val name = case["name"].asString
        val store = InMemoryMemoryStore()
        // Typed as the interface so `recall()` and `list()` resolve their defaults from the contract
        // declaration, which is what the fixture's empty query means.
        val gateway: MemoryGateway = DefaultMemoryGateway(store)

        runBlocking {
            // Preconditions go straight into the store, bypassing the gateway, exactly as the Python
            // harness seeds `initial`: a case about recall must not depend on the write path.
            case.array("initial").forEach { store.put(memory(it.asJsonObject)) }

            var outcome: String? = null
            var editOutcome: String? = null
            val forgetResults = mutableListOf<Boolean>()
            val recalls = mutableListOf<List<String>>()
            val listings = mutableListOf<List<String>>()
            var error: String? = null

            try {
                case.array("operations").forEach { element ->
                    val op = element.asJsonObject
                    when (val kind = op["op"].asString) {
                        "remember" -> outcome = gateway.remember(memory(op.obj("record"))).outcomeName
                        "edit" -> editOutcome = gateway.edit(
                            MemoryId(op["id"].asString),
                            editOf(op.obj("edit")),
                        ).outcomeName
                        "stage" -> gateway.stage(listOf(memory(op.obj("record"))))
                        "confirm" -> gateway.confirm(MemoryId(op["id"].asString))
                        "reject" -> gateway.reject(MemoryId(op["id"].asString))
                        "forget" -> forgetResults += gateway.forget(MemoryId(op["id"].asString))
                        "recall" -> recalls += gateway.recall(query(op.obj("query"))).map { it.id.value }
                        "list" -> listings += gateway.list(
                            statuses = op.optArray("statuses")
                                ?.map { MemoryStatus.valueOf(it.asString) }
                                ?.toSet()
                                ?: MemoryStatus.entries.toSet(),
                        ).map { it.id.value }

                        else -> fail("$name: unknown op '$kind'")
                    }
                }
            } catch (notFound: MemoryNotFoundException) {
                error = "MEMORY_NOT_FOUND"
            } catch (notConfirmed: MemoryNotConfirmedException) {
                error = "MEMORY_NOT_CONFIRMED"
            } catch (notStageable: MemoryNotStageableException) {
                error = "MEMORY_NOT_STAGEABLE"
            } catch (transition: MemoryTransitionException) {
                error = "INVALID_TRANSITION"
            } catch (notEditable: MemoryNotEditableException) {
                error = "MEMORY_NOT_EDITABLE"
            } catch (typeMismatch: MemoryTypeMismatchException) {
                error = "MEMORY_TYPE_MISMATCH"
            } catch (conflict: MemoryIdentityConflictException) {
                error = "MEMORY_IDENTITY_CONFLICT"
            }

            val records = store.list()
            checkCase(
                case = case,
                name = name,
                error = error,
                outcome = outcome,
                editOutcome = editOutcome,
                forgetResults = forgetResults.toList(),
                recalls = recalls.toList(),
                listings = listings.toList(),
                actualStore = records.map(::snapshot),
                actualAudit = records.associate { it.id.value to auditOf(it) },
            )
        }
    }

    private fun checkCase(
        case: JsonObject,
        name: String,
        error: String?,
        outcome: String?,
        editOutcome: String?,
        forgetResults: List<Boolean>,
        recalls: List<List<String>>,
        listings: List<List<String>>,
        actualStore: List<Map<String, Any>>,
        actualAudit: Map<String, Map<String, Any?>>,
    ) {
        val expect = case.obj("expect")

        if (expect.has("error")) {
            val wanted = expect["error"].asString
            assertEquals(wanted, error, "$name: expected $wanted, got $error")
            assertTrue(wanted in errorCodes, "$name: undeclared error code $wanted")
        } else {
            assertEquals(null, error, "$name: unexpected error $error")
        }

        if (expect.has("outcome")) {
            assertEquals(expect["outcome"].asString, outcome, "$name: outcome $outcome")
        }
        if (expect.has("editOutcome")) {
            assertEquals(expect["editOutcome"].asString, editOutcome, "$name: editOutcome $editOutcome")
        }
        // The audit envelope is compared only when a case asks, so that v1's comparisons keep meaning
        // exactly what they meant. Folding these fields into every snapshot would have silently changed
        // an older contract.
        if (expect.has("audit")) {
            val wanted = expect.obj("audit")
            val target = wanted["id"].asString
            val actual = actualAudit[target] ?: fail("$name: no record '$target' to audit")
            wanted.entrySet().forEach { (field, element) ->
                if (field == "id") return@forEach
                val expectedValue: Any? = if (element.isJsonNull) null else element.asString
                assertEquals(expectedValue, actual[field], "$name: audit $field")
            }
        }
        if (expect.has("forgetResults")) {
            assertEquals(
                expect.array("forgetResults").map { it.asBoolean },
                forgetResults,
                "$name: forget $forgetResults",
            )
        }
        if (expect.has("recallBefore")) {
            assertEquals(
                expect.array("recallBefore").map { it.asString },
                recalls.firstOrNull(),
                "$name: recallBefore ${recalls.firstOrNull()}",
            )
        }
        if (expect.has("recallAfter")) {
            assertEquals(
                expect.array("recallAfter").map { it.asString },
                recalls.lastOrNull(),
                "$name: recallAfter ${recalls.lastOrNull()}",
            )
        }
        if (expect.has("listAfter")) {
            assertEquals(
                expect.array("listAfter").map { it.asString },
                listings.lastOrNull(),
                "$name: listAfter ${listings.lastOrNull()}",
            )
        }

        if (expect.has("store")) {
            val actual = actualStore.sortedBy { it["id"] as String }
            val wanted = expect.array("store")
                .map { snapshot(memory(it.asJsonObject)) }
                .sortedBy { it["id"] as String }
            assertEquals(wanted, actual, "$name: store mismatch")
        }
    }

    // --- contract -> model ----------------------------------------------------------------------

    private fun memory(spec: JsonObject): CanonicalMemory {
        val id = MemoryId(spec["id"].asString)
        val status = MemoryStatus.valueOf(spec["status"].asString)
        val recordedAt = instant(spec["recordedAt"].asString)
        val scope = CharacterScope(spec.str("characterScope") ?: DEFAULT_CHARACTER_SCOPE)
        // Read from the contract rather than hardcoded. The Python harness honours this field, so
        // hardcoding NORMAL here made the two runners interpret the same contract differently - a
        // divergence that stayed invisible until a case actually specified a non-default importance.
        val importance = spec.str("importance")?.let(Importance::valueOf) ?: Importance.NORMAL
        val provenance = Provenance(
            sessionId = null,
            messageId = null,
            excerpt = spec.str("excerpt") ?: "",
            extractor = "canonical-memory-v1",
        )

        return when (val type = spec["type"].asString) {
            "PROFILE" -> ProfileMemory(
                id = id,
                importance = importance,
                status = status,
                recordedAt = recordedAt,
                source = MemorySource.CONVERSATION,
                provenance = provenance,
                characterScope = scope,
                attribute = spec["attribute"].asString,
                value = spec["value"].asString,
            )

            "EVENT" -> EventMemory(
                id = id,
                importance = importance,
                status = status,
                recordedAt = recordedAt,
                source = MemorySource.CONVERSATION,
                provenance = provenance,
                characterScope = scope,
                title = spec["title"].asString,
                scheduledFor = spec.str("scheduledFor")?.let(::instant),
                location = spec.str("location"),
            )

            "EPISODE" -> EpisodeMemory(
                id = id,
                importance = importance,
                status = status,
                recordedAt = recordedAt,
                source = MemorySource.CONVERSATION,
                provenance = provenance,
                characterScope = scope,
                summary = spec["summary"].asString,
                occurredAt = instant(spec["occurredAt"].asString),
                emotionalTone = spec.str("emotionalTone"),
                relations = spec.array("relations").map { it.asString }.toSet(),
            )

            "RELATION" -> RelationMemory(
                id = id,
                importance = importance,
                status = status,
                recordedAt = recordedAt,
                source = MemorySource.CONVERSATION,
                provenance = provenance,
                characterScope = scope,
                name = spec["name"].asString,
                role = spec["role"].asString,
                note = spec.str("note"),
            )

            else -> fail("unsupported memory type '$type'")
        }
    }

    private fun query(spec: JsonObject): MemoryQuery = MemoryQuery(
        text = spec.str("text"),
        types = spec.optArray("types")?.map { MemoryType.valueOf(it.asString) }?.toSet()
            ?: MemoryType.entries.toSet(),
        characterScope = spec.str("characterScope")?.let(::CharacterScope),
        statuses = spec.optArray("statuses")?.map { MemoryStatus.valueOf(it.asString) }?.toSet()
            ?: setOf(MemoryStatus.CONFIRMED),
        from = spec.str("from")?.let(::instant),
        to = spec.str("to")?.let(::instant),
        limit = spec.int("limit") ?: MemoryQuery.DEFAULT_LIMIT,
    )

    // --- record -> contract snapshot ------------------------------------------------------------

    /**
     * The field set the contract compares, in the JSON's own camelCase keys.
     *
     * The envelope is always present; the content fields appear only when the record actually has
     * them, which is how the Python harness builds it too. Instants go through `Instant.toString()`,
     * which yields the same `2026-09-27T10:00:00Z` form the file uses.
     */
    private fun snapshot(memory: CanonicalMemory): Map<String, Any> {
        val result = linkedMapOf<String, Any>(
            "id" to memory.id.value,
            "type" to memory.type.name,
            "status" to memory.status.name,
            "recordedAt" to memory.recordedAt.toString(),
            "characterScope" to memory.characterScope.id,
            "excerpt" to memory.provenance.excerpt,
        )
        when (memory) {
            is ProfileMemory -> {
                result["attribute"] = memory.attribute
                result["value"] = memory.value
            }

            is EventMemory -> {
                result["title"] = memory.title
                memory.scheduledFor?.let { result["scheduledFor"] = it.toString() }
                memory.location?.let { result["location"] = it }
            }

            is EpisodeMemory -> {
                result["summary"] = memory.summary
                result["occurredAt"] = memory.occurredAt.toString()
                memory.emotionalTone?.let { result["emotionalTone"] = it }
                if (memory.relations.isNotEmpty()) result["relations"] = memory.relations.toList()
            }

            is RelationMemory -> {
                result["name"] = memory.name
                result["role"] = memory.role
                memory.note?.let { result["note"] = it }
            }
        }
        return result
    }

    private val RememberOutcome.outcomeName: String
        get() = when (this) {
            is RememberOutcome.Created -> "created"
            is RememberOutcome.Updated -> "updated"
            is RememberOutcome.Unchanged -> "unchanged"
        }

    private val EditOutcome.outcomeName: String
        get() = when (this) {
            is EditOutcome.Updated -> "updated"
            is EditOutcome.Unchanged -> "unchanged"
        }

    // --- loading and JSON helpers ---------------------------------------------------------------

    /**
     * Loaded through the classloader rather than a relative path so the test cannot depend on the
     * Gradle working directory. The directory is mounted as a test resource root in build.gradle.kts.
     */
    /**
     * One edit from its contract representation.
     *
     * The type is a discriminator in the contract rather than a nested object, so this is a mapping
     * rather than a parse. An unknown type fails loudly: a contract case that neither implementation
     * understands must not quietly do nothing.
     */
    private fun editOf(spec: JsonObject): MemoryEdit {
        val editedAt = instant(spec["editedAt"].asString)
        return when (val type = spec["type"].asString) {
            "PROFILE" -> ProfileEdit(
                editedAt = editedAt,
                attribute = spec["attribute"].asString,
                value = spec["value"].asString,
            )

            "EVENT" -> EventEdit(
                editedAt = editedAt,
                title = spec["title"].asString,
                scheduledFor = spec.str("scheduledFor")?.let(::instant),
                location = spec.str("location"),
            )

            "EPISODE" -> EpisodeEdit(
                editedAt = editedAt,
                summary = spec["summary"].asString,
                occurredAt = instant(spec["occurredAt"].asString),
                emotionalTone = spec.str("emotionalTone"),
                relations = spec.optArray("relations")?.map { it.asString }?.toSet() ?: emptySet(),
            )

            "RELATION" -> RelationEdit(
                editedAt = editedAt,
                name = spec["name"].asString,
                role = spec["role"].asString,
                note = spec.str("note"),
            )

            else -> fail("unknown edit type '$type'")
        }
    }

    /**
     * The audit envelope of a stored record.
     *
     * Separate from [snapshot] on purpose: an edit can get the content right and the audit information
     * wrong, and that would stay green if only content were compared.
     */
    private fun auditOf(memory: CanonicalMemory): Map<String, Any?> = mapOf(
        "id" to memory.id.value,
        "source" to memory.source.name,
        "importance" to memory.importance.name,
        "recordedAt" to memory.recordedAt.toString(),
        "sessionId" to memory.provenance.sessionId,
        "messageId" to memory.provenance.messageId,
        "excerpt" to memory.provenance.excerpt,
        "extractor" to memory.provenance.extractor,
    )

    private fun loadContract(): JsonObject {
        val stream = javaClass.getResourceAsStream(CONTRACT_RESOURCE)
            ?: fail(
                "$CONTRACT_RESOURCE not on the test classpath. It is mounted from " +
                    "evidence/contracts in :core-memory's build script; check that a fresh clone " +
                    "still has the file and that the test resource root is configured.",
            )
        return stream.bufferedReader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
    }

    private fun instant(text: String): Instant = Instant.parse(text)

    private fun JsonObject.obj(key: String): JsonObject = get(key).asJsonObject

    private fun JsonObject.array(key: String): List<JsonElement> =
        optArray(key) ?: emptyList()

    /** Absent and JSON `null` both come back as null, so callers can rely on Kotlin defaults. */
    private fun JsonObject.optArray(key: String): List<JsonElement>? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList()

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString

    private fun JsonObject.int(key: String): Int? =
        get(key)?.takeIf { !it.isJsonNull }?.asInt

    private companion object {
        const val CONTRACT_RESOURCE = "/canonical-memory-v2.json"
        const val DEFAULT_CHARACTER_SCOPE = "xiaozhi"
    }
}
