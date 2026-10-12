package dev.ophoner.llm.providers

import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.model.ProviderType
import dev.ophoner.llm.*
import dev.ophoner.tools.Tool
import dev.ophoner.tools.ToolExecutionContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class PromptCachingTest {
    private fun config(type: ProviderType) = ProviderConfig("test", "test", baseUrl = "https://example.invalid/v1", modelId = "test", providerType = type)
    private fun capture(assertBody: (JsonObject) -> Unit) = OkHttpClient.Builder().addInterceptor { chain ->
        val buffer = Buffer()
        chain.request().body!!.writeTo(buffer)
        assertBody(Json.parseToJsonElement(buffer.readUtf8()).jsonObject)
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(400).message("test").body("".toResponseBody()).build()
    }.build()

    @Test fun `cache routing key and usage options are scoped to supported providers`() = runBlocking {
        for (type in listOf(ProviderType.OPENAI, ProviderType.OPENROUTER, ProviderType.GEMINI, ProviderType.CUSTOM_OPENAI)) {
            val client = capture { body ->
                assertEquals(type == ProviderType.OPENAI, body.containsKey("prompt_cache_key"))
                if (type == ProviderType.OPENAI) assertEquals("conversation-123", body.getValue("prompt_cache_key").jsonPrimitive.content)
                assertEquals(type in setOf(ProviderType.OPENAI, ProviderType.OPENROUTER), body.containsKey("stream_options"))
            }
            withContext(ToolExecutionContext(cacheKey = "conversation-123")) {
                OpenAiCompatibleProvider(client).streamCompletion(emptyList(), emptyList(), config(type)).toList()
            }
        }
    }

    @Test fun `Claude caches system tools and moving conversation without modifying custom gateways`() = runBlocking {
        for (type in listOf(ProviderType.CLAUDE, ProviderType.CUSTOM_ANTHROPIC)) {
            val client = capture { body ->
                val enabled = type == ProviderType.CLAUDE
                assertEquals(enabled, body.containsKey("cache_control"))
                val tools = body.getValue("tools").jsonArray
                assertFalse(tools.first().jsonObject.containsKey("cache_control"))
                assertEquals(enabled, tools.last().jsonObject.containsKey("cache_control"))
                if (enabled) {
                    val system = body.getValue("system").jsonArray.single().jsonObject
                    assertEquals("Stable rules\nMore rules", system.getValue("text").jsonPrimitive.content)
                    assertEquals("ephemeral", system.getValue("cache_control").jsonObject.getValue("type").jsonPrimitive.content)
                } else assertEquals("Stable rules\nMore rules", body.getValue("system").jsonPrimitive.content)
            }
            val messages = listOf(
                LlmMessage(LlmRole.SYSTEM, listOf(LlmContentBlock.Text("Stable rules"))),
                LlmMessage(LlmRole.SYSTEM, listOf(LlmContentBlock.Text("More rules"))),
                LlmMessage(LlmRole.USER, listOf(LlmContentBlock.Text("hello"))),
            )
            ClaudeProvider(client).streamCompletion(messages, listOf(Tool("a", "a", JsonObject(emptyMap())), Tool("b", "b", JsonObject(emptyMap()))), config(type)).toList()
        }
    }

    @Test fun `explicit caching hints can be disabled`(): Unit = runBlocking {
        val client = capture { body ->
            assertFalse(body.containsKey("cache_control"))
            assertFalse(body.containsKey("prompt_cache_key"))
            body["tools"]?.jsonArray?.forEach { assertFalse(it.jsonObject.containsKey("cache_control")) }
        }
        withContext(ToolExecutionContext(cacheKey = "conversation-123")) {
            OpenAiCompatibleProvider(client).streamCompletion(emptyList(), emptyList(), config(ProviderType.OPENAI).copy(promptCaching = false)).toList()
            ClaudeProvider(client).streamCompletion(emptyList(), emptyList(), config(ProviderType.CLAUDE).copy(promptCaching = false)).toList()
        }
    }

    @Test fun `cache usage reports normalized input totals`() = runBlocking {
        val claude = mutableListOf<LlmResponseChunk>()
        parseClaudeStream((
            "data:{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10,\"cache_read_input_tokens\":100,\"cache_creation_input_tokens\":20}}}\n\n" +
                "data:{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":5}}\n\n" +
                "data:{\"type\":\"message_stop\"}\n\n"
            ).reader().buffered()) { claude.add(it) }
        assertEquals(LlmResponseChunk.Done("end_turn", 130, 5, 100, 20), claude.single())
        val chat = mutableListOf<LlmResponseChunk>()
        parseChatStream((
            "data:{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                "data:{\"choices\":[],\"usage\":{\"prompt_tokens\":130,\"completion_tokens\":5,\"prompt_tokens_details\":{\"cached_tokens\":100}}}\n\n" +
                "data:[DONE]\n\n"
            ).reader().buffered()) { chat.add(it) }
        assertEquals(LlmResponseChunk.Done("stop", 130, 5, 100), chat.single())
        val responses = mutableListOf<LlmResponseChunk>()
        parseResponsesStream("data:{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":130,\"output_tokens\":5,\"input_tokens_details\":{\"cached_tokens\":100}}}}\n\n".reader().buffered()) { responses.add(it) }
        assertEquals(LlmResponseChunk.Done("stop", 130, 5, 100), responses.single())
    }
}
