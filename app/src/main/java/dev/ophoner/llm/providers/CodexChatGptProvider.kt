package dev.ophoner.llm.providers

import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.llm.LlmContentBlock
import dev.ophoner.llm.LlmMessage
import dev.ophoner.llm.LlmProvider
import dev.ophoner.llm.LlmResponseChunk
import dev.ophoner.llm.LlmRole
import dev.ophoner.llm.auth.ProviderCredentialResolver
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.net.SocketTimeoutException
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

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun streamCompletion(
        messages: List<LlmMessage>,
        tools: List<Tool>,
        config: ProviderConfig,
    ): Flow<LlmResponseChunk> = callbackFlow {
        try {
            val (bearer, accountId) = if (credentialResolver != null) {
                credentialResolver.bearerCredential(config)
            } else {
                config.apiKey to config.accountId.takeIf { it.isNotBlank() }
            }

            withContext(Dispatchers.IO) {
                try {
                    val baseUrl = config.baseUrl.ifBlank { DEFAULT_BASE_URL }.trimEnd('/')
                    val body = buildRequestBody(messages, tools, config)
                    val requestBuilder = Request.Builder()
                        .url("$baseUrl/responses")
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .header("Authorization", "Bearer $bearer")
                        .header("OpenAI-Beta", "responses=v1")
                        .header("originator", "ophoner")
                        .post(body.toString().toRequestBody("application/json".toMediaType()))

                    val resolvedAccountId = accountId ?: config.accountId.takeIf { it.isNotBlank() }
                    if (!resolvedAccountId.isNullOrBlank()) {
                        requestBuilder.header("ChatGPT-Account-ID", resolvedAccountId)
                    }

                    val response = httpClient.newCall(requestBuilder.build()).execute()
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
                    android.util.Log.w(TAG, "SSE stream timed out", e)
                    trySend(
                        LlmResponseChunk.Error(
                            "Network timeout: no data from API for 5 minutes. ${e.message ?: ""}",
                        ),
                    )
                    trySend(LlmResponseChunk.Done)
                    close(e)
                } catch (e: IOException) {
                    android.util.Log.w(TAG, "SSE stream IO error", e)
                    trySend(LlmResponseChunk.Error("Network error: ${e.message ?: e.javaClass.simpleName}"))
                    trySend(LlmResponseChunk.Done)
                    close(e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Credential or request setup failed", e)
            trySend(LlmResponseChunk.Error(e.message ?: e.javaClass.simpleName))
            trySend(LlmResponseChunk.Done)
            close(e)
        }
        awaitClose()
    }

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
                    if (text.isNotEmpty()) {
                        result.add(buildJsonObject {
                            put("type", "message")
                            put("role", "user")
                            putJsonArray("content") {
                                add(buildJsonObject {
                                    put("type", "input_text")
                                    put("text", text)
                                })
                            }
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

    private fun kotlinx.coroutines.channels.ProducerScope<LlmResponseChunk>.parseSSEStream(
        reader: BufferedReader,
    ) {
        val openToolCalls = linkedMapOf<String, String>() // callId -> name
        val itemIdToCallId = mutableMapOf<String, String>()

        fun resolveCallId(itemId: String?, callId: String?): String? {
            if (!callId.isNullOrBlank()) {
                if (!itemId.isNullOrBlank()) itemIdToCallId[itemId] = callId
                return callId
            }
            if (!itemId.isNullOrBlank()) return itemIdToCallId[itemId] ?: itemId
            return null
        }

        fun endOpenToolCalls() {
            for (id in openToolCalls.keys.toList()) {
                trySend(LlmResponseChunk.ToolCallEnd(id))
            }
            openToolCalls.clear()
        }

        reader.useLines { lines ->
            for (line in lines) {
                if (!line.startsWith("data: ")) continue
                val data = line.removePrefix("data: ").trim()
                if (data.isEmpty() || data == "[DONE]") {
                    if (data == "[DONE]") break
                    continue
                }

                try {
                    val event = json.parseToJsonElement(data).jsonObject
                    val type = event["type"]?.jsonPrimitive?.contentOrNull ?: continue

                    when (type) {
                        "response.output_text.delta" -> {
                            val delta = event["delta"]?.jsonPrimitive?.contentOrNull
                            if (!delta.isNullOrEmpty()) {
                                trySend(LlmResponseChunk.TextDelta(delta))
                            }
                        }

                        "response.output_item.added", "response.output_item.done" -> {
                            val item = event["item"]?.jsonObject
                            if (item?.get("type")?.jsonPrimitive?.contentOrNull == "function_call") {
                                val callId = item["call_id"]?.jsonPrimitive?.contentOrNull
                                    ?: item["id"]?.jsonPrimitive?.contentOrNull
                                if (callId != null) {
                                    val itemId = item["id"]?.jsonPrimitive?.contentOrNull
                                    if (!itemId.isNullOrBlank()) itemIdToCallId[itemId] = callId
                                    val name = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    if (type == "response.output_item.added" && callId !in openToolCalls) {
                                        openToolCalls[callId] = name
                                        trySend(LlmResponseChunk.ToolCallStart(callId, name))
                                    }
                                    if (type == "response.output_item.done" && callId in openToolCalls) {
                                        trySend(LlmResponseChunk.ToolCallEnd(callId))
                                        openToolCalls.remove(callId)
                                    }
                                }
                            }
                        }

                        "response.function_call_arguments.delta" -> {
                            val itemId = event["item_id"]?.jsonPrimitive?.contentOrNull
                            val callId = resolveCallId(
                                itemId,
                                event["call_id"]?.jsonPrimitive?.contentOrNull,
                            )
                            if (callId != null) {
                                if (callId !in openToolCalls) {
                                    openToolCalls[callId] = ""
                                    trySend(LlmResponseChunk.ToolCallStart(callId, ""))
                                }
                                val delta = event["delta"]?.jsonPrimitive?.contentOrNull
                                if (!delta.isNullOrEmpty()) {
                                    trySend(LlmResponseChunk.ToolCallArgumentDelta(callId, delta))
                                }
                            }
                        }

                        "response.function_call_arguments.done" -> {
                            val itemId = event["item_id"]?.jsonPrimitive?.contentOrNull
                            val callId = resolveCallId(
                                itemId,
                                event["call_id"]?.jsonPrimitive?.contentOrNull,
                            )
                            if (callId != null && callId in openToolCalls) {
                                trySend(LlmResponseChunk.ToolCallEnd(callId))
                                openToolCalls.remove(callId)
                            }
                        }

                        "response.completed", "response.done" -> {
                            endOpenToolCalls()
                        }

                        "response.failed", "response.incomplete", "response.error", "error" -> {
                            val message = event["error"]?.jsonObject
                                ?.get("message")?.jsonPrimitive?.contentOrNull
                                ?: event["message"]?.jsonPrimitive?.contentOrNull
                                ?: "Codex response error ($type)"
                            trySend(LlmResponseChunk.Error(message))
                            endOpenToolCalls()
                        }

                        else -> Unit
                    }
                } catch (e: Exception) {
                    android.util.Log.d(TAG, "Skipping malformed SSE line: ${e.message}")
                }
            }
        }
        endOpenToolCalls()
    }

    companion object {
        private const val TAG = "CodexChatGptProvider"
        private const val DEFAULT_BASE_URL = "https://chatgpt.com/backend-api/codex"
    }
}
