package dev.ophoner.llm.providers

import dev.ophoner.data.model.ProviderType
import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.llm.LlmContentBlock
import dev.ophoner.llm.imageMessageContent
import dev.ophoner.llm.LlmMessage
import dev.ophoner.llm.LlmProvider
import dev.ophoner.llm.LlmResponseChunk
import dev.ophoner.llm.LlmRole
import dev.ophoner.tools.ToolExecutionContext
import kotlinx.coroutines.currentCoroutineContext
import dev.ophoner.tools.Tool
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class OpenAiCompatibleProvider(
    httpClient: OkHttpClient,
) : LlmProvider {
    override val providerId = "openai"

    // Stream-friendly client: no overall call timeout, with a generous per-chunk
    // read timeout so backgrounded-but-healthy streams are not treated as failed.
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
        Request.Builder().url("${config.baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(buildRequestBody(messages, tools, config, currentCoroutineContext()[ToolExecutionContext]?.cacheKey).toString().toRequestBody("application/json".toMediaType()))
            .build()
    }, parse = ::parseChatStream)

    private fun buildRequestBody(
        messages: List<LlmMessage>,
        tools: List<Tool>,
        config: ProviderConfig,
        cacheKey: String?,
    ): JsonObject = buildJsonObject {
        put("model", config.modelId)
        put("stream", true)
        if (config.providerType in setOf(ProviderType.OPENAI, ProviderType.OPENROUTER)) {
            putJsonObject("stream_options") { put("include_usage", true) }
        }
        if (config.promptCaching && config.providerType == ProviderType.OPENAI && !cacheKey.isNullOrBlank()) {
            put("prompt_cache_key", cacheKey)
        }
        val tokenLimitField = if (config.providerType == ProviderType.OPENAI) "max_completion_tokens" else "max_tokens"
        put(tokenLimitField, config.maxOutputTokens.coerceIn(256, 65536))
        putJsonArray("messages") {
            for (msg in formatMessages(messages)) {
                add(msg)
            }
        }
        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                for (tool in tools) {
                    add(buildJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters)
                        }
                    })
                }
            }
            put("tool_choice", "auto")
        }
    }

    private fun formatMessages(messages: List<LlmMessage>): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        for (msg in messages) {
            when (msg.role) {
                LlmRole.SYSTEM -> result.add(buildJsonObject {
                    put("role", "system")
                    put("content", msg.content.filterIsInstance<LlmContentBlock.Text>()
                        .joinToString("\n") { it.text })
                })
                LlmRole.USER -> result.add(buildJsonObject {
                    put("role", "user")
                    if (msg.content.any { it is LlmContentBlock.Image }) put("content", imageMessageContent(msg.content, "chat"))
                    else put("content", msg.content.filterIsInstance<LlmContentBlock.Text>().joinToString("\n") { it.text })
                })
                LlmRole.ASSISTANT -> result.add(buildJsonObject {
                    put("role", "assistant")
                    val textParts = msg.content.filterIsInstance<LlmContentBlock.Text>()
                    val toolUses = msg.content.filterIsInstance<LlmContentBlock.ToolUse>()
                    if (textParts.isNotEmpty()) {
                        put("content", textParts.joinToString("\n") { it.text })
                    } else {
                        put("content", JsonNull)
                    }
                    if (toolUses.isNotEmpty()) {
                        putJsonArray("tool_calls") {
                            for (tc in toolUses) {
                                add(buildJsonObject {
                                    put("id", tc.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", tc.name)
                                        put("arguments", tc.arguments.toString())
                                    }
                                })
                            }
                        }
                    }
                })
                LlmRole.TOOL_RESULT -> {
                    // Each tool result MUST be a separate message in OpenAI format
                    for (block in msg.content.filterIsInstance<LlmContentBlock.ToolResult>()) {
                        result.add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", block.toolUseId)
                            put("content", block.output)
                        })
                    }
                }
            }
        }
        return result
    }

}
