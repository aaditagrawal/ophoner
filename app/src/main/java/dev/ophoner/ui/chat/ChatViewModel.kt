package dev.ophoner.ui.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.ophoner.agent.AgentEvent
import dev.ophoner.agent.AgentLoop
import dev.ophoner.data.model.ContentBlock
import dev.ophoner.data.model.Conversation
import dev.ophoner.data.model.ConversationMode
import dev.ophoner.data.model.Message
import dev.ophoner.data.model.MessageRole
import dev.ophoner.data.model.ProviderConfig
import dev.ophoner.data.repository.ConversationRepository
import dev.ophoner.data.repository.SettingsRepository
import dev.ophoner.llm.LlmContentBlock
import dev.ophoner.llm.LlmMessage
import dev.ophoner.llm.LlmRole
import dev.ophoner.tools.ToolResult
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.Job
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
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agentLoop: AgentLoop,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var agentJob: Job? = null
    private val conversationId: String? = savedStateHandle["conversationId"]
    private val navMode: String? = savedStateHandle["mode"]
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

            // Load existing conversation and its mode
            if (conversationId != null) {
                val messages = conversationRepository.getMessages(conversationId)
                _uiState.update { it.copy(messages = messages, conversationId = conversationId) }
            }

            // Apply mode from nav args
            if (navMode == "folder" && navFolderUri != null) {
                _uiState.update { it.copy(
                    conversationMode = ConversationMode.FOLDER,
                    scopedFolderName = navFolderName,
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
            val mode = _uiState.value.conversationMode
            val convId = _uiState.value.conversationId ?: run {
                val conv = conversationRepository.createConversation(
                    providerConfigId = config.id,
                    mode = mode,
                    scopedFolderUri = navFolderUri,
                    scopedFolderName = navFolderName,
                )
                _uiState.update { it.copy(conversationId = conv.id) }
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
            val systemPrompt = if (mode == ConversationMode.FOLDER && navFolderName != null) {
                "$baseSystemPrompt\n\nIMPORTANT: You are operating in FOLDER MODE, scoped to the directory: $navFolderName\n" +
                "- All file operations (read, write, list, delete) MUST be within this directory only.\n" +
                "- Do NOT access files outside this directory.\n" +
                "- Paths are relative to this directory root.\n" +
                "- Focus your assistance on the files and content within this folder."
            } else {
                baseSystemPrompt
            }
            val llmMessages = buildLlmMessages(systemPrompt)

            // Run agent loop
            agentJob = viewModelScope.launch {
                var streamedText = StringBuilder()

                fun flushIteration() {
                    val text = streamedText.toString()
                    val toolCalls = _uiState.value.activeToolCalls.toList()
                    if (text.isEmpty() && toolCalls.isEmpty()) return

                    val content = mutableListOf<ContentBlock>()
                    if (text.isNotEmpty()) content.add(ContentBlock.Text(text))
                    toolCalls.forEach { tc ->
                        content.add(ContentBlock.ToolUse(tc.id, tc.name, tc.arguments))
                        if (tc.result != null) {
                            content.add(ContentBlock.ToolResult(tc.id, tc.result, tc.isError))
                        }
                    }

                    val idx = _uiState.value.messages.size
                    val msg = Message(
                        id = UUID.randomUUID().toString(),
                        conversationId = convId,
                        role = MessageRole.ASSISTANT,
                        content = content,
                        orderIndex = idx,
                        createdAt = System.currentTimeMillis(),
                    )

                    viewModelScope.launch {
                        conversationRepository.saveMessage(msg)
                    }

                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages + msg,
                            streamingText = "",
                            activeToolCalls = emptyList(),
                        )
                    }
                    streamedText = StringBuilder()
                }

                agentLoop.run(llmMessages, config).collect { event ->
                    when (event) {
                        is AgentEvent.TextDelta -> {
                            streamedText.append(event.text)
                            _uiState.update { it.copy(streamingText = streamedText.toString()) }
                        }
                        is AgentEvent.ToolCallStarted -> {
                            _uiState.update { state ->
                                state.copy(activeToolCalls = state.activeToolCalls + ToolCallUiState(
                                    id = event.id,
                                    name = event.name,
                                ))
                            }
                        }
                        is AgentEvent.ToolCallArgDelta -> {
                            _uiState.update { state ->
                                val existing = state.activeToolCalls.any { it.id == event.id }
                                if (existing) {
                                    state.copy(activeToolCalls = state.activeToolCalls.map { tc ->
                                        if (tc.id == event.id) tc.copy(arguments = tc.arguments + event.json)
                                        else tc
                                    })
                                } else {
                                    // Delta for unknown id — route to last tool call
                                    val last = state.activeToolCalls.lastOrNull()
                                    if (last != null) {
                                        state.copy(activeToolCalls = state.activeToolCalls.map { tc ->
                                            if (tc.id == last.id) tc.copy(arguments = tc.arguments + event.json)
                                            else tc
                                        })
                                    } else state
                                }
                            }
                        }
                        is AgentEvent.ToolExecuting -> {
                            _uiState.update { state ->
                                state.copy(activeToolCalls = state.activeToolCalls.map { tc ->
                                    if (tc.id == event.id) tc.copy(isExecuting = true)
                                    else tc
                                })
                            }
                        }
                        is AgentEvent.ToolCompleted -> {
                            _uiState.update { state ->
                                state.copy(activeToolCalls = state.activeToolCalls.map { tc ->
                                    if (tc.id == event.id) tc.copy(
                                        isExecuting = false,
                                        result = event.result.output,
                                        isError = event.result.isError,
                                    ) else tc
                                })
                            }
                        }
                        is AgentEvent.IterationComplete -> {
                            // Flush current iteration into a saved message, reset for next
                            flushIteration()
                        }
                        is AgentEvent.Error -> {
                            _uiState.update { it.copy(error = event.message) }
                        }
                        is AgentEvent.Finished -> {
                            // Flush any remaining content from the final iteration
                            flushIteration()
                            _uiState.update { it.copy(isAgentRunning = false) }
                        }
                    }
                }
            }
        }
    }

    fun cancelAgent() {
        agentJob?.cancel()
        _uiState.update { it.copy(isAgentRunning = false, streamingText = "", activeToolCalls = emptyList()) }
    }

    fun clearError() {
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
