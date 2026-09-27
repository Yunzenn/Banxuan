package com.aiwatch.memory.remote

import com.aiwatch.memory.CanonicalMemory
import com.aiwatch.memory.MemoryEdit
import com.aiwatch.memory.MemoryId
import com.aiwatch.memory.MemoryIdentityConflictException
import com.aiwatch.memory.MemoryNotEditableException
import com.aiwatch.memory.MemoryNotConfirmedException
import com.aiwatch.memory.MemoryNotFoundException
import com.aiwatch.memory.MemoryNotStageableException
import com.aiwatch.memory.MemoryQuery
import com.aiwatch.memory.MemoryStatus
import com.aiwatch.memory.MemoryTransitionException
import com.aiwatch.memory.MemoryTypeMismatchException
import com.aiwatch.memory.RememberOutcome
import com.aiwatch.memory.ProfileMemory
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * The frozen HTTP data boundary, checked on the client side.
 *
 * Two kinds of evidence, deliberately separated:
 *
 * * **golden payloads** from `evidence/contracts/memory-http-v1-golden.json`, which the Python handler
 *   reads too. Round-tripping them pins the wire format itself, so "the two sides agree" is verified
 *   rather than assumed.
 * * **the real client against MockWebServer**, which covers what a fixture cannot: that the gateway
 *   sends the right method, path, headers and body, and that a served error comes back as the typed
 *   exception the rest of the product already catches.
 */
class RemoteMemoryGatewayTest {

    private val golden: JsonObject = loadGolden()

    // ---------------------------------------------------------------- golden payloads

    @Test
    fun theGoldenContractDeclaresBothItsOwnVersionAndTheSchemaItCarries() {
        assertEquals("memory-http", golden["contract"].asString)
        assertEquals(1, golden["version"].asInt)
        assertEquals(2, golden["canonicalSchema"].asInt)
    }

    @Test
    fun everyGoldenRecordSurvivesADecodeEncodeRoundTrip() {
        val records = golden.getAsJsonArray("records")
        assertTrue(records.size() >= 7, "expected a record per type and per edge case")
        records.forEach { element ->
            val case = element.asJsonObject
            val json = case.getAsJsonObject("json")
            val decoded: CanonicalMemory = MemoryWire.decodeRecord(json)
            assertEquals(
                json,
                MemoryWire.encodeRecord(decoded),
                "${case["name"].asString}: the wire form is not stable across a round trip",
            )
        }
    }

    @Test
    fun everyGoldenEditAndQuerySurvivesARoundTrip() {
        golden.getAsJsonArray("edits").forEach { element ->
            val case = element.asJsonObject
            val json = case.getAsJsonObject("json")
            val decoded: MemoryEdit = MemoryWire.decodeEdit(json)
            assertEquals(json, MemoryWire.encodeEdit(decoded), case["name"].asString)
        }
        golden.getAsJsonArray("queries").forEach { element ->
            val case = element.asJsonObject
            val json = case.getAsJsonObject("json")
            val decoded: MemoryQuery = MemoryWire.decodeQuery(json)
            assertEquals(json, MemoryWire.encodeQuery(decoded), case["name"].asString)
        }
    }

    @Test
    fun instantsAreNormalisedToCanonicalUtc() {
        golden.getAsJsonArray("normalization").forEach { element ->
            val case = element.asJsonObject
            val decoded = MemoryWire.decodeRecord(
                JsonParser.parseString(
                    """
                    {"id":"m1","type":"PROFILE","status":"CONFIRMED","recordedAt":"${case["input"].asString}",
                     "characterScope":"xiaozhi","importance":"NORMAL","source":"CONVERSATION",
                     "provenance":{"sessionId":null,"messageId":null,"excerpt":"","extractor":"x"},
                     "attribute":"a","value":"b"}
                    """.trimIndent(),
                ).asJsonObject,
            )
            assertEquals(
                case["output"].asString,
                MemoryWire.encodeRecord(decoded)["recordedAt"].asString,
                case["name"].asString,
            )
        }
    }

    @Test
    fun aPayloadThatDoesNotMatchTheContractFailsClosed() {
        // A memory the client cannot parse is one it must not act on; substituting a default would
        // fabricate a fact.
        assertFailsWith<MemoryWireFormatException> {
            MemoryWire.decodeRecord(JsonParser.parseString("""{"id":"m1","type":"NONSENSE"}""").asJsonObject)
        }
        assertFailsWith<MemoryWireFormatException> {
            MemoryWire.decodeRecord(
                JsonParser.parseString("""{"id":"m1","type":"PROFILE","status":"MAYBE"}""").asJsonObject,
            )
        }
        assertFailsWith<MemoryWireFormatException> {
            MemoryWire.decodeRecord(
                JsonParser.parseString(
                    """{"id":"m1","type":"PROFILE","status":"CONFIRMED","recordedAt":"not-a-time"}""",
                ).asJsonObject,
            )
        }
    }

    // ---------------------------------------------------------------- the client over HTTP

