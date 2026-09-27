package com.aiwatch.memory.remote

import com.aiwatch.memory.CharacterScope
import com.aiwatch.memory.EditOutcome
import com.aiwatch.memory.MemoryEdit
import com.aiwatch.memory.MemoryGateway
import com.aiwatch.memory.MemoryId
import com.aiwatch.memory.MemoryIdentityConflictException
import com.aiwatch.memory.MemoryNotConfirmedException
import com.aiwatch.memory.MemoryNotEditableException
import com.aiwatch.memory.MemoryNotFoundException
import com.aiwatch.memory.MemoryNotStageableException
import com.aiwatch.memory.MemoryQuery
import com.aiwatch.memory.MemoryStatus
import com.aiwatch.memory.MemoryTransitionException
import com.aiwatch.memory.MemoryType
import com.aiwatch.memory.MemoryTypeMismatchException
import com.aiwatch.memory.RememberOutcome
import com.aiwatch.memory.CanonicalMemory
import com.aiwatch.memory.ScopedMemoryIdentity
import com.aiwatch.memory.MemoryIdentity
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Where the memory service is, and who this device is.
 *
 * The **token**, not the body, decides whose memories are reached. There is deliberately no
 * `subjectId` field: the server derives the subject from the authenticated identity, so a client cannot
 * ask for another subject's memory by editing a payload. The subject partition built on the server side
 * would otherwise be punctured at the first HTTP boundary.
 */
data class MemoryRemoteConfig(
    val baseUrl: String,
    val clientId: String,
    val deviceId: String,
    val token: String,
    val allowInsecureDevelopment: Boolean = false,
)

/** The HTTP surface version. Distinct from the canonical schema version, which is 2. */
object MemoryHttpV1 {
    const val BASE_PATH = "xiaozhi/memory/v1"
}

/**
 * [MemoryGateway] over the canonical memory HTTP surface.
 *
 * The request hygiene is adapted from `BootstrapRepository` rather than reinvented: HTTPS only,
 * redirects disabled, automatic retries disabled, a bounded body read, a call timeout, and cancellation
 * that actually cancels the in-flight call. Those properties are not decoration - a silently followed
 * redirect or an automatic retry on a `POST /confirm` is how one user's decision gets applied twice, or
 * applied somewhere other than where it was sent.
 *
 * Errors are restored to the canonical exception types. The UI already decides what to say by catching
 * `MemoryIdentityConflictException`; making it instead inspect an HTTP status or parse a message string
 * would put protocol detail into the screen and break the moment a proxy rewrote the response.
 */
