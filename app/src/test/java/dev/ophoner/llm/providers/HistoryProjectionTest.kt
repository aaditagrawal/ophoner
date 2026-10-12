package dev.ophoner.llm.providers

import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.model.ProviderType
import dev.ophoner.llm.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class HistoryProjectionTest {
    @Test fun `persisted assistant cards replay calls and outputs on all providers`() = runBlocking {
        val history = LlmHistory.normalize(listOf(
            LlmMessage(LlmRole.USER, listOf(LlmContentBlock.Text("read"))),
            LlmMessage(LlmRole.ASSISTANT, listOf(
                LlmContentBlock.ToolUse("call1", "read", JsonObject(emptyMap())),
                LlmContentBlock.ToolResult("call1", "saved output", false),
            )),
            LlmMessage(LlmRole.USER, listOf(LlmContentBlock.Text("continue"))),
        ))
        for (type in listOf(ProviderType.CLAUDE, ProviderType.OPENAI, ProviderType.CODEX_CHATGPT)) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val buffer = Buffer()
                chain.request().body!!.writeTo(buffer)
                val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                if (type == ProviderType.CODEX_CHATGPT) {
                    val input = body.getValue("input").jsonArray
                    assertEquals("function_call", input[1].jsonObject.getValue("type").jsonPrimitive.content)
                    assertEquals("function_call_output", input[2].jsonObject.getValue("type").jsonPrimitive.content)
                    assertEquals("call1", input[2].jsonObject.getValue("call_id").jsonPrimitive.content)
                    assertEquals("saved output", input[2].jsonObject.getValue("output").jsonPrimitive.content)
                } else {
                    val messages = body.getValue("messages").jsonArray
                    assertEquals("assistant", messages[1].jsonObject.getValue("role").jsonPrimitive.content)
                    val result = messages[2].jsonObject
                    if (type == ProviderType.CLAUDE) {
                        assertEquals("user", result.getValue("role").jsonPrimitive.content)
                        val block = result.getValue("content").jsonArray.single().jsonObject
                        assertEquals("tool_result", block.getValue("type").jsonPrimitive.content)
                        assertEquals("call1", block.getValue("tool_use_id").jsonPrimitive.content)
                        assertEquals("saved output", block.getValue("content").jsonPrimitive.content)
                    } else {
                        assertEquals("tool", result.getValue("role").jsonPrimitive.content)
                        assertEquals("call1", result.getValue("tool_call_id").jsonPrimitive.content)
                        assertEquals("saved output", result.getValue("content").jsonPrimitive.content)
                    }
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(400).message("fixture").body("".toResponseBody()).build()
            }.build()
            val provider: LlmProvider = when (type) {
                ProviderType.CLAUDE -> ClaudeProvider(client)
                ProviderType.CODEX_CHATGPT -> CodexChatGptProvider(client)
                else -> OpenAiCompatibleProvider(client)
            }
            provider.streamCompletion(history, emptyList(), ProviderConfig("test", "test", baseUrl = "https://example.invalid/v1", modelId = "test", providerType = type)).toList()
        }
    }
}
