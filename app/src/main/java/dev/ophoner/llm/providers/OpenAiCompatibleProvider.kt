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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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

class OpenAiCompatibleProvider(
    httpClient: OkHttpClient,
) : LlmProvider {
    override val providerId = "openai"

    // Stream-friendly client: no overall call timeout, but per-chunk read timeout
    // so stalled SSE streams abort rather than hang forever.
    private val httpClient: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
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
                val request = Request.Builder()
                    .url("${config.baseUrl.trimEnd('/')}/chat/completions")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer ${config.apiKey}")
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
                android.util.Log.w("OpenAiProvider", "SSE stream timed out", e)
                trySend(LlmResponseChunk.Error("Network timeout: no data from API for 2 minutes. ${e.message ?: ""}"))
                trySend(LlmResponseChunk.Done)
                close(e)
            } catch (e: IOException) {
                android.util.Log.w("OpenAiProvider", "SSE stream IO error", e)
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
        put("stream", true)
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
                    put("content", msg.content.filterIsInstance<LlmContentBlock.Text>()
                        .joinToString("\n") { it.text })
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

    private fun kotlinx.coroutines.channels.ProducerScope<LlmResponseChunk>.parseSSEStream(
        reader: BufferedReader,
    ) {
        // Track tool call IDs by index — OpenAI only sends the id in the first delta
        val toolCallIdsByIndex = mutableMapOf<Int, String>()

        reader.useLines { lines ->
            for (line in lines) {
                if (!line.startsWith("data: ")) continue
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") break

                try {
                    val chunk = json.parseToJsonElement(data).jsonObject
                    val choices = chunk["choices"]?.jsonArray ?: continue
                    if (choices.isEmpty()) continue

                    val delta = choices[0].jsonObject["delta"]?.jsonObject ?: continue

                    // Text content
                    delta["content"]?.jsonPrimitive?.content?.let { text ->
                        if (text.isNotEmpty()) trySend(LlmResponseChunk.TextDelta(text))
                    }

                    // Tool calls
                    delta["tool_calls"]?.jsonArray?.forEach { tc ->
                        val tcObj = tc.jsonObject
                        val index = tcObj["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        val id = tcObj["id"]?.jsonPrimitive?.content
                        val function = tcObj["function"]?.jsonObject
                        val name = function?.get("name")?.jsonPrimitive?.content

                        // Only emit ToolCallStart for NEW tool calls (first time we see this index)
                        if (index !in toolCallIdsByIndex) {
                            val resolvedId = id ?: "tool_$index"
                            toolCallIdsByIndex[index] = resolvedId
                            trySend(LlmResponseChunk.ToolCallStart(resolvedId, name ?: ""))
                        }

                        // Argument deltas
                        function?.get("arguments")?.jsonPrimitive?.content?.let { args ->
                            if (args.isNotEmpty()) {
                                val resolvedId = toolCallIdsByIndex[index] ?: id ?: "tool_$index"
                                trySend(LlmResponseChunk.ToolCallArgumentDelta(resolvedId, args))
                            }
                        }
                    }

                    // Check finish reason — emit ToolCallEnd for all tracked tool calls
                    choices[0].jsonObject["finish_reason"]?.jsonPrimitive?.content?.let { reason ->
                        if (reason == "tool_calls" || reason == "stop") {
                            for ((_, tcId) in toolCallIdsByIndex) {
                                trySend(LlmResponseChunk.ToolCallEnd(tcId))
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Skip malformed SSE line — the SSE protocol allows keep-alive / partial
                    // frames that may not parse as JSON. Log at debug so it's observable
                    // without noise in production logs.
                    android.util.Log.d("OpenAiProvider", "Skipping malformed SSE line: ${e.message}")
                }
            }
        }
    }
}
