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
import dev.ophoner.data.model.ContentBlock
import dev.ophoner.data.model.ConversationMode
import dev.ophoner.data.model.Message
import dev.ophoner.data.model.MessageRole
import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.repository.ConversationRepository
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
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agentRunManager: AgentRunManager,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
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
        }
    }

    fun sendMessage(text: String) {
        val config = _uiState.value.providerConfig ?: run {
            _uiState.update { it.copy(error = "No provider configured. Go to Settings to add one.") }
            return
        }

        viewModelScope.launch {
            // Create conversation if needed
            val state = _uiState.value
            val mode = state.conversationMode
            val folderUri = state.scopedFolderUri
            val folderName = state.scopedFolderName
            val convId = state.conversationId ?: run {
                val conv = conversationRepository.createConversation(
                    providerConfigId = config.id,
                    mode = mode,
                    scopedFolderUri = folderUri,
                    scopedFolderName = folderName,
                )
                _uiState.update { it.copy(conversationId = conv.id) }
                observeConversation(conv.id)
                conv.id
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

            // Build LLM messages from conversation
            val baseSystemPrompt = settingsRepository.observeSystemPrompt().firstOrNull() ?: ""
            val systemPrompt = if (mode == ConversationMode.FOLDER && folderName != null) {
                "$baseSystemPrompt\n\nIMPORTANT: You are operating in FOLDER MODE, scoped to the directory: $folderName\n" +
                "- All file operations (read, write, list, delete) MUST be within this directory only.\n" +
                "- Do NOT access files outside this directory.\n" +
                "- Paths are relative to this directory root.\n" +
                "- Focus your assistance on the files and content within this folder."
            } else {
                baseSystemPrompt
            }
            val llmMessages = buildLlmMessages(systemPrompt)

            AgentService.start(appContext)
            agentRunManager.startRun(convId, llmMessages, config)
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
