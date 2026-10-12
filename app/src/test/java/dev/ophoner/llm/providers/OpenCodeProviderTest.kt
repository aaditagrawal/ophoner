package dev.ophoner.llm.providers

import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.model.ProviderType
import dev.ophoner.llm.LlmContentBlock
import dev.ophoner.llm.LlmMessage
import dev.ophoner.llm.LlmResponseChunk
import dev.ophoner.llm.LlmRole
import dev.ophoner.llm.models.ModelApi
import dev.ophoner.llm.models.ModelsDevCatalog
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class OpenCodeProviderTest {
    @Test fun `Go routes catalog models through all three protocols with the right credentials`() = runBlocking {
        for (api in ModelApi.entries) {
            val catalog = mockk<ModelsDevCatalog>()
            coEvery { catalog.modelApi("opencode-go", "new-model") } returns api
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val endpoint = when (api) {
                    ModelApi.CHAT_COMPLETIONS -> "chat/completions"
                    ModelApi.RESPONSES -> "responses"
                    ModelApi.ANTHROPIC -> "messages"
                }
                assertEquals("/zen/go/v1/$endpoint", request.url.encodedPath)
                assertEquals(if (api == ModelApi.ANTHROPIC) "key" else "Bearer key",
                    request.header(if (api == ModelApi.ANTHROPIC) "x-api-key" else "Authorization"))
                assertNull(request.header("ChatGPT-Account-ID"))
                val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                assertTrue(body.contains("\"model\":\"new-model\""))
                assertTrue(body.contains("hello"))
                val frames = when (api) {
                    ModelApi.CHAT_COMPLETIONS -> "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    ModelApi.RESPONSES -> "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"
                    ModelApi.ANTHROPIC -> "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\ndata: {\"type\":\"message_stop\"}\n\n"
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(frames.toResponseBody()).build()
            }.build()
            val config = ProviderConfig("go", "Go", "key", "https://opencode.ai/zen/go/v1", "new-model", ProviderType.OPENCODE_GO)
            val chunks = OpenCodeProvider(client, catalog).streamCompletion(
                listOf(LlmMessage(LlmRole.USER, listOf(LlmContentBlock.Text("hello")))), emptyList(), config,
            ).toList()
            assertTrue("$api must complete without a protocol error: $chunks", chunks.last() is LlmResponseChunk.Done)
        }
    }
}
