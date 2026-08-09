package dev.ophoner.agent

import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.llm.LlmContentBlock
import dev.ophoner.llm.LlmMessage
import dev.ophoner.llm.LlmProviderFactory
import dev.ophoner.llm.LlmResponseChunk
import dev.ophoner.llm.LlmRole
import dev.ophoner.tools.ToolExecutionContext
import dev.ophoner.tools.ToolRegistry
import dev.ophoner.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

const val DEFAULT_MAX_ITERATIONS = 25
const val YOLO_MAX_ITERATIONS = 40

/** Read-only / idempotent tools safe to run concurrently in one turn. */
private val PARALLEL_SAFE_TOOLS = setOf(
    "file_read",
    "file_list",
    "web_fetch",
    "web_search",
    "app_list",
)

@Singleton
class AgentLoop @Inject constructor(
    private val providerFactory: LlmProviderFactory,
    private val toolRegistry: ToolRegistry,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param scopedFolderUri Optional SAF tree URI for folder-scoped chats.
     *   When set, file tools resolve paths under this root for the entire run.
     *   When null, [dev.ophoner.tools.sandbox.FileAccessManager] falls back to
     *   the global settings working directory.
     * @param yoloMode When true, soft tool gates / future confirmation prompts
     *   are skipped and [YOLO_MAX_ITERATIONS] is used unless [maxIterations] is
     *   explicitly raised higher.
     */
    fun run(
        conversationMessages: List<LlmMessage>,
        config: ProviderConfig,
        maxIterations: Int = DEFAULT_MAX_ITERATIONS,
        scopedFolderUri: String? = null,
        yoloMode: Boolean = false,
    ): Flow<AgentEvent> = flow {
        val effectiveMax = when {
            yoloMode && maxIterations <= DEFAULT_MAX_ITERATIONS -> YOLO_MAX_ITERATIONS
            else -> maxIterations
        }
        val toolContext = ToolExecutionContext(
            rootUri = scopedFolderUri,
            yoloMode = yoloMode,
        )
        withContext(toolContext) {
            runAgent(conversationMessages, config, effectiveMax)
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentEvent>.runAgent(
        conversationMessages: List<LlmMessage>,
        config: ProviderConfig,
        maxIterations: Int,
    ) {
        val provider = providerFactory.create(config)
        val tools = toolRegistry.allTools()
        val messages = conversationMessages.toMutableList()
        var iteration = 0

        while (iteration < maxIterations) {
            iteration++
            val assistantContent = mutableListOf<LlmContentBlock>()
            val textAccumulator = StringBuilder()
            val pendingToolCalls = mutableMapOf<String, ToolCallAccumulator>()
            var hadError = false

            // Stream the LLM response. Use .catch to convert provider-level
            // IO/timeout exceptions into a structured agent error rather than
            // letting them bubble up and silently terminate the agent.
            try {
                provider.streamCompletion(messages, tools, config)
                    .catch { t ->
                        when (t) {
                            is CancellationException -> throw t // respect user cancel
                            is SocketTimeoutException -> {
                                android.util.Log.w("AgentLoop", "Provider stream timed out", t)
                                emit(LlmResponseChunk.Error("[Agent error: LLM stream timed out — ${t.message ?: "no data for 2 minutes"}]"))
                            }
                            is IOException -> {
                                android.util.Log.w("AgentLoop", "Provider stream IO error", t)
                                emit(LlmResponseChunk.Error("[Agent error: network failure — ${t.message ?: t.javaClass.simpleName}]"))
                            }
                            else -> {
                                android.util.Log.e("AgentLoop", "Provider stream unexpected error", t)
                                emit(LlmResponseChunk.Error("[Agent error: ${t.javaClass.simpleName} — ${t.message ?: "unknown"}]"))
                            }
                        }
                    }
                    .collect { chunk ->
                        when (chunk) {
                            is LlmResponseChunk.TextDelta -> {
                                textAccumulator.append(chunk.text)
                                emit(AgentEvent.TextDelta(chunk.text))
                            }
                            is LlmResponseChunk.ToolCallStart -> {
                                pendingToolCalls[chunk.id] = ToolCallAccumulator(chunk.id, chunk.name)
                                emit(AgentEvent.ToolCallStarted(chunk.id, chunk.name))
                            }
                            is LlmResponseChunk.ToolCallArgumentDelta -> {
                                val acc = pendingToolCalls[chunk.id]
                                    ?: pendingToolCalls.values.lastOrNull()
                                acc?.appendArg(chunk.json)
                                emit(AgentEvent.ToolCallArgDelta(chunk.id, chunk.json))
                            }
                            is LlmResponseChunk.ToolCallEnd -> {
                                // Finalized
                            }
                            is LlmResponseChunk.Done -> {
                                // Stream ended
                            }
                            is LlmResponseChunk.Error -> {
                                emit(AgentEvent.Error(chunk.message))
                                hadError = true
                            }
                        }
                    }
            } catch (ce: CancellationException) {
                // User-initiated cancel — propagate to terminate the flow cleanly.
                throw ce
            } catch (t: Throwable) {
                // Defensive catch: anything that escapes .catch (shouldn't normally) is
                // surfaced as a structured agent error instead of silently terminating.
                android.util.Log.e("AgentLoop", "Unhandled error in stream collection", t)
                emit(AgentEvent.Error("[Agent error: ${t.javaClass.simpleName} — ${t.message ?: "unknown"}]"))
                hadError = true
            }

            if (hadError) {
                emit(AgentEvent.Finished)
                return
            }

            // Build assistant content blocks
            if (textAccumulator.isNotEmpty()) {
                assistantContent.add(LlmContentBlock.Text(textAccumulator.toString()))
            }

            val toolCalls = pendingToolCalls.values.mapNotNull { acc ->
                try {
                    val args = json.parseToJsonElement(acc.argumentJson()).jsonObject
                    LlmContentBlock.ToolUse(acc.id, acc.name, args)
                } catch (e: Exception) {
                    // If JSON is malformed, try to recover with a closing brace.
                    try {
                        val fixed = acc.argumentJson().let { raw ->
                            if (!raw.endsWith("}")) "$raw}" else raw
                        }
                        val args = json.parseToJsonElement(fixed).jsonObject
                        LlmContentBlock.ToolUse(acc.id, acc.name, args)
                    } catch (recoveryError: Exception) {
                        android.util.Log.w(
                            "AgentLoop",
                            "Failed to parse tool-call arguments for ${acc.name} (id=${acc.id}): ${e.message}; recovery also failed: ${recoveryError.message}",
                        )
                        null
                    }
                }
            }

            toolCalls.forEach { assistantContent.add(it) }
            messages.add(LlmMessage(LlmRole.ASSISTANT, assistantContent))

            // If no tool calls, agent is done
            if (toolCalls.isEmpty()) break

            // Execute tools. Independent read-only tools in one turn run concurrently;
            // mutating / shell tools stay sequential (and flush any pending parallel batch first).
            val toolResults = mutableListOf<LlmContentBlock.ToolResult>()
            var i = 0
            while (i < toolCalls.size) {
                if (toolCalls[i].name in PARALLEL_SAFE_TOOLS) {
                    val batchStart = i
                    while (i < toolCalls.size && toolCalls[i].name in PARALLEL_SAFE_TOOLS) i++
                    val batch = toolCalls.subList(batchStart, i)
                    for (tc in batch) {
                        emit(AgentEvent.ToolExecuting(tc.id, tc.name))
                    }
                    // awaitAll preserves deferred order → stable tool_result ordering for the model.
                    val batchResults = coroutineScope {
                        batch.map { tc ->
                            async { executeToolCall(tc) }
                        }.awaitAll()
                    }
                    for ((tc, result) in batch.zip(batchResults)) {
                        emit(AgentEvent.ToolCompleted(tc.id, result))
                        toolResults.add(LlmContentBlock.ToolResult(tc.id, result.output, result.isError))
                    }
                } else {
                    val tc = toolCalls[i++]
                    emit(AgentEvent.ToolExecuting(tc.id, tc.name))
                    val result = executeToolCall(tc)
                    emit(AgentEvent.ToolCompleted(tc.id, result))
                    toolResults.add(LlmContentBlock.ToolResult(tc.id, result.output, result.isError))
                }
            }

            messages.add(LlmMessage(LlmRole.TOOL_RESULT, toolResults))
            emit(AgentEvent.IterationComplete)
        }

        emit(AgentEvent.Finished)
    }

    private suspend fun executeToolCall(tc: LlmContentBlock.ToolUse): ToolResult {
        val executor = toolRegistry.getExecutor(tc.name)
        return if (executor != null) {
            try {
                // Hook for a future human-in-the-loop confirmation UI.
                // YOLO mode auto-approves so gates never block the run.
                if (!awaitConfirmationIfNeeded()) {
                    return ToolResult(
                        tc.id,
                        "Tool '${tc.name}' denied by user confirmation.",
                        isError = true,
                    )
                }
                executor.execute(tc.id, tc.arguments)
            } catch (ce: CancellationException) {
                throw ce // respect user cancel
            } catch (t: Throwable) {
                android.util.Log.w("AgentLoop", "Tool ${tc.name} threw", t)
                ToolResult(
                    tc.id,
                    "Tool '${tc.name}' threw ${t.javaClass.simpleName}: ${t.message ?: "no message"}",
                    isError = true,
                )
            }
        } else {
            ToolResult(tc.id, "Unknown tool: ${tc.name}", isError = true)
        }
    }

    /**
     * Confirmation gate placeholder. Returns false only when a future UI denies
     * the tool. YOLO ([ToolExecutionContext.skipsConfirmation]) always approves.
     */
    private suspend fun awaitConfirmationIfNeeded(): Boolean {
        val ctx = currentCoroutineContext()[ToolExecutionContext]
        if (ctx?.skipsConfirmation == true) return true
        // No confirmation UI yet — allow. When added, emit a ConfirmationRequired
        // event here (include tool name/args) and suspend until the user responds.
        return true
    }
}

private class ToolCallAccumulator(
    val id: String,
    val name: String,
) {
    private val argBuilder = StringBuilder()

    fun appendArg(json: String) {
        argBuilder.append(json)
    }

    fun argumentJson(): String {
        val raw = argBuilder.toString()
        return raw.ifEmpty { "{}" }
    }
}
