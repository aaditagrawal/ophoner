package dev.ophoner.llm.providers

import dev.ophoner.data.model.ProviderType
import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.llm.LlmContentBlock
import dev.ophoner.llm.imageMessageContent
import dev.ophoner.llm.LlmMessage
import dev.ophoner.llm.LlmProvider
import dev.ophoner.llm.LlmResponseChunk
import dev.ophoner.llm.LlmRole
import dev.ophoner.tools.Tool
import kotlinx.coroutines.flow.Flow
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

class ClaudeProvider(
    httpClient: OkHttpClient,
) : LlmProvider {
    override val providerId = "claude"

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
        val baseUrl = config.baseUrl.trimEnd('/')
        val url = if (baseUrl.endsWith("/v1")) "$baseUrl/messages" else "$baseUrl/v1/messages"
        Request.Builder().url(url)
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(buildRequestBody(messages, tools, config).toString().toRequestBody("application/json".toMediaType()))
            .build()
    }, parse = ::parseClaudeStream)

    private fun buildRequestBody(
        messages: List<LlmMessage>,
        tools: List<Tool>,
        config: ProviderConfig,
    ): JsonObject = buildJsonObject {
        put("model", config.modelId)
        put("max_tokens", config.maxOutputTokens.coerceIn(256, 65536))
        put("stream", true)

        // Cache only on the known Anthropic API; custom gateways may reject cache hints.
        val cacheEnabled = config.promptCaching && config.providerType == ProviderType.CLAUDE
        // The third breakpoint advances with the conversation; total remains below the four-slot limit.
        if (cacheEnabled) putJsonObject("cache_control") { put("type", "ephemeral") }
        val systemText = messages.filter { it.role == LlmRole.SYSTEM }
            .flatMap { it.content.filterIsInstance<LlmContentBlock.Text>() }
            .joinToString("\n") { it.text }
        if (systemText.isNotEmpty()) {
            if (cacheEnabled) {
                putJsonArray("system") {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", systemText)
                        putJsonObject("cache_control") { put("type", "ephemeral") }
                    })
                }
            } else put("system", systemText)
        }

        putJsonArray("messages") {
            for (msg in messages.filter { it.role != LlmRole.SYSTEM }) {
                when (msg.role) {
                    LlmRole.USER -> add(buildJsonObject {
                        put("role", "user")
                        put("content", imageMessageContent(msg.content, "anthropic"))
                    })
                    LlmRole.ASSISTANT -> add(buildJsonObject {
                        put("role", "assistant")
                        putJsonArray("content") {
                            for (block in msg.content) {
                                when (block) {
                                    is LlmContentBlock.Text -> add(buildJsonObject {
                                        put("type", "text")
                                        put("text", block.text)
                                    })
                                    is LlmContentBlock.ToolUse -> add(buildJsonObject {
                                        put("type", "tool_use")
                                        put("id", block.id)
                                        put("name", block.name)
                                        put("input", block.arguments)
                                    })
                                    else -> {}
                                }
                            }
                        }
                    })
                    LlmRole.TOOL_RESULT -> add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            for (block in msg.content.filterIsInstance<LlmContentBlock.ToolResult>()) {
                                add(buildJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", block.toolUseId)
                                    put("content", block.output)
                                    if (block.isError) put("is_error", true)
                                })
                            }
                        }
                    })
                    else -> {}
                }
            }
        }

        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                for ((index, tool) in tools.withIndex()) {
                    add(buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("input_schema", tool.parameters)
                        if (cacheEnabled && index == tools.lastIndex) {
                            putJsonObject("cache_control") { put("type", "ephemeral") }
                        }
                    })
                }
            }
        }
    }

}
