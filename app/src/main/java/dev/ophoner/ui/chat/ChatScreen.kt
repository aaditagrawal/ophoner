package dev.ophoner.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewHeadline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ophoner.data.model.ContentBlock
import dev.ophoner.data.model.Message
import dev.ophoner.data.model.MessageRole
import dev.ophoner.ui.components.FolderIconBadge
import dev.ophoner.ui.util.formatFolderDisplayName
import dev.ophoner.ui.theme.AccentRed
import dev.ophoner.ui.theme.accentColor
import dev.ophoner.ui.theme.chatPalette
import dev.ophoner.ui.theme.monoFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ChromeRadius = 10.dp
private val BubbleRadius = 12.dp
private val PanelRadius = 10.dp

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

    LaunchedEffect(
        uiState.messages.size,
        uiState.streamingText,
        uiState.activeToolCalls.size,
        uiState.isAgentRunning,
    ) {
        if (uiState.messages.isNotEmpty() || uiState.streamingText.isNotEmpty()) {
            listState.scrollToItem(maxOf(0, listState.layoutInfo.totalItemsCount - 1))
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(Unit) { viewModel.refreshProvider() }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            val layoutDirection = LocalLayoutDirection.current
            val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .consumeWindowInsets(innerPadding),
                    contentPadding = PaddingValues(
                        start = 16.dp + innerPadding.calculateLeftPadding(layoutDirection),
                        end = 16.dp + innerPadding.calculateRightPadding(layoutDirection),
                        top = statusBarTop + 56.dp,
                        bottom = 110.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (uiState.messages.isEmpty() && !uiState.isAgentRunning) {
                        item(key = "empty_state") { EmptyState() }
                    }

                    items(
                        items = uiState.messages,
                        key = { it.id },
                        contentType = { it.role },
                    ) { message ->
                        MessageItem(message)
                    }

                    if (uiState.streamingText.isNotEmpty() || uiState.activeToolCalls.isNotEmpty()) {
                        item(key = "streaming") {
                            StreamingResponse(
                                text = uiState.streamingText,
                                toolCalls = uiState.activeToolCalls,
                            )
                        }
                    }

                    if (uiState.isAgentRunning && uiState.streamingText.isEmpty() && uiState.activeToolCalls.isEmpty()) {
                        item(key = "thinking") { ThinkingIndicator() }
                    }
                }

                InputBar(
                    isAgentRunning = uiState.isAgentRunning,
                    hasProvider = uiState.providerConfig != null,
                    draftText = uiState.draftText,
                    onDraftConsumed = { viewModel.clearDraftText() },
                    onSend = { viewModel.sendMessage(it) },
                    onCancel = { viewModel.cancelAgent() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                )
            }
        }

        FrostedTopBar(
            folderName = uiState.scopedFolderName?.takeIf {
                uiState.conversationMode == dev.ophoner.data.model.ConversationMode.FOLDER
            },
            onOpenConversations = onOpenConversations,
            onOpenSettings = onOpenSettings,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FrostedTopBar(
    folderName: String?,
    onOpenConversations: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = MaterialTheme.colorScheme.background
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(bg)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        TopAppBar(
            title = {
                if (folderName != null) {
                    val accent = accentColor()
                    val chipShape = RoundedCornerShape(ChromeRadius)
                    Row(
                        modifier = Modifier
                            .clip(chipShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                chipShape,
                            )
                            .padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FolderIconBadge(tint = accent, expanded = true, size = 22.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = formatFolderDisplayName(folderName),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = monoFamily(),
                                fontWeight = FontWeight.Medium,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onOpenConversations) {
                    Icon(Icons.Outlined.ViewHeadline, contentDescription = "Conversations")
                }
            },
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Tune, contentDescription = "Settings")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 96.dp, horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            val accent = accentColor()
            Text(
                "o",
                style = MaterialTheme.typography.displayMedium.copy(
                    fontFamily = monoFamily(),
                    fontWeight = FontWeight.Medium,
                ),
                color = accent,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "Hey there",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = monoFamily(),
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Ask anything — I can browse, read files, run shell commands, and more.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MessageItem(message: Message) {
    val isUser = message.role == MessageRole.USER
    val palette = chatPalette()
    val context = LocalContext.current

    val stats = remember(message.content) {
        message.content.filterIsInstance<ContentBlock.Stats>().firstOrNull()
    }
    val visibleText = remember(message.content) {
        message.content.filterIsInstance<ContentBlock.Text>().joinToString("\n\n") { it.text }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        val toolResults = remember(message.content) {
            message.content
                .filterIsInstance<ContentBlock.ToolResult>()
                .associateBy { it.toolUseId }
        }

        for (block in message.content) {
            when (block) {
                is ContentBlock.Text -> {
                    if (isUser) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.82f)
                                .clip(
                                    RoundedCornerShape(
                                        BubbleRadius,
                                        BubbleRadius,
                                        4.dp,
                                        BubbleRadius,
                                    ),
                                )
                                .background(palette.userBubble)
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                        ) {
                            Text(
                                text = block.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.onUserBubble,
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.92f)
                                .clip(
                                    RoundedCornerShape(
                                        BubbleRadius,
                                        BubbleRadius,
                                        BubbleRadius,
                                        4.dp,
                                    ),
                                )
                                .background(palette.assistantBubble)
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                        ) {
                            MarkdownText(
                                text = block.text,
                                color = palette.onAssistantBubble,
                            )
                        }
                    }
                }
                is ContentBlock.ToolUse -> {
                    val result = toolResults[block.id]
                    ToolCallCard(
                        name = block.name,
                        arguments = block.argumentsJson,
                        result = result?.output,
                        isExecuting = false,
                        isError = result?.isError ?: false,
                    )
                }
                is ContentBlock.ToolResult, is ContentBlock.Stats -> {
                    // Stats rendered in footer; ToolResult paired with its ToolUse above.
                }
            }
        }

        if (!isUser && (visibleText.isNotEmpty() || stats != null)) {
            MessageFooter(
                timestamp = message.createdAt,
                stats = stats,
                copyText = visibleText.takeIf { it.isNotBlank() },
                onCopy = { text -> copyToClipboard(context, text) },
                horizontalAlignment = Alignment.Start,
            )
        }
        if (isUser) {
            MessageFooter(
                timestamp = message.createdAt,
                stats = null,
                copyText = null,
                onCopy = {},
                horizontalAlignment = Alignment.End,
            )
        }
    }
}

@Composable
private fun MessageFooter(
    timestamp: Long,
    stats: ContentBlock.Stats?,
    copyText: String?,
    onCopy: (String) -> Unit,
    horizontalAlignment: Alignment.Horizontal,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = when (horizontalAlignment) {
            Alignment.End -> Arrangement.End
            else -> Arrangement.Start
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
        val parts = buildList {
            add(formatClock(timestamp))
            stats?.let {
                val tokensPerSec = estimateTokensPerSecond(it.outputChars, it.durationMs)
                if (tokensPerSec != null) add("${tokensPerSec} tok/s")
            }
        }
        Text(
            text = parts.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = monoFamily()),
            color = labelColor,
        )
        if (copyText != null) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onCopy(copyText) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = "Copy message",
                    tint = labelColor,
                    modifier = Modifier.size(13.dp),
                )
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
        for (tc in toolCalls) {
            ToolCallCard(
                name = tc.name,
                arguments = tc.arguments,
                result = tc.result,
                isExecuting = tc.isExecuting,
                isError = tc.isError,
            )
        }

        if (text.isNotEmpty()) {
            val palette = chatPalette()
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clip(
                        RoundedCornerShape(
                            BubbleRadius,
                            BubbleRadius,
                            BubbleRadius,
                            4.dp,
                        ),
                    )
                    .background(palette.assistantBubble)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                MarkdownText(text = text, color = palette.onAssistantBubble)
            }
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    val palette = chatPalette()
    val shape = RoundedCornerShape(PanelRadius)

    Row(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(palette.assistantBubble)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
                shape,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LoadingIndicator(
            modifier = Modifier.size(14.dp),
            color = accentColor(),
        )
        Text(
            "Thinking…",
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = monoFamily()),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InputBar(
    isAgentRunning: Boolean,
    hasProvider: Boolean,
    draftText: String? = null,
    onDraftConsumed: () -> Unit = {},
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var isFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val accent = accentColor()

    LaunchedEffect(draftText) {
        val draft = draftText ?: return@LaunchedEffect
        if (draft.isNotEmpty()) {
            text = draft
            onDraftConsumed()
        }
    }

    val isExpanded by remember(text, isFocused) {
        derivedStateOf { isFocused || text.isNotBlank() }
    }

    BackHandler(enabled = isExpanded) { focusManager.clearFocus() }

    val minHeight by animateDpAsState(
        targetValue = if (isExpanded) 180.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "input-min-height",
    )

    val bg = MaterialTheme.colorScheme.background
    val panelShape = RoundedCornerShape(PanelRadius)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        val containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        val borderColor = if (isFocused) {
            accent.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .clip(panelShape)
                .background(containerColor)
                .border(
                    width = 1.dp,
                    color = borderColor,
                    shape = panelShape,
                ),
        ) {
            if (isExpanded) {
                ExpandedComposerHeader(onCollapse = { focusManager.clearFocus() })
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 14.dp,
                        end = 8.dp,
                        top = if (isExpanded) 4.dp else 4.dp,
                        bottom = 8.dp,
                    ),
                verticalAlignment = Alignment.Bottom,
            ) {
                val textStyle = LocalTextStyle.current.merge(
                    MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 8.dp, bottom = 8.dp),
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = if (!hasProvider) "Configure a provider first…"
                            else "Message Ophoner…",
                            style = textStyle.copy(fontFamily = monoFamily()),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { isFocused = it.isFocused },
                        textStyle = textStyle,
                        cursorBrush = SolidColor(accent),
                        enabled = !isAgentRunning && hasProvider,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        keyboardActions = KeyboardActions(),
                        maxLines = if (isExpanded) Int.MAX_VALUE else 4,
                        interactionSource = remember { MutableInteractionSource() },
                    )
                }

                Spacer(Modifier.width(6.dp))

                if (isAgentRunning) {
                    SendButton(
                        icon = Icons.Outlined.Stop,
                        contentDescription = "Cancel",
                        onClick = onCancel,
                        background = AccentRed.copy(alpha = 0.14f),
                        tint = AccentRed,
                    )
                } else {
                    val canSend = text.isNotBlank() && hasProvider
                    SendButton(
                        icon = Icons.Outlined.ArrowUpward,
                        contentDescription = "Send",
                        onClick = {
                            if (canSend) {
                                onSend(text.trim())
                                text = ""
                                focusManager.clearFocus()
                            }
                        },
                        enabled = canSend,
                        background = if (canSend) accent
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                        tint = if (canSend) Color.White
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpandedComposerHeader(onCollapse: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "New message",
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = monoFamily()),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onCollapse, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Collapse",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun SendButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    background: Color,
    tint: Color,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(ChromeRadius)
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(shape)
            .background(background)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 0.2f else 0.28f),
                shape,
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun formatClock(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val sameDay = SimpleDateFormat("yyyyMMdd", Locale.US).run {
        format(Date(now)) == format(Date(timestamp))
    }
    val fmt = if (sameDay) "HH:mm" else "MMM d · HH:mm"
    return SimpleDateFormat(fmt, Locale.getDefault()).format(Date(timestamp))
}

/** Rough estimate: ~4 chars per token for English. Returns null if duration is too tiny. */
private fun estimateTokensPerSecond(chars: Int, durationMs: Long): Int? {
    if (durationMs < 200 || chars < 8) return null
    val tokens = chars / 4.0
    val seconds = durationMs / 1000.0
    val tps = tokens / seconds
    return if (tps < 1) null else tps.toInt()
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("message", text))
}
