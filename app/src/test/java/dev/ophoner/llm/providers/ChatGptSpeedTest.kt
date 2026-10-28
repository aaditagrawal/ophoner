package dev.ophoner.llm.providers

import dev.ophoner.data.model.ChatGptSpeed
import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.model.ProviderType
import dev.ophoner.llm.LlmContentBlock
import dev.ophoner.llm.LlmMessage
import dev.ophoner.llm.LlmResponseChunk
import dev.ophoner.llm.LlmRole
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ChatGptSpeedTest {
    private val config = ProviderConfig("chatgpt", "ChatGPT", "test-token",
        "https://chatgpt.com/backend-api/codex", "gpt-6-astra", ProviderType.CODEX_CHATGPT,
        accountId = "test-account", useLatestModel = true)

    @Test fun `old connections default to Standard and Fast survives serialization`() {
        val old = Json.encodeToString(config)
        assertFalse(old.contains("chatGptSpeed"))
        assertEquals(ChatGptSpeed.STANDARD, Json.decodeFromString<ProviderConfig>(old).chatGptSpeed)
        val fast = config.copy(chatGptSpeed = ChatGptSpeed.FAST)
        assertEquals(fast, Json.decodeFromString<ProviderConfig>(Json.encodeToString(fast)))
    }

    @Test fun `ChatGPT sends the chosen service tier with the same account and model`() = runBlocking {
        for (speed in ChatGptSpeed.entries) {
            val client = client { request ->
                assertEquals("Bearer test-token", request.header("Authorization"))
                assertEquals("test-account", request.header("ChatGPT-Account-ID"))
                val body = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
                assertEquals(if (speed == ChatGptSpeed.FAST) "fast" else "default", body["service_tier"]?.jsonPrimitive?.content)
                assertEquals("gpt-6-astra", body["model"]?.jsonPrimitive?.content)
            }
            val chunks = CodexChatGptProvider(client).streamCompletion(
                listOf(LlmMessage(LlmRole.USER, listOf(LlmContentBlock.Text("hello")))),
                emptyList(), config.copy(chatGptSpeed = speed),
            ).toList()
            assertTrue(chunks.last() is LlmResponseChunk.Done)
        }
    }

    @Test fun `subscription speed does not leak into other Responses providers`() = runBlocking {
        val client = client { request ->
            val body = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            assertFalse(body.containsKey("service_tier"))
            assertNull(request.header("ChatGPT-Account-ID"))
        }
        val chunks = CodexChatGptProvider(client).streamCompletion(emptyList(), emptyList(),
            config.copy(providerType = ProviderType.OPENCODE_GO, chatGptSpeed = ChatGptSpeed.FAST)).toList()
        assertTrue(chunks.last() is LlmResponseChunk.Done)
    }

    private fun client(inspect: (okhttp3.Request) -> Unit) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        inspect(request)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n".toResponseBody()).build()
    }.build()
}
