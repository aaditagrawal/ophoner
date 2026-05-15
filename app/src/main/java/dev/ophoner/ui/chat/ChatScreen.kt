package dev.ophoner.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ophoner.data.model.ContentBlock
import dev.ophoner.data.model.Message
import dev.ophoner.data.model.MessageRole
import dev.ophoner.ui.theme.AccentAmber
import dev.ophoner.ui.theme.AccentBackdrop
import dev.ophoner.ui.theme.AccentGreen
import dev.ophoner.ui.theme.AccentRed
import dev.ophoner.ui.theme.chatPalette
import dev.ophoner.ui.theme.monoFamily
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        // 1. Painted texture behind everything
        AccentBackdrop()

        // 2. Scaffold renders chat content + scrollable list, transparent so blobs read through.
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = Color.Transparent,
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
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (uiState.messages.isEmpty() && !uiState.isAgentRunning) {
                        item { EmptyState() }
                    }

                    items(uiState.messages, key = { it.id }) { message ->
                        MessageItem(message)
                    }

                    if (uiState.streamingText.isNotEmpty() || uiState.activeToolCalls.isNotEmpty()) {
                        item {
                            StreamingResponse(
                                text = uiState.streamingText,
                                toolCalls = uiState.activeToolCalls,
                            )
                        }
                    }

                    if (uiState.isAgentRunning && uiState.streamingText.isEmpty() && uiState.activeToolCalls.isEmpty()) {
                        item { ThinkingIndicator() }
                    }
                }

                // 3. Floating composer at the bottom — frosted glass
                InputBar(
                    isAgentRunning = uiState.isAgentRunning,
                    hasProvider = uiState.providerConfig != null,
                    onSend = { viewModel.sendMessage(it) },
                    onCancel = { viewModel.cancelAgent() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                )
            }
        }

        // 4. Floating top bar — frosted glass over the scroll
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
            .background(
                Brush.verticalGradient(
                    listOf(
                        bg.copy(alpha = 0.92f),
                        bg.copy(alpha = 0.78f),
                        bg.copy(alpha = 0.0f),
                    ),
                )
            )
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        TopAppBar(
            title = {
                if (folderName != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = folderName,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onOpenConversations) {
                    Icon(Icons.Outlined.Menu, contentDescription = "Conversations")
                }
            },
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings")
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
            .padding(vertical = 80.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "o",
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Hey there",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Ask anything — I can browse, read files, run shell\ncommands, and more.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                .fillMaxWidth(0.85f)
                                .clip(RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp))
                                .background(palette.userBubble)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = block.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.onUserBubble,
                            )
                        }
                    } else {
                        MarkdownText(
                            text = block.text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 2.dp, vertical = 2.dp),
                        )
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
        val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        val parts = buildList {
            add(formatClock(timestamp))
            stats?.let {
                val tokensPerSec = estimateTokensPerSecond(it.outputChars, it.durationMs)
                if (tokensPerSec != null) add("${tokensPerSec} tok/s")
            }
        }
        Text(
            text = parts.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
        )
        if (copyText != null) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
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
            Spacer(Modifier.height(4.dp))
        }

        if (text.isNotEmpty()) {
            MarkdownText(
                text = text,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 2.dp),
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
    val palette = chatPalette()

    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(palette.toolCardBg)
                .border(0.5.dp, palette.toolCardBorder, RoundedCornerShape(10.dp))
                .clickable { if (!isExecuting) expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isExecuting -> AccentAmber
                            isError -> AccentRed
                            result != null -> AccentGreen
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        }
                    ),
            )
            Spacer(Modifier.width(8.dp))

            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = monoFamily(),
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (isExecuting) {
                Spacer(Modifier.width(8.dp))
                LoadingIndicator(
                    modifier = Modifier.size(14.dp),
                    color = AccentAmber,
                )
            }

            if (!isExecuting && (arguments.isNotEmpty() || result != null)) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (expanded) "−" else "+",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .padding(start = 10.dp, top = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.codeBlockBg)
                    .padding(10.dp),
            ) {
                if (arguments.isNotEmpty() && arguments != "{}") {
                    Text(
                        text = remember(arguments) { formatJson(arguments) },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = monoFamily(),
                            fontSize = 11.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 8,
                    )
                }
                if (result != null) {
                    if (arguments.isNotEmpty() && arguments != "{}") {
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(
                        text = result.take(400).let { if (result.length > 400) "$it..." else it },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = monoFamily(),
                            fontSize = 11.sp,
                        ),
                        color = if (isError) AccentRed else palette.codeText,
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
            modifier = Modifier.size(16.dp),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Thinking…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    var isFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    val isExpanded by remember(text, isFocused) {
        derivedStateOf { isFocused || text.isNotBlank() }
    }

    BackHandler(enabled = isExpanded) { focusManager.clearFocus() }

    val verticalPadding by animateDpAsState(
        targetValue = if (isExpanded) 16.dp else 12.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "input-vpad",
    )
    val horizontalPadding by animateDpAsState(
        targetValue = if (isExpanded) 12.dp else 16.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "input-hpad",
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (isExpanded) 28.dp else 26.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "input-radius",
    )

    val bg = MaterialTheme.colorScheme.background

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (isExpanded) {
                    Brush.verticalGradient(listOf(Color.Transparent, bg.copy(alpha = 0.7f)))
                } else {
                    Brush.verticalGradient(listOf(Color.Transparent, bg.copy(alpha = 0.55f)))
                }
            )
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
    ) {
        val surfaceColor = MaterialTheme.colorScheme.surfaceContainerLow
        val containerColor = if (isExpanded) surfaceColor else surfaceColor.copy(alpha = 0.78f)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (isExpanded) 280.dp else 0.dp)
                .clip(RoundedCornerShape(cornerRadius))
                .background(containerColor)
                .border(
                    width = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = if (isExpanded) 0.5f else 0.35f),
                    shape = RoundedCornerShape(cornerRadius),
                ),
        ) {
            if (isExpanded) {
                ExpandedComposerHeader(onCollapse = { focusManager.clearFocus() })
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 16.dp,
                        end = 6.dp,
                        top = if (isExpanded) 8.dp else 4.dp,
                        bottom = 6.dp,
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
                            style = textStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
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
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
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
                        background = AccentRed.copy(alpha = 0.15f),
                        tint = AccentRed,
                    )
                } else {
                    val canSend = text.isNotBlank() && hasProvider
                    SendButton(
                        icon = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        onClick = {
                            if (canSend) {
                                onSend(text.trim())
                                text = ""
                                focusManager.clearFocus()
                            }
                        },
                        enabled = canSend,
                        background = if (canSend) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                        tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
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
            .padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "New message",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onCollapse, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Collapse",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
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
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(background)
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
