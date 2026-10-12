package dev.ophoner.llm

import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.tools.Tool
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Provider-agnostic message model
data class LlmMessage(
    val role: LlmRole,
    val content: List<LlmContentBlock>,
)

enum class LlmRole { SYSTEM, USER, ASSISTANT, TOOL_RESULT }

sealed interface LlmContentBlock {
    data class Image(val mediaType: String, val base64: String) : LlmContentBlock
    data class Text(val text: String) : LlmContentBlock
    data class ToolUse(val id: String, val name: String, val arguments: JsonObject) : LlmContentBlock
    data class ToolResult(val toolUseId: String, val output: String, val isError: Boolean) : LlmContentBlock
}

// What the streaming API emits
sealed interface LlmResponseChunk {
    data class TextDelta(val text: String) : LlmResponseChunk
    data class ToolCallStart(val id: String, val name: String) : LlmResponseChunk
    data class ToolCallArgumentDelta(val id: String, val json: String) : LlmResponseChunk
    data class ToolCallEnd(val id: String) : LlmResponseChunk
    data class Done(
        val stopReason: String = "stop",
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
        val cacheReadInputTokens: Long? = null,
        val cacheCreationInputTokens: Long? = null,
    ) : LlmResponseChunk
    data class Error(
        val message: String,
        val kind: LlmErrorKind = LlmErrorKind.PROTOCOL,
        val retryable: Boolean = false,
        val retryAfterMillis: Long? = null,
    ) : LlmResponseChunk
}

enum class LlmErrorKind { NETWORK, HTTP, PROTOCOL, AUTHENTICATION, CONTEXT_LIMIT }

interface LlmProvider {
    val providerId: String

    fun streamCompletion(
        messages: List<LlmMessage>,
        tools: List<Tool>,
        config: ProviderConfig,
    ): Flow<LlmResponseChunk>
}
