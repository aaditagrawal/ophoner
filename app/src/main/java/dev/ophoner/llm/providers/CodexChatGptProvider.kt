package dev.ophoner.llm.providers

import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.model.ProviderType
import dev.ophoner.llm.LlmContentBlock
import dev.ophoner.llm.imageMessageContent
import dev.ophoner.llm.LlmMessage
import dev.ophoner.llm.LlmProvider
import dev.ophoner.llm.LlmResponseChunk
import dev.ophoner.llm.LlmRole
import dev.ophoner.llm.auth.ProviderCredentialResolver
import dev.ophoner.tools.Tool
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class CodexChatGptProvider(
    httpClient: OkHttpClient,
    private val credentialResolver: ProviderCredentialResolver? = null,
) : LlmProvider {
    override val providerId = "codex_chatgpt"

    private val httpClient: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun streamCompletion(
        messages: List<LlmMessage>,
        tools: List<Tool>,
        config: ProviderConfig,
    ): Flow<LlmResponseChunk> = streamResponse(httpClient, request = {
        val (bearer, accountId) = credentialResolver?.bearerCredential(config)
            ?: (config.apiKey to config.accountId.takeIf { it.isNotBlank() })
        val baseUrl = config.baseUrl.ifBlank { DEFAULT_BASE_URL }.trimEnd('/')
        Request.Builder().url("$baseUrl/responses")
            .header("Authorization", "Bearer $bearer")
            .apply {
                if (config.providerType == ProviderType.CODEX_CHATGPT) {
                    header("OpenAI-Beta", "responses=v1")
                    header("originator", "ophoner")
                    (accountId ?: config.accountId.takeIf { it.isNotBlank() })?.let { header("ChatGPT-Account-ID", it) }
                }
            }
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(buildRequestBody(messages, tools, config).toString().toRequestBody("application/json".toMediaType()))
            .build()
    }, parse = ::parseResponsesStream)

    private fun buildRequestBody(
        messages: List<LlmMessage>,
        tools: List<Tool>,
        config: ProviderConfig,
    ): JsonObject = buildJsonObject {
        put("model", config.modelId)
        put("instructions", extractInstructions(messages))
        put("input", buildJsonArray {
            for (item in formatInputItems(messages)) {
                add(item)
            }
        })
        put("store", false)
        put("stream", true)
        if (config.providerType == ProviderType.CODEX_CHATGPT) {
            put("service_tier", config.chatGptSpeed.serviceTier)
        }
        if (config.providerType != ProviderType.CODEX_CHATGPT) put("max_output_tokens", config.maxOutputTokens.coerceIn(256, 65536))
        put("parallel_tool_calls", true)
        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                for (tool in tools) {
                    add(buildJsonObject {
                        put("type", "function")
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.parameters)
                    })
                }
            }
            put("tool_choice", "auto")
        }
    }

    private fun extractInstructions(messages: List<LlmMessage>): String =
        messages
            .filter { it.role == LlmRole.SYSTEM }
            .flatMap { it.content.filterIsInstance<LlmContentBlock.Text>() }
            .joinToString("\n") { it.text }

    private fun formatInputItems(messages: List<LlmMessage>): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        for (msg in messages) {
            when (msg.role) {
                LlmRole.SYSTEM -> Unit
                LlmRole.USER -> {
                    val text = msg.content.filterIsInstance<LlmContentBlock.Text>()
                        .joinToString("\n") { it.text }
                    if (text.isNotEmpty() || msg.content.any { it is LlmContentBlock.Image }) {
                        result.add(buildJsonObject {
                            put("type", "message")
                            put("role", "user")
                            put("content", imageMessageContent(msg.content, "responses"))
                        })
                    }
                }
                LlmRole.ASSISTANT -> {
                    val textParts = msg.content.filterIsInstance<LlmContentBlock.Text>()
                    val toolUses = msg.content.filterIsInstance<LlmContentBlock.ToolUse>()
                    val text = textParts.joinToString("\n") { it.text }
                    if (text.isNotEmpty()) {
                        result.add(buildJsonObject {
                            put("type", "message")
                            put("role", "assistant")
                            putJsonArray("content") {
                                add(buildJsonObject {
                                    put("type", "output_text")
                                    put("text", text)
                                })
                            }
                        })
                    }
                    for (tc in toolUses) {
                        result.add(buildJsonObject {
                            put("type", "function_call")
                            put("call_id", tc.id)
                            put("name", tc.name)
                            put("arguments", tc.arguments.toString())
                        })
                    }
                }
                LlmRole.TOOL_RESULT -> {
                    for (block in msg.content.filterIsInstance<LlmContentBlock.ToolResult>()) {
                        result.add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", block.toolUseId)
                            put("output", block.output)
                        })
                    }
                }
            }
        }
        return result
    }

    companion object {
        private const val TAG = "CodexChatGptProvider"
        private const val DEFAULT_BASE_URL = "https://chatgpt.com/backend-api/codex"
    }
}