    @Test
    fun recallSendsTheFrozenRequestAndRestoresTheCanonicalRecords() = withServer { server, gateway ->
        val profileJson = goldenRecord("profile_with_full_provenance")
        server.enqueue(jsonResponse(200, """{"records":[$profileJson]}"""))

        val records = runBlocking { gateway.recall(MemoryQuery(characterScope = com.aiwatch.memory.CharacterScope("xiaozhi"))) }

        assertEquals(1, records.size)
        val restored = assertIs<ProfileMemory>(records.single())
        assertEquals("food.dislike", restored.attribute)
        assertEquals("香菜", restored.value)
        assertEquals(Instant.parse("2026-09-27T10:00:00Z"), restored.recordedAt)

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("/xiaozhi/memory/v1/recall", recorded.path)
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
        assertEquals("device-1", recorded.getHeader("Device-Id"))
        assertEquals("client-1", recorded.getHeader("Client-Id"))
        val body = JsonParser.parseString(recorded.body.readUtf8()).asJsonObject
        assertEquals(listOf("CONFIRMED"), body.getAsJsonArray("statuses").map { it.asString })
        // The subject is decided by the token, never by the payload: a client able to name a subject
        // could read another one's memory.
        assertTrue(body.get("subjectId") == null, "the client must not send a subject")
        assertTrue(body.get("subject_id") == null, "the client must not send a subject")
    }

    @Test
    fun listSendsStatusesAsAQueryAndNoSubject() = withServer { server, gateway ->
        server.enqueue(jsonResponse(200, """{"records":[]}"""))

        runBlocking { gateway.list(statuses = setOf(MemoryStatus.STAGED, MemoryStatus.CONFIRMED)) }

        val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", recorded.method)
        assertEquals("/xiaozhi/memory/v1?statuses=STAGED%2CCONFIRMED", recorded.path)
    }

