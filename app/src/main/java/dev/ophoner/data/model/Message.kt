package dev.ophoner.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: List<ContentBlock>,
    val orderIndex: Int,
    val createdAt: Long,
)

@Serializable
enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL_RESULT,
}

@Serializable
sealed interface ContentBlock {
    @Serializable
    data class Text(val text: String) : ContentBlock

    @Serializable
    data class ToolUse(
        val id: String,
        val name: String,
        val argumentsJson: String,
    ) : ContentBlock

    @Serializable
    data class ToolResult(
        val toolUseId: String,
        val output: String,
        val isError: Boolean = false,
    ) : ContentBlock
}