class RemoteMemoryGateway(
    private val config: MemoryRemoteConfig,
    client: OkHttpClient = OkHttpClient(),
) : MemoryGateway {

    private val http: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val base: HttpUrl = try {
        val url = config.baseUrl.toHttpUrl()
        require(url.isHttps || config.allowInsecureDevelopment) {
            "memory endpoint must be https"
        }
        require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
        url
    } catch (invalid: IllegalArgumentException) {
        throw MemoryTransportException("invalid memory endpoint: ${invalid.message}", invalid)
    }

    // ---------------------------------------------------------------- MemoryGateway

    override suspend fun stage(candidates: List<CanonicalMemory>): List<CanonicalMemory> {
        val body = JsonObject().apply {
            add("records", JsonArray().apply { candidates.forEach { add(MemoryWire.encodeRecord(it)) } })
        }
        return records(execute(post(path("stage"), body)))
    }

    override suspend fun confirm(id: MemoryId): CanonicalMemory =
        MemoryWire.decodeRecord(record(execute(post(idPath(id, "confirm")))))

    override suspend fun reject(id: MemoryId): CanonicalMemory =
        MemoryWire.decodeRecord(record(execute(post(idPath(id, "reject")))))

    override suspend fun remember(memory: CanonicalMemory): RememberOutcome {
        val body = JsonObject().apply { add("record", MemoryWire.encodeRecord(memory)) }
        val response = execute(post(path("remember"), body))
        val outcome = response.string("outcome")
        val record = MemoryWire.decodeRecord(record(response))
        return when (outcome) {
            "created" -> RememberOutcome.Created(record)
            "updated" -> RememberOutcome.Updated(
                previous = MemoryWire.decodeRecord(previous(response)),
                memory = record,
            )

            "unchanged" -> RememberOutcome.Unchanged(record)
            else -> throw MemoryWireFormatException("unknown remember outcome '$outcome'")
        }
    }

    override suspend fun edit(id: MemoryId, edit: MemoryEdit): EditOutcome {
        val body = JsonObject().apply { add("edit", MemoryWire.encodeEdit(edit)) }
        val response = execute(patch(idPath(id), body))
        val outcome = response.string("outcome")
        val record = MemoryWire.decodeRecord(record(response))
        return when (outcome) {
            "updated" -> EditOutcome.Updated(
                previous = MemoryWire.decodeRecord(previous(response)),
                memory = record,
            )

            "unchanged" -> EditOutcome.Unchanged(record)
            else -> throw MemoryWireFormatException("unknown edit outcome '$outcome'")
        }
    }

    override suspend fun recall(query: MemoryQuery): List<CanonicalMemory> =
        records(execute(post(path("recall"), MemoryWire.encodeQuery(query))))

    override suspend fun list(
        statuses: Set<MemoryStatus>,
        characterScope: CharacterScope?,
    ): List<CanonicalMemory> {
        val url = url(path()).newBuilder()
            .addQueryParameter("statuses", statuses.joinToString(",") { it.name })
            .apply { characterScope?.let { addQueryParameter("characterScope", it.id) } }
            .build()
        return records(execute(get(url)))
    }

    override suspend fun forget(id: MemoryId): Boolean =
        execute(delete(idPath(id))).get("deleted")?.takeIf { !it.isJsonNull }?.asBoolean ?: false

    // ---------------------------------------------------------------- transport

    private fun path(vararg segments: String): String =
        (listOf(MemoryHttpV1.BASE_PATH) + segments).joinToString("/")

    private fun idPath(id: MemoryId, vararg suffix: String): String =
        path(id.value, *suffix)

    private fun url(path: String): HttpUrl =
        base.newBuilder().addPathSegments(path).build()

    private fun authorized(builder: Request.Builder): Request.Builder = builder
        .header("Device-Id", config.deviceId)
        .header("Client-Id", config.clientId)
        .header("Authorization", "Bearer ${config.token}")
        .header("Accept", "application/json")

    private fun post(path: String, body: JsonObject? = null): Request =
        authorized(Request.Builder().url(url(path)))
            .post(body.orEmpty().toString().toRequestBody(JSON))
            .build()

    private fun patch(path: String, body: JsonObject): Request =
        authorized(Request.Builder().url(url(path)))
            .patch(body.toString().toRequestBody(JSON))
            .build()

    private fun delete(path: String): Request =
        authorized(Request.Builder().url(url(path))).delete().build()

    private fun get(url: HttpUrl): Request = authorized(Request.Builder().url(url)).get().build()

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject()

    /** One bounded request. Every failure path is either a canonical exception or a transport one. */
    private suspend fun execute(request: Request): JsonObject =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(MemoryTransportException(e.message ?: "network failure", e)))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            val text = readBounded(it)
                            if (it.isSuccessful) parse(text)
                            else Result.failure(translate(it.code, text))
                        }
                    } catch (wire: MemoryTransportException) {
                        Result.failure(wire)
                    } catch (wire: MemoryWireFormatException) {
                        Result.failure(wire)
                    } catch (io: IOException) {
                        Result.failure(MemoryTransportException(io.message ?: "network failure", io))
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }

    /** Bounded like the bootstrap read; a memory service cannot be allowed to stream without limit. */
    private fun readBounded(response: Response): String {
        val source = response.body?.source() ?: return ""
        val buffer = okio.Buffer()
        while (buffer.size <= MAX_BODY_BYTES) {
            if (source.read(buffer, MAX_BODY_BYTES + 1 - buffer.size) == -1L) break
        }
        if (buffer.size > MAX_BODY_BYTES) {
            throw MemoryTransportException("memory response exceeded $MAX_BODY_BYTES bytes")
        }
        return buffer.readUtf8()
    }

    private fun parse(text: String): Result<JsonObject> =
        try {
            Result.success(JsonParser.parseString(text).asJsonObject)
        } catch (_: Exception) {
            Result.failure(MemoryWireFormatException("response is not a JSON object"))
        }

    /**
     * Maps the canonical error code back to the exception the rest of the product already catches.
     *
     * An unrecognised code is a transport failure rather than a guess: treating an unknown rejection as
     * success, or as "not found", would let a server-side refusal be read as a decision.
     */
    private fun translate(status: Int, text: String): Exception {
        val body = try {
            JsonParser.parseString(text).asJsonObject
        } catch (_: Exception) {
            null
        }
        val code = body?.let { it.get("code")?.takeIf { c -> !c.isJsonNull }?.asString }
            ?: return when (status) {
                401, 403 -> MemoryTransportException("memory request was not authorised (HTTP $status)")
                else -> MemoryTransportException("memory request failed (HTTP $status)")
            }
        val message = body.get("message")?.takeIf { !it.isJsonNull }?.asString ?: code
        fun id(key: String, fallback: MemoryId) = body.get(key)?.takeIf { !it.isJsonNull }?.asString
            ?.let(::MemoryId) ?: fallback
        fun status(key: String, fallback: MemoryStatus) = body.get(key)?.takeIf { !it.isJsonNull }
            ?.asString?.let { runCatching { MemoryStatus.valueOf(it) }.getOrNull() } ?: fallback
        fun type(key: String, fallback: MemoryType) = body.get(key)?.takeIf { !it.isJsonNull }
            ?.asString?.let { runCatching { MemoryType.valueOf(it) }.getOrNull() } ?: fallback

        val placeholder = MemoryId("")
        return when (code) {
            "MEMORY_NOT_FOUND" -> MemoryNotFoundException(id("id", placeholder))
            "MEMORY_NOT_CONFIRMED" ->
                MemoryNotConfirmedException(id("id", placeholder), status("status", MemoryStatus.STAGED))

            "MEMORY_NOT_STAGEABLE" ->
                MemoryNotStageableException(id("id", placeholder), status("status", MemoryStatus.CONFIRMED))

            "INVALID_TRANSITION" -> MemoryTransitionException(
                id("id", placeholder),
                status("fromStatus", MemoryStatus.STAGED),
                status("toStatus", MemoryStatus.CONFIRMED),
            )

            "MEMORY_NOT_EDITABLE" ->
                MemoryNotEditableException(id("id", placeholder), status("status", MemoryStatus.REJECTED))

            "MEMORY_TYPE_MISMATCH" -> MemoryTypeMismatchException(
                id("id", placeholder),
                type("expectedType", MemoryType.PROFILE),
                type("actualType", MemoryType.PROFILE),
            )

            "MEMORY_IDENTITY_CONFLICT" -> MemoryIdentityConflictException(
                id("id", placeholder),
                id("conflictingId", placeholder),
                ScopedMemoryIdentity(
                    CharacterScope(body.get("characterScope")?.takeIf { !it.isJsonNull }?.asString ?: ""),
                    MemoryIdentity(MemoryType.PROFILE, body.get("identityKey")?.asString ?: ""),
                ),
            )

            else -> MemoryTransportException("unrecognised memory error code '$code': $message")
        }
    }

    private fun records(response: JsonObject): List<CanonicalMemory> =
        response.getAsJsonArray("records")?.map { MemoryWire.decodeRecord(it.asJsonObject) }
            ?: throw MemoryWireFormatException("response has no 'records'")

    private fun record(response: JsonObject): JsonObject =
        response.getAsJsonObject("record")
            ?: throw MemoryWireFormatException("response has no 'record'")

    private fun previous(response: JsonObject): JsonObject =
        response.getAsJsonObject("previous")
            ?: throw MemoryWireFormatException("'updated' response has no 'previous'")

    private fun JsonObject.string(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString
            ?: throw MemoryWireFormatException("response has no '$key'")

    private companion object {
        const val MAX_BODY_BYTES = 1_048_576L
        val JSON: okhttp3.MediaType = "application/json; charset=utf-8".toMediaType()
    }
}

/** A transport-level failure: the memory service could not be reached or did not answer usably. */
class MemoryTransportException(message: String, cause: Throwable? = null) : IOException(message, cause)
