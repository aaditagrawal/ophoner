package dev.ophoner.llm.providers

import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.llm.LlmContentBlock
import dev.ophoner.llm.LlmMessage
import dev.ophoner.llm.LlmProvider
import dev.ophoner.llm.LlmResponseChunk
import dev.ophoner.llm.LlmRole
import dev.ophoner.tools.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.net.SocketTimeoutException
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

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun streamCompletion(
        messages: List<LlmMessage>,
        tools: List<Tool>,
        config: ProviderConfig,
    ): Flow<LlmResponseChunk> = callbackFlow {
        withContext(Dispatchers.IO) {
            try {
                val body = buildRequestBody(messages, tools, config)
                val baseUrl = config.baseUrl.trimEnd('/')
                val url = if (baseUrl.endsWith("/v1")) "$baseUrl/messages"
                    else "$baseUrl/v1/messages"

                val request = Request.Builder()
                    .url(url)
                    .header("Content-Type", "application/json")
                    .header("x-api-key", config.apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    trySend(LlmResponseChunk.Error("HTTP ${response.code}: $errorBody"))
                    trySend(LlmResponseChunk.Done)
                    close()
                    return@withContext
                }

                val reader = response.body?.byteStream()?.bufferedReader()
                    ?: run {
                        trySend(LlmResponseChunk.Error("Empty response body"))
                        trySend(LlmResponseChunk.Done)
                        close()
                        return@withContext
                    }

                parseSSEStream(reader)
                trySend(LlmResponseChunk.Done)
                close()
            } catch (e: SocketTimeoutException) {
                android.util.Log.w("ClaudeProvider", "SSE stream timed out", e)
                trySend(LlmResponseChunk.Error("Network timeout: no data from Claude API for 5 minutes. ${e.message ?: ""}"))
                trySend(LlmResponseChunk.Done)
                close(e)
            } catch (e: IOException) {
                android.util.Log.w("ClaudeProvider", "SSE stream IO error", e)
                trySend(LlmResponseChunk.Error("Network error: ${e.message ?: e.javaClass.simpleName}"))
                trySend(LlmResponseChunk.Done)
                close(e)
            }
        }
        awaitClose()
    }

    private fun buildRequestBody(
        messages: List<LlmMessage>,
        tools: List<Tool>,
        config: ProviderConfig,
    ): JsonObject = buildJsonObject {
        put("model", config.modelId)
        put("max_tokens", 4096)
        put("stream", true)

        // Extract system message
        val systemMsg = messages.firstOrNull { it.role == LlmRole.SYSTEM }
        if (systemMsg != null) {
            val systemText = systemMsg.content.filterIsInstance<LlmContentBlock.Text>()
                .joinToString("\n") { it.text }
            put("system", systemText)
        }

        putJsonArray("messages") {
            for (msg in messages.filter { it.role != LlmRole.SYSTEM }) {
                when (msg.role) {
                    LlmRole.USER -> add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            for (block in msg.content.filterIsInstance<LlmContentBlock.Text>()) {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", block.text)
                                })
                            }
                        }
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
                for (tool in tools) {
                    add(buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("input_schema", tool.parameters)
                    })
                }
            }
        }
    }

    private fun kotlinx.coroutines.channels.ProducerScope<LlmResponseChunk>.parseSSEStream(
        reader: BufferedReader,
    ) {
        var currentToolId = ""
        reader.useLines { lines ->
            for (line in lines) {
                if (!line.startsWith("data: ")) continue
                val data = line.removePrefix("data: ").trim()
                if (data.isEmpty()) continue

                try {
                    val event = json.parseToJsonElement(data).jsonObject
                    val type = event["type"]?.jsonPrimitive?.content ?: continue

                    when (type) {
                        "content_block_start" -> {
                            val block = event["content_block"]?.jsonObject ?: continue
                            when (block["type"]?.jsonPrimitive?.content) {
                                "tool_use" -> {
                                    val id = block["id"]?.jsonPrimitive?.content ?: ""
                                    val name = block["name"]?.jsonPrimitive?.content ?: ""
                                    currentToolId = id
                                    trySend(LlmResponseChunk.ToolCallStart(id, name))
                                }
                            }
                        }
                        "content_block_delta" -> {
                            val delta = event["delta"]?.jsonObject ?: continue
                            when (delta["type"]?.jsonPrimitive?.content) {
                                "text_delta" -> {
                                    val text = delta["text"]?.jsonPrimitive?.content ?: ""
                                    if (text.isNotEmpty()) trySend(LlmResponseChunk.TextDelta(text))
                                }
                                "input_json_delta" -> {
                                    val partial = delta["partial_json"]?.jsonPrimitive?.content ?: ""
                                    if (partial.isNotEmpty()) {
                                        trySend(LlmResponseChunk.ToolCallArgumentDelta(currentToolId, partial))
                                    }
                                }
                            }
                        }
                        "content_block_stop" -> {
                            if (currentToolId.isNotEmpty()) {
                                trySend(LlmResponseChunk.ToolCallEnd(currentToolId))
                                currentToolId = ""
                            }
                        }
                        "message_stop" -> {
                            // Done
                        }
                    }
                } catch (e: Exception) {
                    // Skip malformed SSE line — the SSE protocol guarantees some lines
                    // (keep-alives, partial frames) may not parse as JSON events. Log at
                    // debug level so it's observable without spamming production logs.
                    android.util.Log.d("ClaudeProvider", "Skipping malformed SSE line: ${e.message}")
                }
            }
        }
    }
}
