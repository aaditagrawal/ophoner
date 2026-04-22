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
    data object Done : LlmResponseChunk
    data class Error(val message: String) : LlmResponseChunk
}

interface LlmProvider {
    val providerId: String

    fun streamCompletion(
        messages: List<LlmMessage>,
        tools: List<Tool>,
        config: ProviderConfig,
    ): Flow<LlmResponseChunk>
}
