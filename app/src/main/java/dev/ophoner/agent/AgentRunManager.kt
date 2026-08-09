package dev.ophoner.agent

import android.util.Log
import dev.ophoner.data.model.ContentBlock
import dev.ophoner.data.model.Message
import dev.ophoner.data.model.MessageRole
import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.repository.ConversationRepository
import dev.ophoner.data.repository.SettingsRepository
import dev.ophoner.di.ApplicationScope
import dev.ophoner.llm.LlmMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TEXT_DELTA_COALESCE_MS = 50L

data class ActiveToolCallState(
    val id: String,
    val name: String,
    val arguments: String = "",
    val result: String? = null,
    val isError: Boolean = false,
    val isExecuting: Boolean = false,
)

data class AgentRunState(
    val conversationId: String,
    val streamingText: String = "",
    val activeToolCalls: List<ActiveToolCallState> = emptyList(),
    val isRunning: Boolean = false,
    val error: String? = null,
)

@Singleton
class AgentRunManager @Inject constructor(
    private val agentLoop: AgentLoop,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) {
    private val _runs = MutableStateFlow<Map<String, AgentRunState>>(emptyMap())
    val runs = _runs.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()

    fun observeRun(conversationId: String): Flow<AgentRunState?> =
        runs.map { it[conversationId] }.distinctUntilChanged()

    fun startRun(
        conversationId: String,
        conversationMessages: List<LlmMessage>,
        config: ProviderConfig,
        scopedFolderUri: String? = null,
    ) {
        val existingJob = jobs[conversationId]
        if (existingJob?.isActive == true) return
        if (existingJob != null) jobs.remove(conversationId, existingJob)

        _runs.update {
            it + (conversationId to AgentRunState(
                conversationId = conversationId,
                isRunning = true,
            ))
        }

        lateinit var job: Job
        job = applicationScope.launch(start = CoroutineStart.LAZY) {
            val streamedText = StringBuilder()
            var streamStartedAt: Long? = null
            var coalesceJob: Job? = null
            var streamingDirty = false

            fun publishStreamingText() {
                if (!streamingDirty) return
                streamingDirty = false
                val snapshot = streamedText.toString()
                updateRun(conversationId) { it.copy(streamingText = snapshot) }
            }

            fun scheduleStreamingTextPublish() {
                // Leading-edge window: first dirty delta starts a ~50ms timer; further
                // deltas in that window only mutate streamedText (no extra StateFlow churn).
                if (coalesceJob?.isActive == true) return
                coalesceJob = launch {
                    delay(TEXT_DELTA_COALESCE_MS)
                    coalesceJob = null
                    publishStreamingText()
                }
            }

            fun flushPendingStreamingText() {
                coalesceJob?.cancel()
                coalesceJob = null
                publishStreamingText()
            }

            suspend fun flushIteration() {
                flushPendingStreamingText()
                val state = _runs.value[conversationId] ?: return
                val text = streamedText.toString()
                if (text.isEmpty() && state.activeToolCalls.isEmpty()) return

                val content = mutableListOf<ContentBlock>()
                if (text.isNotEmpty()) content.add(ContentBlock.Text(text))
                state.activeToolCalls.forEach { toolCall ->
                    content.add(ContentBlock.ToolUse(toolCall.id, toolCall.name, toolCall.arguments))
                    if (toolCall.result != null) {
                        content.add(ContentBlock.ToolResult(toolCall.id, toolCall.result, toolCall.isError))
                    }
                }

                streamStartedAt?.let { startedAt ->
                    val durationMs = System.currentTimeMillis() - startedAt
                    if (text.isNotEmpty() && durationMs > 0) {
                        content.add(ContentBlock.Stats(outputChars = text.length, durationMs = durationMs))
                    }
                }
                streamStartedAt = null

                conversationRepository.saveMessage(
                    Message(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        role = MessageRole.ASSISTANT,
                        content = content,
                        orderIndex = conversationRepository.getMessageCount(conversationId),
                        createdAt = System.currentTimeMillis(),
                    ),
                )

                streamedText.clear()
                _runs.update { runs ->
                    runs + (conversationId to state.copy(
                        streamingText = "",
                        activeToolCalls = emptyList(),
                    ))
                }
            }

            try {
                // Snapshot YOLO at run start so mid-run toggles don't flip soft gates.
                val yoloMode = settingsRepository.observeYoloMode().first()
                agentLoop.run(
                    conversationMessages = conversationMessages,
                    config = config,
                    scopedFolderUri = scopedFolderUri,
                    yoloMode = yoloMode,
                ).collect { event ->
                    when (event) {
                        is AgentEvent.TextDelta -> {
                            if (streamStartedAt == null) streamStartedAt = System.currentTimeMillis()
                            streamedText.append(event.text)
                            streamingDirty = true
                            scheduleStreamingTextPublish()
                        }
                        is AgentEvent.ToolCallStarted -> {
                            flushPendingStreamingText()
                            updateRun(conversationId) {
                                it.copy(activeToolCalls = it.activeToolCalls + ActiveToolCallState(
                                    id = event.id,
                                    name = event.name,
                                ))
                            }
                        }
                        is AgentEvent.ToolCallArgDelta -> {
                            updateRun(conversationId) {
                                val existing = it.activeToolCalls.any { toolCall -> toolCall.id == event.id }
                                if (existing) {
                                    it.copy(activeToolCalls = it.activeToolCalls.map { toolCall ->
                                        if (toolCall.id == event.id) {
                                            toolCall.copy(arguments = toolCall.arguments + event.json)
                                        } else {
                                            toolCall
                                        }
                                    })
                                } else {
                                    val last = it.activeToolCalls.lastOrNull()
                                    if (last == null) {
                                        it
                                    } else {
                                        it.copy(activeToolCalls = it.activeToolCalls.map { toolCall ->
                                            if (toolCall.id == last.id) {
                                                toolCall.copy(arguments = toolCall.arguments + event.json)
                                            } else {
                                                toolCall
                                            }
                                        })
                                    }
                                }
                            }
                        }
                        is AgentEvent.ToolExecuting -> {
                            updateRun(conversationId) {
                                it.copy(activeToolCalls = it.activeToolCalls.map { toolCall ->
                                    if (toolCall.id == event.id) toolCall.copy(isExecuting = true) else toolCall
                                })
                            }
                        }
                        is AgentEvent.ToolCompleted -> {
                            updateRun(conversationId) {
                                it.copy(activeToolCalls = it.activeToolCalls.map { toolCall ->
                                    if (toolCall.id == event.id) {
                                        toolCall.copy(
                                            isExecuting = false,
                                            result = event.result.output,
                                            isError = event.result.isError,
                                        )
                                    } else {
                                        toolCall
                                    }
                                })
                            }
                        }
                        is AgentEvent.IterationComplete -> flushIteration()
                        is AgentEvent.Error -> {
                            updateRun(conversationId) { it.copy(error = event.message) }
                        }
                        is AgentEvent.Finished -> {
                            flushIteration()
                            updateRun(conversationId) { it.copy(isRunning = false) }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                updateRun(conversationId) {
                    it.copy(
                        streamingText = "",
                        activeToolCalls = emptyList(),
                        isRunning = false,
                    )
                }
                throw ce
            } catch (t: Throwable) {
                Log.e("AgentRunManager", "Agent run failed", t)
                updateRun(conversationId) {
                    it.copy(
                        isRunning = false,
                        error = "[Agent error: ${t.javaClass.simpleName} - ${t.message ?: "unknown"}]",
                    )
                }
            } finally {
                jobs.remove(conversationId, job)
                updateRun(conversationId) { it.copy(isRunning = false) }
            }
        }

        val previousJob = jobs.putIfAbsent(conversationId, job)
        if (previousJob?.isActive == true) {
            job.cancel()
            return
        }
        if (previousJob != null) {
            if (!jobs.replace(conversationId, previousJob, job)) {
                job.cancel()
                return
            }
        }
        job.start()
    }

    fun cancelRun(conversationId: String?) {
        if (conversationId == null) return
        jobs.remove(conversationId)?.cancel()
        updateRun(conversationId) {
            it.copy(
                streamingText = "",
                activeToolCalls = emptyList(),
                isRunning = false,
            )
        }
    }

    fun clearError(conversationId: String?) {
        if (conversationId == null) return
        updateRun(conversationId) { it.copy(error = null) }
    }

    private fun updateRun(
        conversationId: String,
        transform: (AgentRunState) -> AgentRunState,
    ) {
        _runs.update { runs ->
            val current = runs[conversationId] ?: AgentRunState(conversationId)
            runs + (conversationId to transform(current))
        }
    }
}