    @Test
    fun confirmRejectRememberForgetAndEditAllReachTheirEndpoints() = withServer { server, gateway ->
        val profileJson = goldenRecord("profile_with_full_provenance")
        server.enqueue(jsonResponse(200, """{"record":$profileJson}"""))
        assertEquals("m1", runBlocking { gateway.confirm(MemoryId("m1")) }.id.value)
        assertEquals("/xiaozhi/memory/v1/m1/confirm", server.takeRequest(2, TimeUnit.SECONDS)!!.path)

        server.enqueue(jsonResponse(200, """{"record":$profileJson}"""))
        runBlocking { gateway.reject(MemoryId("m1")) }
        assertEquals("/xiaozhi/memory/v1/m1/reject", server.takeRequest(2, TimeUnit.SECONDS)!!.path)

        server.enqueue(
            jsonResponse(200, """{"outcome":"created","record":$profileJson}"""),
        )
        val created = runBlocking {
            gateway.remember(MemoryWire.decodeRecord(JsonParser.parseString(profileJson).asJsonObject))
        }
        assertIs<RememberOutcome.Created>(created)
        val rememberRequest = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/xiaozhi/memory/v1/remember", rememberRequest.path)

        server.enqueue(jsonResponse(200, """{"outcome":"updated","previous":$profileJson,"record":$profileJson}"""))
        val edit = MemoryWire.decodeEdit(goldenEdit("profile_edit"))
        val outcome = runBlocking { gateway.edit(MemoryId("m1"), edit) }
        assertIs<com.aiwatch.memory.EditOutcome.Updated>(outcome)
        val editRequest = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("PATCH", editRequest.method)
        assertEquals("/xiaozhi/memory/v1/m1", editRequest.path)

        server.enqueue(jsonResponse(200, """{"deleted":true}"""))
        assertTrue(runBlocking { gateway.forget(MemoryId("m1")) })
        assertEquals("DELETE", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
    }

    @Test
    fun aStagedCandidateIsRequestedAsStagedAndNeverBecomesConfirmedInTransit() = withServer { server, gateway ->
        val stagedJson = goldenRecord("event_without_a_resolved_time")
        server.enqueue(jsonResponse(200, """{"records":[$stagedJson]}"""))

        val records = runBlocking { gateway.recall() }

        assertEquals(MemoryStatus.STAGED, records.single().status)
        val body = JsonParser.parseString(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).asJsonObject
        // The client asks for CONFIRMED by default and the server is what answers; nothing here can
        // promote a candidate.
        assertEquals(listOf("CONFIRMED"), body.getAsJsonArray("statuses").map { it.asString })
    }

    // ---------------------------------------------------------------- errors round-trip as types

    @Test
    fun everyGoldenErrorIsRestoredToTheTypedExceptionTheProductCatches() = withServer { server, gateway ->
        golden.getAsJsonArray("errors").forEach { element ->
            val case = element.asJsonObject
            val status = case["status"].asInt
            val body = case.getAsJsonObject("body").toString()
            server.enqueue(jsonResponse(status, body))

            val expected = case["expectException"].asString
            val thrown = assertFailsWith<Exception>(case["name"].asString) {
                runBlocking { gateway.list() }
            }
            assertEquals(expected, thrown::class.simpleName, "${case["name"].asString}: wrong exception type")
            server.takeRequest(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun anIdentityConflictArrivesAsAnIdentityConflictAndNotAsAGuess() = withServer { server, gateway ->
        server.enqueue(
            jsonResponse(
                409,
                """{"code":"MEMORY_IDENTITY_CONFLICT","message":"m1 would become m2","id":"m1","conflictingId":"m2","characterScope":"xiaozhi","identityKey":"food.like"}""",
            ),
        )

        val thrown = assertFailsWith<MemoryIdentityConflictException> {
            runBlocking {
                gateway.edit(MemoryId("m1"), MemoryWire.decodeEdit(goldenEdit("profile_edit")))
            }
        }
        assertEquals("m1", thrown.id.value)
        assertEquals("m2", thrown.conflictingId.value)
    }

    @Test
    fun theNamedCanonicalErrorsMapToTheirOwnTypes() {
        // Guards against the mapping silently collapsing every code into one exception, which is what
        // would make the UI's conflict message reachable only by accident.
        assertEquals(MemoryNotFoundException::class.simpleName, exceptionNameFor(404, "MEMORY_NOT_FOUND"))
        assertEquals(MemoryNotConfirmedException::class.simpleName, exceptionNameFor(400, "MEMORY_NOT_CONFIRMED"))
        assertEquals(MemoryNotStageableException::class.simpleName, exceptionNameFor(400, "MEMORY_NOT_STAGEABLE"))
        assertEquals(MemoryTransitionException::class.simpleName, exceptionNameFor(409, "INVALID_TRANSITION"))
        assertEquals(MemoryNotEditableException::class.simpleName, exceptionNameFor(409, "MEMORY_NOT_EDITABLE"))
        assertEquals(MemoryTypeMismatchException::class.simpleName, exceptionNameFor(400, "MEMORY_TYPE_MISMATCH"))
        assertEquals(
            MemoryIdentityConflictException::class.simpleName,
            exceptionNameFor(409, "MEMORY_IDENTITY_CONFLICT"),
        )
        assertEquals(
            MemoryTransportException::class.simpleName,
            exceptionNameFor(500, "SOMETHING_THIS_CLIENT_DOES_NOT_KNOW"),
        )
    }

    // ---------------------------------------------------------------- transport hygiene

    @Test
    fun aPlainHttpEndpointIsRefused() {
        // Built directly rather than through config(), which enables the development escape hatch for
        // MockWebServer's http listener. Testing the guard with the guard disabled proves nothing.
        val thrown = assertFailsWith<MemoryTransportException> {
            RemoteMemoryGateway(
                MemoryRemoteConfig(
                    baseUrl = "http://memory.test/",
                    clientId = "client-1",
                    deviceId = "device-1",
                    token = "test-token",
                ),
            )
        }
        assertTrue(thrown.message!!.contains("https"), thrown.message!!)
    }

    @Test
    fun redirectsAreNotFollowed() = withServer { server, gateway ->
        // A memory decision must be applied where it was sent. Following a redirect would silently
        // re-issue a POST somewhere else.
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://elsewhere.test/"))
        server.enqueue(jsonResponse(200, """{"records":[]}"""))

        assertFailsWith<MemoryTransportException> { runBlocking { gateway.list() } }
        server.takeRequest(2, TimeUnit.SECONDS)
        assertEquals(0, server.requestCount - 1, "a redirect was followed")
    }

    private fun exceptionNameFor(status: Int, code: String): String? {
        var name: String? = null
        withServer { server, gateway ->
            server.enqueue(jsonResponse(status, """{"code":"$code","message":"m"}"""))
            name = assertFailsWith<Exception> { runBlocking { gateway.list() } }::class.simpleName
        }
        return name
    }

    // ---------------------------------------------------------------- harness

    private fun config(baseUrl: String) = MemoryRemoteConfig(
        baseUrl = baseUrl,
        clientId = "client-1",
        deviceId = "device-1",
        token = "test-token",
        allowInsecureDevelopment = true,
    )

    private fun withServer(block: (MockWebServer, RemoteMemoryGateway) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            block(server, RemoteMemoryGateway(config(server.url("/").toString())))
        } finally {
            server.shutdown()
        }
    }

    private fun jsonResponse(status: Int, body: String): MockResponse =
        MockResponse().setResponseCode(status)
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .setBody(body)

    private fun goldenRecord(name: String): String = goldenArray("records", name)

    private fun goldenEdit(name: String): JsonObject =
        JsonParser.parseString(goldenArray("edits", name)).asJsonObject

    private fun goldenArray(array: String, name: String): String =
        golden.getAsJsonArray(array).map { it.asJsonObject }
            .first { it["name"].asString == name }
            .getAsJsonObject("json")
            .toString()

    private fun loadGolden(): JsonObject {
        val stream = javaClass.getResourceAsStream("/memory-http-v1-golden.json")
            ?: error("the golden payloads are not on the test classpath")
        return JsonParser.parseString(stream.reader().readText()).asJsonObject
    }
}
