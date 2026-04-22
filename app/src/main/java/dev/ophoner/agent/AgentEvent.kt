package dev.ophoner.agent

import dev.ophoner.tools.ToolResult

sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent
    data class ToolCallStarted(val id: String, val name: String) : AgentEvent
    data class ToolCallArgDelta(val id: String, val json: String) : AgentEvent
    data class ToolExecuting(val id: String, val name: String) : AgentEvent
    data class ToolCompleted(val id: String, val result: ToolResult) : AgentEvent
    data class Error(val message: String) : AgentEvent
    data object IterationComplete : AgentEvent
    data object Finished : AgentEvent
}
