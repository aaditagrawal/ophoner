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
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
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
    val streamingText: String = "",
    val activeToolCalls: List<ToolCallUiState> = emptyList(),
    val isAgentRunning: Boolean = false,
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
    @param:ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var messagesJob: Job? = null
    private var runStateJob: Job? = null
    private val conversationId: String? = savedStateHandle["conversationId"]
    private val navFolderUri: String? = savedStateHandle["folderUri"]
    private val navFolderName: String? = savedStateHandle["folderName"]

    init {
        viewModelScope.launch {
            // Load active provider
            val providers = settingsRepository.observeProviders().firstOrNull() ?: emptyList()
            val activeId = settingsRepository.observeActiveProviderId().firstOrNull()
            val config = providers.find { it.id == activeId } ?: providers.firstOrNull()
            val hasDir = settingsRepository.observeWorkingDirectoryUri().firstOrNull() != null

            _uiState.update { it.copy(providerConfig = config, hasWorkingDirectory = hasDir) }

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
        _uiState.update { it.copy(draftText = null) }
    }

    fun sendMessage(text: String) {
        val config = _uiState.value.providerConfig ?: run {
            _uiState.update { it.copy(error = "No provider configured. Go to Settings to add one.") }
            return
        }

        viewModelScope.launch {
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
            // Hydrate history if observe hasn't emitted yet (same init race as folder scope).
            if (existingConvId != null && _uiState.value.messages.isEmpty()) {
                val loaded = conversationRepository.getMessages(convId)
                if (loaded.isNotEmpty()) {
                    _uiState.update { it.copy(messages = loaded) }
                }
            }

            // Add user message
            val msgIndex = _uiState.value.messages.size
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
            val llmMessages = buildLlmMessages(systemPrompt)

            AgentService.start(appContext)
            agentRunManager.startRun(
                conversationId = convId,
                conversationMessages = llmMessages,
                config = config,
                // Null → FileAccessManager falls back to settings working directory.
                scopedFolderUri = folderUri,
            )
        }
    }

    fun cancelAgent() {
        agentRunManager.cancelRun(_uiState.value.conversationId)
        _uiState.update { it.copy(isAgentRunning = false, streamingText = "", activeToolCalls = emptyList()) }
    }

    fun clearError() {
        agentRunManager.clearError(_uiState.value.conversationId)
        _uiState.update { it.copy(error = null) }
    }

    fun clearBanner() {
        _uiState.update { it.copy(banner = null) }
    }

    fun refreshProvider() {
        viewModelScope.launch {
            val providers = settingsRepository.observeProviders().firstOrNull() ?: emptyList()
            val activeId = settingsRepository.observeActiveProviderId().firstOrNull()
            val config = providers.find { it.id == activeId } ?: providers.firstOrNull()
            val hasDir = settingsRepository.observeWorkingDirectoryUri().firstOrNull() != null
            _uiState.update { it.copy(providerConfig = config, hasWorkingDirectory = hasDir) }
        }
    }

    private fun observeConversation(conversationId: String) {
        if (_uiState.value.conversationId == conversationId &&
            messagesJob?.isActive == true &&
            runStateJob?.isActive == true
        ) return

        messagesJob?.cancel()
        runStateJob?.cancel()

        messagesJob = viewModelScope.launch {
            conversationRepository.observeMessages(conversationId).collectLatest { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }

        runStateJob = viewModelScope.launch {
            agentRunManager.observeRun(conversationId).collectLatest { run ->
                _uiState.update {
                    it.copy(
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
                        error = run?.error,
                    )
                }
            }
        }
    }

    private fun buildLlmMessages(systemPrompt: String): List<LlmMessage> {
        val result = mutableListOf<LlmMessage>()

        if (systemPrompt.isNotEmpty()) {
            result.add(LlmMessage(LlmRole.SYSTEM, listOf(LlmContentBlock.Text(systemPrompt))))
        }

        for (msg in _uiState.value.messages) {
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

        return result
    }
}
