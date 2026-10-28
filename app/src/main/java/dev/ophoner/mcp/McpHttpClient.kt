package dev.ophoner.mcp

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** POST-only Streamable HTTP, MCP 2025-03-26. No replay or automatic redirects. */
internal class McpHttpClient(private val url: String, client: OkHttpClient, private val json: Json,
    private val accessToken: suspend () -> String? = { null },
    private val unauthorized: suspend () -> Unit = {},
) : McpProtocolClient() {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        // OkHttp may follow a 503 with Retry-After: 0 even with connection retries disabled.
        // Prevent that automatic POST replay, including tools that mutate remote state.
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 503) response.newBuilder().header("Retry-After", "60").build() else response
        }
        .callTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).build()
    private val ids = AtomicLong()
    @Volatile private var session: String? = null

    override suspend fun rpc(method: String, params: JsonObject?, notification: Boolean): JsonObject {
        val id = if (notification) null else ids.incrementAndGet()
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            put("method", method)
            params?.let { put("params", it) }
        }
        require(payload.toString().length <= MAX_BODY_BYTES) { "MCP request exceeds size limit." }
        val token = accessToken()
        val request = Request.Builder().url(url)
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", PROTOCOL)
            .apply { session?.let { header("Mcp-Session-Id", it) } }
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        try { return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (it.code == 404 && session != null) { session = null; resetSession() }
                            if (it.code == 401) throw McpAuthorizationRequired(it.header("WWW-Authenticate"))
                            if (!it.isSuccessful) throw IOException("MCP $method failed with HTTP ${it.code}. No tool call was retried.")
                            if (method == "initialize") {
                                session = it.header("Mcp-Session-Id")?.also { value ->
                                    require(value.length <= 1024 && value.all { c -> c.code in 0x21..0x7e }) { "Invalid MCP session ID." }
                                }
                            }
                            if (notification) {
                                if (it.code != 202 && it.code != 204) throw IOException("MCP notification was not accepted.")
                                return@use JsonObject(emptyMap())
                            }
                            val body = it.body ?: throw IOException("MCP returned an empty body.")
                            when (body.contentType()?.let { type -> "${type.type}/${type.subtype}" }) {
                                "text/event-stream" -> readSse(body, id!!)
                                "application/json" -> {
                                    val source = body.source()
                                    if (source.request(MAX_BODY_BYTES + 1)) throw IOException("MCP response exceeds size limit.")
                                    matchingResult(json.parseToJsonElement(source.readUtf8()), id!!)
                                        ?: throw IOException("MCP response ID did not match the request.")
                                }
                                else -> throw IOException("Unsupported MCP response content type.")
                            }
                        }
                        continuation.resume(result)
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }
            })
        } } catch (e: McpAuthorizationRequired) {
            unauthorized()
            throw e
        }
    }

    private fun readSse(body: ResponseBody, id: Long): JsonObject {
        val source = body.source()
        var consumed = 0L
        val data = StringBuilder()
        while (!source.exhausted()) {
            val line = source.readUtf8LineStrict(MAX_BODY_BYTES - consumed)
            consumed += line.toByteArray().size + 1
            if (consumed > MAX_BODY_BYTES) throw IOException("MCP SSE response exceeds size limit.")
            if (line.isEmpty()) {
                if (data.isNotEmpty()) {
                    matchingResult(json.parseToJsonElement(data.toString()), id)?.let { return it }
                    data.setLength(0)
                }
            } else if (line.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
            }
        }
        throw IOException("MCP stream ended without a matching response. Do not repeat a mutation without checking its outcome.")
    }

    private fun matchingResult(value: JsonElement, id: Long): JsonObject? {
        val messages = if (value is JsonArray) value else JsonArray(listOf(value))
        for (element in messages) {
            val message = element as? JsonObject ?: continue
            if (message["id"] != JsonPrimitive(id)) continue
            if (message.string("jsonrpc") != "2.0") throw IOException("Invalid MCP JSON-RPC version.")
            if (message["method"] != null) throw IOException("MCP server requested an unsupported client operation.")
            if (message["error"] != null) throw IOException("MCP returned a JSON-RPC error: ${message["error"].toString().take(1024)}")
            return message["result"] as? JsonObject ?: throw IOException("MCP response has no result.")
        }
        return null
    }

    companion object {
        const val PROTOCOL = "2025-03-26"
        const val MAX_TOOLS = 64
        const val MAX_SCHEMA_CHARS = 16_384
        private const val MAX_BODY_BYTES = 1_048_576L
    }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
