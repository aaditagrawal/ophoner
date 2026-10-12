package dev.ophoner.ui.chat

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.ophoner.agent.AgentRunManager
import dev.ophoner.agent.AgentService
import dev.ophoner.agent.SkillLoader
import dev.ophoner.data.model.ContentBlock
import dev.ophoner.data.model.ConversationMode
import dev.ophoner.data.model.Message
import dev.ophoner.data.model.MessageRole
import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.repository.ConversationRepository
import dev.ophoner.data.repository.PendingShareText
import dev.ophoner.data.repository.SettingsRepository
import dev.ophoner.llm.LlmContentBlock
import dev.ophoner.llm.LlmMessage
import dev.ophoner.llm.LlmRole
import dev.ophoner.llm.models.ProviderModelResolver
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ToolCallUiState(
    val id: String,
    val name: String,
    val arguments: String = "",
    val result: String? = null,
    val isError: Boolean = false,
    val isExecuting: Boolean = false,
)

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val historyLoaded: Boolean = false,
    val hasOlderMessages: Boolean = false,
    val hasNewerMessages: Boolean = false,
    val loadingOlderMessages: Boolean = false,
    val restoredViewport: ChatViewport? = null,
    val streamingText: String = "",
    val activeToolCalls: List<ToolCallUiState> = emptyList(),
    val isAgentRunning: Boolean = false,
    val pauseRequested: Boolean = false,
    val isPaused: Boolean = false,
    val queuedFollowUps: Int = 0,
    val error: String? = null,
    // Non-fatal warnings surfaced to the user (e.g. tool-arg parse failures that
    // don't abort the agent but should be visible so the user knows something
    // went wrong). UI can render as a dismissible banner/snackbar.
    val banner: String? = null,
    val conversationId: String? = null,
    val providerConfig: ProviderConfig? = null,
    val hasWorkingDirectory: Boolean = false,
    val conversationMode: ConversationMode = ConversationMode.GENERAL,
    val scopedFolderName: String? = null,
    val scopedFolderUri: String? = null,
    /** One-shot composer prefill (e.g. ACTION_SEND share target). */
    val draftText: String? = null,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agentRunManager: AgentRunManager,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    private val pendingShareText: PendingShareText,
    private val skillLoader: SkillLoader,
    private val modelResolver: ProviderModelResolver,
    @param:ApplicationContext private val appContext: Context,
    private val viewportStore: ChatViewportStore,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    private val _uiState = MutableStateFlow(ChatUiState(conversationId = savedStateHandle["conversationId"], draftText = savedStateHandle["promptDraft"], banner = if (savedStateHandle.get<Boolean>("branchCreated") == true) "New branch. The original thread is unchanged; completed device actions are not undone." else null))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var messagesJob: Job? = null
    private data class HistoryWindow(val start: Int, val end: Int = Int.MAX_VALUE)
    private val historyWindow = MutableStateFlow<HistoryWindow?>(null)
    private var observedConversationId: String? = null
    private var submittingMessage = false
    private var providerRefreshJob: Job? = null
    private val conversationId: String? = savedStateHandle["conversationId"]
    private val navFolderUri: String? = savedStateHandle["folderUri"]
    private val navFolderName: String? = savedStateHandle["folderName"]

    init {
        viewModelScope.launch {
            savedStateHandle.getStateFlow<String?>("promptDraft", null).filterNotNull().collect { draft ->
                _uiState.update { it.copy(draftText = draft) }
            }
        }
        refreshProvider()
        viewModelScope.launch {
            // Load existing conversation and restore its folder scope (if any).
            if (conversationId != null) {
                val conv = conversationRepository.getConversation(conversationId)
                _uiState.update {
                    it.copy(
                        conversationId = conversationId,
                        conversationMode = conv?.mode ?: it.conversationMode,
                        scopedFolderName = conv?.scopedFolderName,
                        scopedFolderUri = conv?.scopedFolderUri,
                    )
                }
                observeConversation(conversationId)
            }

            // Apply folder scope from nav args for NEW conversations only (no conversationId).
            if (conversationId == null && navFolderUri != null) {
                _uiState.update { it.copy(
                    conversationMode = ConversationMode.FOLDER,
                    scopedFolderName = navFolderName,
                    scopedFolderUri = navFolderUri,
                ) }
            }

            // Prefill composer from share-target text on a fresh chat.
            if (conversationId == null) {
                pendingShareText.consume()?.let { shared ->
                    _uiState.update { it.copy(draftText = shared) }
                }
            }
        }
    }

    fun clearDraftText() {
        savedStateHandle["promptDraft"] = null
        _uiState.update { it.copy(draftText = null) }
    }

    fun branchPrompt(message: Message, edit: Boolean, onReady: (String, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val branch = conversationRepository.forkFromPrompt(message.conversationId, message.id, includePrompt = !edit)
                val draft = if (edit) message.content.filterIsInstance<ContentBlock.Text>().joinToString("\n\n") { it.text } else null
                onReady(branch.id, draft)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _uiState.update { it.copy(error = e.message ?: "Couldn't create branch.") } }
        }
    }

    fun pauseForInput() {
        _uiState.value.conversationId?.takeIf { agentRunManager.isRunActive(it) }?.let { agentRunManager.pauseRun(it, true) }
    }

    fun togglePause() {
        _uiState.value.conversationId?.let { agentRunManager.pauseRun(it, !_uiState.value.pauseRequested) }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        if (submittingMessage) { _uiState.update { it.copy(draftText = text, banner = "Wait for your previous message to finish saving.") }; return }
        val activeId = _uiState.value.conversationId ?: conversationId
        if (agentRunManager.isRunActive(activeId) && activeId != null) {
            submittingMessage = true
            viewModelScope.launch {
                try {
                    val accepted = agentRunManager.sendFollowUp(activeId, text)
                    if (accepted) agentRunManager.pauseRun(activeId, false)
                    _uiState.update { it.copy(
                        banner = if (accepted) "Follow-up saved for the next model turn." else "The run just finished. Send your follow-up to continue.",
                        draftText = if (accepted) it.draftText else text,
                    ) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { _uiState.update { it.copy(error = e.message ?: "Couldn't save follow-up.", draftText = text) } }
                finally { submittingMessage = false }
            }
            return
        }
        submittingMessage = true
        viewModelScope.launch {
            try {
                // Finish automatic selection before capturing the model for this run.
                providerRefreshJob?.join()
                val config = _uiState.value.providerConfig ?: run {
                    _uiState.update { it.copy(error = "No provider configured. Go to Settings to add one.") }
                    return@launch
                }
                if (config.modelId.isBlank()) {
                    _uiState.update { it.copy(error = "A model could not be selected. Check your connection or choose a model below the prompt.") }
                    refreshProvider()
                    return@launch
                }
                // Resolve scope before the run. init{} loads folder/conversation async from
                // nav args / DB; sending before that finishes must not drop folder scope
                // (wrong root for file tools + skills never loaded from the project folder).
                val state = _uiState.value
                val existingConvId = state.conversationId ?: conversationId
                val existingConv = if (
                    existingConvId != null &&
                    state.scopedFolderUri == null &&
                    navFolderUri == null
                ) {
                    conversationRepository.getConversation(existingConvId)
                } else {
                    null
                }
                val folderUri = state.scopedFolderUri ?: navFolderUri ?: existingConv?.scopedFolderUri
                val folderName = state.scopedFolderName ?: navFolderName ?: existingConv?.scopedFolderName
                val mode = when {
                    state.conversationMode == ConversationMode.FOLDER -> ConversationMode.FOLDER
                    existingConv?.mode == ConversationMode.FOLDER -> ConversationMode.FOLDER
                    folderUri != null && navFolderUri != null -> ConversationMode.FOLDER
                    else -> state.conversationMode
                }
                if (
                    folderUri != state.scopedFolderUri ||
                    folderName != state.scopedFolderName ||
                    mode != state.conversationMode
                ) {
                    _uiState.update {
                        it.copy(
                            conversationMode = mode,
                            scopedFolderUri = folderUri,
                            scopedFolderName = folderName,
                        )
                    }
                }
                val convId = existingConvId ?: run {
                    val conv = conversationRepository.createConversation(
                        providerConfigId = config.id,
                        mode = mode,
                        scopedFolderUri = folderUri,
                        scopedFolderName = folderName,
                    )
                    conv.id
                }
                if (state.conversationId != convId) {
                    _uiState.update { it.copy(conversationId = convId) }
                    observeConversation(convId)
                }
                // Add user message
                val msgIndex = conversationRepository.getMessageCount(convId)
                val userMessage = Message(
                    id = UUID.randomUUID().toString(),
                    conversationId = convId,
                    role = MessageRole.USER,
                    content = listOf(ContentBlock.Text(text)),
                    orderIndex = msgIndex,
                    createdAt = System.currentTimeMillis(),
                )
                _uiState.update { it.copy(
                    messages = it.messages + userMessage,
                    isAgentRunning = true,
                    error = null,
                    streamingText = "",
                    activeToolCalls = emptyList(),
                ) }
                conversationRepository.saveMessage(userMessage)

                // Auto-title on first message
                if (msgIndex == 0) {
                    val title = text.take(50).let { if (text.length > 50) "$it..." else it }
                    conversationRepository.updateTitle(convId, title)
                }

                // Skills load from the same root file tools will use this run:
                // conversation folder URI, else global settings working directory.
                val skillsRootUri = folderUri
                    ?: settingsRepository.observeWorkingDirectoryUri().firstOrNull()

                // Build LLM messages from conversation
                val baseSystemPrompt = settingsRepository.observeSystemPrompt().firstOrNull() ?: ""
                val folderScopePrompt = if (mode == ConversationMode.FOLDER && folderName != null) {
                    "\n\nIMPORTANT: You are operating in FOLDER MODE, scoped to the directory: $folderName\n" +
                        "- All file operations (read, write, list, delete, move) MUST be within this directory only.\n" +
                        "- Do NOT access files outside this directory.\n" +
                        "- Paths are relative to this directory root.\n" +
                        "- Focus your assistance on the files and content within this folder."
                } else {
                    ""
                }
                val skillsPrompt = try {
                    skillLoader.formatForSystemPrompt(skillsRootUri).orEmpty()
                } catch (t: Throwable) {
                    Log.w(TAG, "Skill load failed (continuing without skills)", t)
                    ""
                }
                val systemPrompt = baseSystemPrompt + folderScopePrompt + skillsPrompt
                val llmMessages = buildLlmMessages(systemPrompt, conversationRepository.getMessages(convId))

                AgentService.start(appContext)
                agentRunManager.startRun(
                    conversationId = convId,
                    conversationMessages = llmMessages,
                    config = config,
                    // Null → FileAccessManager falls back to settings working directory.
                    scopedFolderUri = folderUri,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(isAgentRunning = false, error = error.message ?: "Could not start the agent.") }
            } finally {
                submittingMessage = false
            }
        }
    }

    fun cancelAgent() {
        agentRunManager.cancelRun(_uiState.value.conversationId)
    }

    fun clearError() {
        agentRunManager.clearError(_uiState.value.conversationId)
        _uiState.update { it.copy(error = null) }
    }

    fun clearBanner() {
        savedStateHandle.remove<Boolean>("branchCreated")
        _uiState.update { it.copy(banner = null) }
    }

    fun refreshProvider(resolveAutomatic: Boolean = true) {
        if (providerRefreshJob?.isActive == true) {
            if (resolveAutomatic) return
            // An explicit composer selection must supersede an older catalog refresh.
            providerRefreshJob?.cancel()
        }
        providerRefreshJob = viewModelScope.launch {
            val providers = settingsRepository.observeProviders().firstOrNull() ?: emptyList()
            val activeId = settingsRepository.observeActiveProviderId().firstOrNull()
            val config = providers.find { it.id == activeId } ?: providers.firstOrNull()
            val hasDir = settingsRepository.observeWorkingDirectoryUri().firstOrNull() != null
            _uiState.update { it.copy(providerConfig = config, hasWorkingDirectory = hasDir) }
            if (config != null && resolveAutomatic) {
                try {
                    val resolved = modelResolver.resolve(config)
                    _uiState.update { it.copy(providerConfig = resolved) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    _uiState.update { it.copy(banner = error.message) }
                }
            }
        }
    }

    fun rememberViewport(viewport: ChatViewport) {
        _uiState.value.conversationId?.let { viewportStore.put(it, viewport) }
    }

    fun loadOlderMessages() {
        val id = observedConversationId ?: return
        val window = historyWindow.value ?: return
        if (!_uiState.value.hasOlderMessages || _uiState.value.loadingOlderMessages) return
        _uiState.update { it.copy(loadingOlderMessages = true) }
        viewModelScope.launch {
            try {
                val older = conversationRepository.pageStart(id, window.start)
                if (observedConversationId == id) historyWindow.compareAndSet(window, window.copy(start = older))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(loadingOlderMessages = false, error = "Could not load older messages.") }
            }
        }
    }

    fun loadNewerMessages() {
        val id = observedConversationId ?: return
        val window = historyWindow.value ?: return
        if (!_uiState.value.hasNewerMessages || _uiState.value.loadingOlderMessages) return
        _uiState.update { it.copy(loadingOlderMessages = true) }
        viewModelScope.launch {
            try {
                val end = conversationRepository.pageEnd(id, window.end)
                if (observedConversationId == id) historyWindow.compareAndSet(window, window.copy(end = end))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(loadingOlderMessages = false, error = "Could not load newer messages.") }
            }
        }
    }

    fun showLatestMessages() {
        val id = observedConversationId ?: return
        viewModelScope.launch {
            try {
                val start = conversationRepository.pageStart(id)
                if (observedConversationId == id) historyWindow.value = HistoryWindow(start)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(error = "Could not load latest messages.") }
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeConversation(conversationId: String) {
        if (observedConversationId == conversationId && messagesJob?.isActive == true) return
        messagesJob?.cancel()
        observedConversationId = conversationId
        historyWindow.value = null
        messagesJob = viewModelScope.launch {
            val viewport = viewportStore.get(conversationId)
            val latestStart = conversationRepository.pageStart(conversationId)
            historyWindow.value = if (viewport != null && !viewport.followLatest) {
                HistoryWindow(
                    conversationRepository.pageStart(conversationId, viewport.orderIndex),
                    conversationRepository.pageEnd(conversationId, viewport.orderIndex),
                )
            } else HistoryWindow(latestStart)
            _uiState.update { it.copy(restoredViewport = viewport) }
            var previousMessages: List<Message>? = null
            var previousCheckpoint: String? = null
            var visibleMessages = emptyList<Message>()
            var previousWindow: HistoryWindow? = null
            combine(
                historyWindow.filterNotNull().flatMapLatest { window ->
                    val source = if (window.end == Int.MAX_VALUE) {
                        conversationRepository.observeMessagesFrom(conversationId, window.start)
                    } else conversationRepository.observeMessagesRange(conversationId, window.start, window.end)
                    source.map { window to it }
                },
                agentRunManager.observeRun(conversationId),
            ) { window, run -> Triple(window.first, window.second, run) }.collect { (window, messages, run) ->
                if (window != historyWindow.value) return@collect
                val rowsChanged = messages !== previousMessages
                if (rowsChanged || previousCheckpoint != run?.checkpointMessageId) {
                    visibleMessages = if (run?.checkpointMessageId == null) messages else {
                        messages.filterNot { it.id == run.checkpointMessageId }
                    }
                    previousMessages = messages
                    previousCheckpoint = run?.checkpointMessageId
                }
                val hasOlder = if (previousWindow != window) {
                    conversationRepository.hasMessagesBefore(conversationId, window.start)
                } else _uiState.value.hasOlderMessages
                val hasNewer = if (window.end == Int.MAX_VALUE) false else if (rowsChanged || previousWindow != window) {
                    conversationRepository.hasMessagesAfter(conversationId, window.end)
                } else _uiState.value.hasNewerMessages
                if (!hasNewer && window.end != Int.MAX_VALUE) {
                    historyWindow.value = window.copy(end = Int.MAX_VALUE)
                    return@collect
                }
                previousWindow = window
                _uiState.update {
                    it.copy(
                        // The checkpoint is already represented by the live tool cards.
                        messages = visibleMessages,
                        historyLoaded = true,
                        hasOlderMessages = hasOlder,
                        hasNewerMessages = hasNewer,
                        loadingOlderMessages = false,
                        streamingText = run?.streamingText.orEmpty(),
                        activeToolCalls = run?.activeToolCalls?.map { toolCall ->
                            ToolCallUiState(
                                id = toolCall.id,
                                name = toolCall.name,
                                arguments = toolCall.arguments,
                                result = toolCall.result,
                                isError = toolCall.isError,
                                isExecuting = toolCall.isExecuting,
                            )
                        }.orEmpty(),
                        isAgentRunning = run?.isRunning == true,
                        pauseRequested = run?.pauseRequested == true,
                        isPaused = run?.isPaused == true,
                        queuedFollowUps = run?.queuedFollowUps ?: 0,
                        error = run?.error,
                    )
                }
            }
        }
    }

    private suspend fun buildLlmMessages(systemPrompt: String, messages: List<Message>): List<LlmMessage> = withContext(Dispatchers.Default) {
        val result = mutableListOf<LlmMessage>()

        if (systemPrompt.isNotEmpty()) {
            result.add(LlmMessage(LlmRole.SYSTEM, listOf(LlmContentBlock.Text(systemPrompt))))
        }

        for (msg in messages) {
            when (msg.role) {
                MessageRole.USER -> {
                    val blocks = msg.content.map { block ->
                        when (block) {
                            is ContentBlock.Text -> LlmContentBlock.Text(block.text)
                            else -> LlmContentBlock.Text("")
                        }
                    }
                    result.add(LlmMessage(LlmRole.USER, blocks))
                }
                MessageRole.ASSISTANT -> {
                    val blocks = msg.content.mapNotNull { block ->
                        when (block) {
                            is ContentBlock.Text -> LlmContentBlock.Text(block.text)
                            is ContentBlock.ToolUse -> {
                                try {
                                    val args = kotlinx.serialization.json.Json.parseToJsonElement(block.argumentsJson).jsonObject
                                    LlmContentBlock.ToolUse(block.id, block.name, args)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to parse stored tool args for ${block.name} (id=${block.id})", e)
                                    _uiState.update {
                                        it.copy(
                                            banner = "Could not replay tool call '${block.name}' from history — arguments were malformed. The assistant will not see this past call.",
                                        )
                                    }
                                    null
                                }
                            }
                            is ContentBlock.ToolResult -> LlmContentBlock.ToolResult(
                                block.toolUseId, block.output, block.isError
                            )
                            is ContentBlock.Stats -> null // UI-only metadata, never replayed
                        }
                    }
                    result.add(LlmMessage(LlmRole.ASSISTANT, blocks))
                }
                MessageRole.TOOL_RESULT -> {
                    val blocks = msg.content.filterIsInstance<ContentBlock.ToolResult>().map {
                        LlmContentBlock.ToolResult(it.toolUseId, it.output, it.isError)
                    }
                    if (blocks.isNotEmpty()) {
                        result.add(LlmMessage(LlmRole.TOOL_RESULT, blocks))
                    }
                }
                MessageRole.SYSTEM -> {} // Already handled
            }
        }

        result
    }
}
