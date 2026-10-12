package dev.ophoner.agent

import dev.ophoner.tools.ToolResult
import kotlinx.coroutines.CompletableDeferred

enum class AgentStopReason { COMPLETED, BUDGET_EXHAUSTED, ERROR, CONTEXT_LIMIT, STALLED, CANCELLED }

sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent
    data class ToolCallStarted(val id: String, val name: String) : AgentEvent
    data class ToolCallArgDelta(val id: String, val json: String) : AgentEvent
    data class ToolExecuting(val id: String, val name: String, val acknowledged: CompletableDeferred<Unit>? = null) : AgentEvent
    data class ToolCompleted(val id: String, val result: ToolResult, val acknowledged: CompletableDeferred<Unit>? = null) : AgentEvent
    data class Error(val message: String) : AgentEvent
    data class Usage(
        val inputTokens: Long?,
        val outputTokens: Long?,
        val cacheReadInputTokens: Long? = null,
        val cacheCreationInputTokens: Long? = null,
    ) : AgentEvent
    data class Retrying(val attempt: Int, val delayMillis: Long) : AgentEvent
    /** A failed provider attempt must not leak partial calls into the saved transcript. */
    data object ResponseDiscarded : AgentEvent
    data class RunStopped(val reason: AgentStopReason) : AgentEvent
    data object IterationComplete : AgentEvent
    data object Finished : AgentEvent
}
