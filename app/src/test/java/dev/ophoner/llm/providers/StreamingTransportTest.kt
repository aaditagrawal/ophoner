package dev.ophoner.llm.providers

import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.model.ProviderType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import okio.Buffer
import dev.ophoner.llm.LlmErrorKind
import dev.ophoner.llm.LlmResponseChunk
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class StreamingTransportTest {
    private val request = Request.Builder().url("https://example.invalid/completions").build()
    private fun client(body: String, code: Int = 200, retryAfter: String = "2") = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                .message("test").header("Retry-After", retryAfter).body(body.toResponseBody()).build()
        }.build()

    @Test fun `slow consumer receives every delta in order`() = runBlocking {
        val body = buildString {
            repeat(512) { append("data:{\"choices\":[{\"delta\":{\"content\":\"$it\"}}]}\n\n") }
            append("data:{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata:[DONE]\n\n")
        }
        val chunks = mutableListOf<LlmResponseChunk>()
        streamResponse(client(body), { request }, ::parseChatStream).collect {
            delay(1)
            chunks.add(it)
        }
        assertEquals((0 until 512).map(Int::toString), chunks.filterIsInstance<LlmResponseChunk.TextDelta>().map { it.text })
        assertEquals(LlmResponseChunk.Done(), chunks.last())
    }

    @Test fun `HTTP failure preserves retry hints without exposing body`() = runBlocking {
        val chunks = streamResponse(client("secret bearer value", 429), { request }, ::parseChatStream).toList()
        val error = chunks.single() as LlmResponseChunk.Error
        assertTrue(error.retryable)
        assertEquals(LlmErrorKind.HTTP, error.kind)
        assertEquals(2000L, error.retryAfterMillis)
        assertFalse(error.message.contains("secret"))
    }

    @Test fun `context overflow is typed and not a transient retry`() = runBlocking {
        val chunks = streamResponse(client("{\"code\":\"context_length_exceeded\"}", 400), { request }, ::parseChatStream).toList()
        val error = chunks.single() as LlmResponseChunk.Error
        assertEquals(LlmErrorKind.CONTEXT_LIMIT, error.kind)
        assertFalse(error.retryable)
    }

    @Test fun `unexpected EOF returns error without done`() = runBlocking {
        val chunks = streamResponse(client(""), { request }, ::parseChatStream).toList()
        val error = chunks.single() as LlmResponseChunk.Error
        assertEquals(LlmErrorKind.NETWORK, error.kind)
        assertTrue(error.retryable)
    }
    @Test fun `chat token caps use provider compatible fields`() = runBlocking {
        for (providerType in listOf(ProviderType.OPENAI, ProviderType.OPENROUTER, ProviderType.GEMINI, ProviderType.CUSTOM_OPENAI)) {
            val limitField = if (providerType == ProviderType.OPENAI) "max_completion_tokens" else "max_tokens"
            val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
                val buffer = Buffer()
                chain.request().body!!.writeTo(buffer)
                val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                assertEquals(1234, body.getValue(limitField).jsonPrimitive.int)
                assertFalse(body.containsKey(if (limitField == "max_tokens") "max_completion_tokens" else "max_tokens"))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                    .body("data:{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata:[DONE]\n\n".toResponseBody()).build()
            }.build()
            val config = ProviderConfig("test", "test", baseUrl = "https://example.invalid/v1", modelId = "test", providerType = providerType, maxOutputTokens = 1234)
            val chunks = OpenAiCompatibleProvider(httpClient).streamCompletion(emptyList(), emptyList(), config).toList()
            assertEquals(LlmResponseChunk.Done(), chunks.last())
        }
    }

    @Test fun `cancelling a stalled stream closes its socket promptly`() = runBlocking {
        ServerSocket(0).use { server ->
            val headersSent = CountDownLatch(1)
            val disconnected = CountDownLatch(1)
            val serverThread = thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* consume request headers */ }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                        flush()
                    }
                    headersSent.countDown()
                    if (reader.read() == -1) disconnected.countDown()
                }
            }
            val localRequest = Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()
            val collecting = launch(Dispatchers.IO) {
                streamResponse(OkHttpClient(), { localRequest }, ::parseChatStream).collect {}
            }
            try {
                assertTrue(headersSent.await(3, TimeUnit.SECONDS))
                withTimeout(3000) { collecting.cancelAndJoin() }
                assertTrue(disconnected.await(3, TimeUnit.SECONDS))
            } finally {
                collecting.cancel()
                serverThread.join(6000)
            }
        }
    }

}
