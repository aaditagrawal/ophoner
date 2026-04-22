package dev.ophoner.ui.chat

import kotlinx.serialization.json.jsonObject
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import dev.ophoner.ui.theme.GeistMono
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ophoner.data.model.ContentBlock
import dev.ophoner.data.model.MessageRole
import dev.ophoner.ui.theme.AccentAmber
import dev.ophoner.ui.theme.AccentGreen
import dev.ophoner.ui.theme.AccentRed
import dev.ophoner.ui.theme.AssistantBubble
import dev.ophoner.ui.theme.ToolCardBg
import dev.ophoner.ui.theme.ToolCardBorder
import dev.ophoner.ui.theme.UserBubble

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    onOpenConversations: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Auto-scroll to bottom on new content
    LaunchedEffect(uiState.messages.size, uiState.streamingText) {
        if (uiState.messages.isNotEmpty() || uiState.streamingText.isNotEmpty()) {
            listState.animateScrollToItem(maxOf(0, listState.layoutInfo.totalItemsCount - 1))
        }
    }

    // Show errors
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Refresh provider when returning from settings
    LaunchedEffect(Unit) {
        viewModel.refreshProvider()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    val folderName = uiState.scopedFolderName
                    if (uiState.conversationMode == dev.ophoner.data.model.ConversationMode.FOLDER &&
                        folderName != null
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = folderName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenConversations) {
                        Icon(Icons.Default.Menu, contentDescription = "Conversations")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding(),
        ) {
            // Messages
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Empty state
                if (uiState.messages.isEmpty() && !uiState.isAgentRunning) {
                    item {
                        EmptyState()
                    }
                }

                // Messages
                items(uiState.messages, key = { it.id }) { message ->
                    MessageItem(message)
                }

                // Streaming response
                if (uiState.streamingText.isNotEmpty() || uiState.activeToolCalls.isNotEmpty()) {
                    item {
                        StreamingResponse(
                            text = uiState.streamingText,
                            toolCalls = uiState.activeToolCalls,
                        )
                    }
                }

                // Thinking indicator
                if (uiState.isAgentRunning && uiState.streamingText.isEmpty() && uiState.activeToolCalls.isEmpty()) {
                    item {
                        ThinkingIndicator()
                    }
                }
            }

            // Floating input bar at the bottom
            InputBar(
                isAgentRunning = uiState.isAgentRunning,
                hasProvider = uiState.providerConfig != null,
                onSend = { viewModel.sendMessage(it) },
                onCancel = { viewModel.cancelAgent() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 60.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.Surface(
                shape = MaterialShapes.Cookie9Sided.toShape(),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(84.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "o",
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontFamily = GeistMono,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Hey there",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Ask me anything — I can browse, read files, run shell\ncommands, and more.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageItem(message: dev.ophoner.data.model.Message) {
    val isUser = message.role == MessageRole.USER

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        // Role label
        Text(
            text = if (isUser) "you" else "agent",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = GeistMono,
                fontWeight = FontWeight.Medium,
            ),
            color = if (isUser) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )

        // Build a map of tool results by tool use id for pairing
        val toolResults = message.content
            .filterIsInstance<ContentBlock.ToolResult>()
            .associateBy { it.toolUseId }

        for (block in message.content) {
            when (block) {
                is ContentBlock.Text -> {
                    if (isUser) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                                .background(UserBubble)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = block.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    } else {
                        MarkdownText(
                            text = block.text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
                is ContentBlock.ToolUse -> {
                    // Pair with its result if available
                    val result = toolResults[block.id]
                    ToolCallCard(
                        name = block.name,
                        arguments = block.argumentsJson,
                        result = result?.output,
                        isExecuting = false,
                        isError = result?.isError ?: false,
                    )
                }
                is ContentBlock.ToolResult -> {
                    // Skip — already rendered paired with its ToolUse above
                }
            }
        }
    }
}

@Composable
private fun StreamingResponse(
    text: String,
    toolCalls: List<ToolCallUiState>,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "agent",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = GeistMono,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )

        // Tool calls
        for (tc in toolCalls) {
            ToolCallCard(
                name = tc.name,
                arguments = tc.arguments,
                result = tc.result,
                isExecuting = tc.isExecuting,
                isError = tc.isError,
            )
            Spacer(Modifier.height(4.dp))
        }

        // Streaming text
        if (text.isNotEmpty()) {
            MarkdownText(
                text = text,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
fun ToolCallCard(
    name: String,
    arguments: String,
    result: String?,
    isExecuting: Boolean,
    isError: Boolean,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        // Compact single-line chip
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF1A1A1A))
                .clickable { if (!isExecuting) expanded = !expanded }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status dot
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isExecuting -> AccentAmber
                            isError -> AccentRed
                            result != null -> AccentGreen
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        }
                    ),
            )
            Spacer(Modifier.width(6.dp))

            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            if (isExecuting) {
                Spacer(Modifier.width(6.dp))
                LoadingIndicator(
                    modifier = Modifier.size(14.dp),
                    color = AccentAmber,
                )
            }

            if (!isExecuting && (arguments.isNotEmpty() || result != null)) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (expanded) "−" else "+",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
        }

        // Expandable detail
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .padding(start = 12.dp, top = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF141414))
                    .padding(8.dp),
            ) {
                if (arguments.isNotEmpty() && arguments != "{}") {
                    Text(
                        text = formatJson(arguments),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 8,
                    )
                }
                if (result != null) {
                    if (arguments.isNotEmpty() && arguments != "{}") {
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        text = result.take(400).let { if (result.length > 400) "$it..." else it },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                        color = if (isError) AccentRed.copy(alpha = 0.8f)
                            else AccentGreen.copy(alpha = 0.7f),
                        maxLines = 12,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LoadingIndicator(
            modifier = Modifier.size(18.dp),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "thinking...",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = GeistMono),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun InputBar(
    isAgentRunning: Boolean,
    hasProvider: Boolean,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.9f))
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    if (!hasProvider) "Configure a provider first..."
                    else "Message Ophoner...",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            enabled = !isAgentRunning && hasProvider,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF1A1A1A),
                unfocusedContainerColor = Color(0xFF1A1A1A),
                disabledContainerColor = Color(0xFF1A1A1A).copy(alpha = 0.5f),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (text.isNotBlank() && !isAgentRunning) {
                        onSend(text.trim())
                        text = ""
                    }
                }
            ),
            maxLines = 4,
            textStyle = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.width(8.dp))

        if (isAgentRunning) {
            IconButton(
                onClick = onCancel,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(AccentRed.copy(alpha = 0.15f)),
            ) {
                Icon(
                    Icons.Default.Stop,
                    contentDescription = "Cancel",
                    tint = AccentRed,
                )
            }
        } else {
            IconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onSend(text.trim())
                        text = ""
                    }
                },
                enabled = text.isNotBlank() && hasProvider,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (text.isNotBlank() && hasProvider) MaterialTheme.colorScheme.primary
                        else Color(0xFF1A1A1A)
                    ),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (text.isNotBlank() && hasProvider) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                )
            }
        }
    }
}

private val PrettyJson = kotlinx.serialization.json.Json { prettyPrint = true }

private fun formatJson(json: String): String {
    return try {
        val element = PrettyJson.parseToJsonElement(json)
        PrettyJson.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(), element.jsonObject
        )
    } catch (_: Exception) {
        json
    }
}

