package dev.ophoner.agent

import android.util.Log
import dev.ophoner.data.model.ContentBlock
import dev.ophoner.data.model.Message
import dev.ophoner.data.model.MessageRole
import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.repository.ConversationRepository
import dev.ophoner.data.repository.SettingsRepository
import dev.ophoner.di.ApplicationScope
import dev.ophoner.di.DefaultDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import dev.ophoner.llm.LlmMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
    val stopReason: AgentStopReason? = null,
    val checkpointMessageId: String? = null,
    val retryAttempt: Int = 0,
    val retryDelayMillis: Long? = null,
    val pauseRequested: Boolean = false,
    val isPaused: Boolean = false,
    val queuedFollowUps: Int = 0,
    val lastOutput: String = "",
)

@Singleton
class AgentRunManager @Inject constructor(
    private val agentLoop: AgentLoop,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
    @param:DefaultDispatcher private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _runs = MutableStateFlow<Map<String, AgentRunState>>(emptyMap())
    val runs = _runs.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val controls = ConcurrentHashMap<String, RunControl>()
    private val checkpoints = ConcurrentHashMap<String, suspend () -> Unit>()

    fun pauseRun(id: String, pause: Boolean) {
        controls[id]?.requestPause(pause)
        updateRun(id) { it.copy(pauseRequested = pause) }
    }

    suspend fun sendFollowUp(id: String, text: String): Boolean = applicationScope.async(Dispatchers.Main.immediate) {
        require(text.isNotBlank() && text.length <= 50_000) { "Follow-up must contain 1–50,000 characters." }
        val control = controls[id] ?: return@async false
        control.submit(text.trim()) {
            checkpoints[id]?.invoke()
            conversationRepository.saveMessage(Message(
                id = UUID.randomUUID().toString(), conversationId = id, role = MessageRole.USER,
                content = listOf(ContentBlock.Text(text.trim())), orderIndex = 0, createdAt = System.currentTimeMillis(),
            ))
        }
    }.await()


    fun observeRun(conversationId: String): Flow<AgentRunState?> =
        runs.map { it[conversationId] }.distinctUntilChanged()

    fun startRun(
        conversationId: String,
        conversationMessages: List<LlmMessage>,
        config: ProviderConfig,
        scopedFolderUri: String? = null,
    ) {
        val existingJob = jobs[conversationId]
        if (existingJob != null && !existingJob.isCompleted) return
        if (existingJob != null) jobs.remove(conversationId, existingJob)

        _runs.update {
            it + (conversationId to AgentRunState(
                conversationId = conversationId,
                isRunning = true,
            ))
        }

        val control = RunControl()
        lateinit var job: Job
        // Serialize accumulator and timer access; provider/tool work runs upstream on Default/IO.
        job = applicationScope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            val pauseObserver = launch { control.atPauseBoundary.collect { paused -> updateRun(conversationId) { it.copy(isPaused = paused) } } }
            val queueObserver = launch { control.pendingCount.collect { count -> updateRun(conversationId) { it.copy(queuedFollowUps = count) } } }
            val checkpointMutex = Mutex()
            val streamedText = StringBuilder()
            var streamStartedAt: Long? = null
            var inputTokens: Long? = null
            var outputTokens: Long? = null
            var cacheReadInputTokens: Long? = null
            var cacheCreationInputTokens: Long? = null
            var coalesceJob: Job? = null
            var streamingDirty = false
            val argumentBuilders = linkedMapOf<String, StringBuilder>()
            val dirtyArguments = mutableSetOf<String>()

            fun publishStreamingText() {
                if (!streamingDirty && dirtyArguments.isEmpty()) return
                val textSnapshot = if (streamingDirty) streamedText.toString() else null
                val argumentSnapshots = dirtyArguments.associateWith { argumentBuilders.getValue(it).toString() }
                streamingDirty = false
                dirtyArguments.clear()
                updateRun(conversationId) { state ->
                    state.copy(
                        streamingText = textSnapshot ?: state.streamingText,
                        activeToolCalls = if (argumentSnapshots.isEmpty()) state.activeToolCalls else {
                            state.activeToolCalls.map { tool ->
                                argumentSnapshots[tool.id]?.let { tool.copy(arguments = it) } ?: tool
                            }
                        },
                    )
                }
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

            var checkpointId: String? = null
            var checkpointOrder: Int? = null
            var checkpointCreatedAt: Long? = null

            suspend fun checkpointIteration(finalize: Boolean = false) = checkpointMutex.withLock {
                flushPendingStreamingText()
                val state = _runs.value[conversationId] ?: return@withLock
                val text = streamedText.toString()
                if (text.isEmpty() && state.activeToolCalls.isEmpty() && checkpointId == null) return@withLock

                val startedAtSnapshot = streamStartedAt
                val inputTokensSnapshot = inputTokens
                val outputTokensSnapshot = outputTokens
                val cacheReadSnapshot = cacheReadInputTokens
                val cacheCreationSnapshot = cacheCreationInputTokens
                val content = withContext(computationDispatcher) {
                    val content = mutableListOf<ContentBlock>()
                    if (text.isNotEmpty()) content.add(ContentBlock.Text(text))
                    state.activeToolCalls.forEach { toolCall ->
                        val arguments = toolCall.arguments.ifBlank { "{}" }
                        val validArguments = runCatching { Json.parseToJsonElement(arguments) is JsonObject }.getOrDefault(false)
                        if (!validArguments) {
                            content.add(ContentBlock.Text("Tool call '${toolCall.name}' had incomplete arguments and was not executed."))
                            return@forEach
                        }
                        content.add(ContentBlock.ToolUse(toolCall.id, toolCall.name, arguments))
                        content.add(ContentBlock.ToolResult(
                            toolCall.id,
                            toolCall.result ?: if (toolCall.isExecuting) {
                                "Execution was interrupted before its result was recorded. Outcome unknown: verify the actual state before retrying this action."
                            } else {
                                "This tool call was not executed."
                            },
                            if (toolCall.result == null) true else toolCall.isError,
                        ))
                    }

                    val durationMs = startedAtSnapshot?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) } ?: 0
                    if ((text.isNotEmpty() && durationMs > 0) || inputTokensSnapshot != null || outputTokensSnapshot != null) {
                        content.add(ContentBlock.Stats(
                            outputChars = text.length,
                            durationMs = durationMs,
                            inputTokens = inputTokensSnapshot,
                            outputTokens = outputTokensSnapshot,
                            cacheReadInputTokens = cacheReadSnapshot,
                            cacheCreationInputTokens = cacheCreationSnapshot,
                        ))
                    }
                    content
                }
                val messageId = checkpointId ?: UUID.randomUUID().toString().also { checkpointId = it }
                val order = checkpointOrder ?: conversationRepository.getMessageCount(conversationId).also { checkpointOrder = it }
                val createdAt = checkpointCreatedAt ?: System.currentTimeMillis().also { checkpointCreatedAt = it }
                updateRun(conversationId) { it.copy(checkpointMessageId = messageId) }
                conversationRepository.saveMessage(
                    Message(
                        id = messageId,
                        conversationId = conversationId,
                        role = MessageRole.ASSISTANT,
                        content = content,
                        orderIndex = order,
                        createdAt = createdAt,
                    ),
                )

                if (!finalize) return@withLock
                checkpointId = null
                checkpointOrder = null
                checkpointCreatedAt = null
                streamStartedAt = null
                inputTokens = null
                outputTokens = null
                cacheReadInputTokens = null
                cacheCreationInputTokens = null
                streamedText.clear()
                argumentBuilders.clear()
                dirtyArguments.clear()
                _runs.update { runs ->
                    runs + (conversationId to (runs[conversationId] ?: state).copy(
                        lastOutput = text.ifBlank { state.lastOutput }.takeLast(4000),
                        streamingText = "",
                        activeToolCalls = emptyList(),
                        checkpointMessageId = null,
                        retryAttempt = 0,
                        retryDelayMillis = null,
                    ))
                }
            }

            checkpoints[conversationId] = { checkpointIteration() }
            try {
                // Snapshot YOLO at run start so mid-run toggles don't flip soft gates.
                val yoloMode = settingsRepository.observeYoloMode().first()
                agentLoop.run(
                    conversationMessages = conversationMessages,
                    config = config,
                    scopedFolderUri = scopedFolderUri,
                    yoloMode = yoloMode,
                    requireDurableCheckpoints = true,
                    cacheKey = conversationId,
                    control = control,
                ).collect { event ->
                    when (event) {
                        is AgentEvent.TextDelta -> {
                            if (streamStartedAt == null) streamStartedAt = System.currentTimeMillis()
                            streamedText.append(event.text)
                            streamingDirty = true
                            scheduleStreamingTextPublish()
                        }
                        is AgentEvent.ToolCallStarted -> {
                            if (streamStartedAt == null) streamStartedAt = System.currentTimeMillis()
                            flushPendingStreamingText()
                            argumentBuilders[event.id] = StringBuilder()
                            updateRun(conversationId) {
                                it.copy(activeToolCalls = it.activeToolCalls + ActiveToolCallState(
                                    id = event.id,
                                    name = event.name,
                                ))
                            }
                        }
                        is AgentEvent.ToolCallArgDelta -> {
                            val id = event.id.takeIf { it in argumentBuilders }
                                ?: argumentBuilders.keys.lastOrNull()
                            if (id != null) {
                                argumentBuilders.getValue(id).append(event.json)
                                dirtyArguments.add(id)
                                scheduleStreamingTextPublish()
                            }
                        }
                        is AgentEvent.ToolExecuting -> {
                            flushPendingStreamingText()
                            updateRun(conversationId) {
                                it.copy(activeToolCalls = it.activeToolCalls.map { toolCall ->
                                    if (toolCall.id == event.id) toolCall.copy(isExecuting = true) else toolCall
                                })
                            }
                            try {
                                checkpointIteration()
                                event.acknowledged?.complete(Unit)
                            } catch (t: Throwable) {
                                event.acknowledged?.completeExceptionally(t)
                                throw t
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
                            try {
                                checkpointIteration()
                                event.acknowledged?.complete(Unit)
                            } catch (t: Throwable) {
                                event.acknowledged?.completeExceptionally(t)
                                throw t
                            }
                        }
                        is AgentEvent.Usage -> {
                            // Providers may report input and output counts in separate chunks.
                            event.inputTokens?.let { inputTokens = it }
                            event.outputTokens?.let { outputTokens = it }
                            event.cacheReadInputTokens?.let { cacheReadInputTokens = it }
                            event.cacheCreationInputTokens?.let { cacheCreationInputTokens = it }
                        }
                        is AgentEvent.Retrying -> updateRun(conversationId) {
                            it.copy(retryAttempt = event.attempt, retryDelayMillis = event.delayMillis)
                        }
                        is AgentEvent.ResponseDiscarded -> {
                            // A follow-up may have checkpointed a partial response. Clear that
                            // row on retry while preserving its position before the follow-up.
                            checkpointMutex.withLock {
                                checkpointId?.let { id ->
                                    conversationRepository.saveMessage(Message(id, conversationId, MessageRole.ASSISTANT,
                                        emptyList(), checkpointOrder ?: 0, checkpointCreatedAt ?: System.currentTimeMillis()))
                                }
                            }
                            coalesceJob?.cancel()
                            coalesceJob = null
                            streamedText.clear()
                            argumentBuilders.clear()
                            dirtyArguments.clear()
                            streamingDirty = false
                            streamStartedAt = null
                            inputTokens = null
                            outputTokens = null
                            cacheReadInputTokens = null
                            cacheCreationInputTokens = null
                            updateRun(conversationId) { it.copy(streamingText = "", activeToolCalls = emptyList()) }
                        }
                        is AgentEvent.RunStopped -> updateRun(conversationId) { it.copy(stopReason = event.reason) }
                        is AgentEvent.IterationComplete -> checkpointIteration(finalize = true)
                        is AgentEvent.Error -> {
                            updateRun(conversationId) { it.copy(error = event.message) }
                        }
                        is AgentEvent.Finished -> {
                            checkpointIteration(finalize = true)
                            updateRun(conversationId) { it.copy(isRunning = false) }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                // Preserve completed actions and unknown outcomes even when the user stops.
                withContext(NonCancellable) {
                    try { withTimeout(5_000) { checkpointIteration(finalize = true) } }
                    catch (t: Throwable) { Log.e("AgentRunManager", "Could not persist interrupted run", t) }
                }
                updateRun(conversationId) {
                    it.copy(
                        streamingText = "",
                        activeToolCalls = emptyList(),
                        isRunning = false,
                        checkpointMessageId = null,
                        stopReason = AgentStopReason.CANCELLED,
                    )
                }
                throw ce
            } catch (t: Throwable) {
                withContext(NonCancellable) {
                    try { withTimeout(5_000) { checkpointIteration(finalize = true) } }
                    catch (saveError: Throwable) { Log.e("AgentRunManager", "Could not persist failed run", saveError) }
                }
                Log.e("AgentRunManager", "Agent run failed", t)
                updateRun(conversationId) {
                    it.copy(
                        isRunning = false,
                        error = "[Agent error: ${t.javaClass.simpleName} - ${t.message ?: "unknown"}]",
                        stopReason = AgentStopReason.ERROR,
                    )
                }
            } finally {
                withContext(NonCancellable) { control.close() }
                pauseObserver.cancel()
                queueObserver.cancel()
                checkpoints.remove(conversationId)
                controls.remove(conversationId, control)
                jobs.remove(conversationId, job)
                updateRun(conversationId) { it.copy(isRunning = false, checkpointMessageId = null) }
            }
        }

        val previousJob = jobs.putIfAbsent(conversationId, job)
        if (previousJob != null && !previousJob.isCompleted) {
            job.cancel()
            return
        }
        if (previousJob != null) {
            if (!jobs.replace(conversationId, previousJob, job)) {
                job.cancel()
                return
            }
        }
        controls[conversationId] = control
        job.start()
    }

    fun isRunActive(conversationId: String?): Boolean =
        conversationId != null && jobs[conversationId]?.isCompleted == false

    fun cancelRun(conversationId: String?) {
        if (conversationId == null) return
        jobs[conversationId]?.cancel()
    }

    fun cancelAll() { jobs.keys.toList().forEach(::cancelRun) }

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
