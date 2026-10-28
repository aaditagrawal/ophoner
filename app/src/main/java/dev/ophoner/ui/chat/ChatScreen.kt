package dev.ophoner.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.State
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.TextButton
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.rememberHazeState
import dev.ophoner.data.model.ContentBlock
import dev.ophoner.data.model.ChatGptSpeed
import dev.ophoner.data.model.ProviderType
import dev.ophoner.data.model.ConversationMode
import dev.ophoner.data.model.Message
import dev.ophoner.data.model.MessageRole
import dev.ophoner.ui.components.GlassIconButton
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.offset
import dev.ophoner.ui.theme.HomeBackground
import dev.ophoner.ui.theme.LocalUiPrefs
import dev.ophoner.ui.components.MascotFace
import dev.ophoner.ui.components.Mascot
import dev.ophoner.ui.components.GlassSurface
import dev.ophoner.ui.components.IconMark
import dev.ophoner.ui.components.LocalGlassState
import dev.ophoner.ui.components.Lucide
import dev.ophoner.ui.components.LucideIcon
import dev.ophoner.ui.components.StatusBarScrim
import dev.ophoner.ui.components.glassSource
import dev.ophoner.ui.components.pressFade
import dev.ophoner.ui.theme.StartGradientStops
import dev.ophoner.ui.theme.chatPalette
import dev.ophoner.ui.theme.tokens
import dev.ophoner.ui.util.formatFolderDisplayName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val UserBubbleShape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
private val ComposerShape = RoundedCornerShape(26.dp)

private data class Shortcut(
    val label: String,
    @DrawableRes val icon: Int,
    val prompt: String,
)

private val Shortcuts = listOf(
    Shortcut("Web", Lucide.Globe, "Search the web for "),
    Shortcut("Files", Lucide.FolderOpen, "List the files in my working directory and summarise what's there."),
    Shortcut(
        "Device",
        Lucide.Smartphone,
        "Give me a quick overview of this device: model, Android version, storage, and battery.",
    ),
)

@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    onOpenConversations: () -> Unit,
    onNewChat: (folderUri: String?, folderName: String?) -> Unit,
    onOpenBranch: (String, String?) -> Unit = { _, _ -> },
    viewModel: ChatViewModel = hiltViewModel(),
    modelPickerViewModel: ModelPickerViewModel = hiltViewModel(),
) {
    val pickerState by modelPickerViewModel.state.collectAsStateWithLifecycle()
    val observedState = viewModel.uiState.collectAsStateWithLifecycle()
    // Token updates only invalidate the timeline, not the composer and glass controls.
    val uiState by remember(observedState) {
        derivedStateOf(structuralEqualityPolicy()) {
            observedState.value.copy(streamingText = "", activeToolCalls = emptyList())
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(pickerState.selectionError) {
        pickerState.selectionError?.let { snackbarHostState.showSnackbar(it) }
    }
    val glass = rememberHazeState()
    val t = tokens()
    val density = LocalDensity.current

    // Shortcut tiles feed the composer through the same draft path as share intents.
    var shortcutDraft by remember { mutableStateOf<String?>(null) }
    var composerHeightPx by remember { mutableIntStateOf(0) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.banner) {
        uiState.banner?.let {
            snackbarHostState.showSnackbar(it, actionLabel = "Dismiss")
            viewModel.clearBanner()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshProvider() }

    val isEmpty = uiState.conversationId == null && uiState.messages.isEmpty() && !uiState.isAgentRunning
    val gradientAlpha by animateFloatAsState(
        targetValue = if (isEmpty) 1f else 0f,
        animationSpec = tween(durationMillis = 420),
        label = "start-gradient",
    )
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val composerHeight = with(density) { composerHeightPx.toDp() }
    val prefs = LocalUiPrefs.current

    CompositionLocalProvider(LocalGlassState provides glass) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(t.background),
        ) {
            // Everything in this box is sampled by the glass controls above it.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .glassSource(glass),
            ) {
                if (gradientAlpha > 0f && prefs.homeBackground == HomeBackground.GRADIENT) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = gradientAlpha }
                            .background(
                                Brush.linearGradient(
                                    colorStops = StartGradientStops.zip(t.startGradient).toTypedArray(),
                                    start = Offset.Zero,
                                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                                ),
                            ),
                    )
                }
                ChatTimeline(
                    onEditPrompt = { viewModel.branchPrompt(it, true, onOpenBranch) },
                    onForkPrompt = { viewModel.branchPrompt(it, false, onOpenBranch) },                    observedState = observedState,
                    onLoadOlder = viewModel::loadOlderMessages,
                    onLoadNewer = viewModel::loadNewerMessages,
                    onLatest = viewModel::showLatestMessages,
                    onViewport = viewModel::rememberViewport,
                    contentPadding = PaddingValues(
                        start = 18.dp,
                        end = 18.dp,
                        top = statusBarTop + 64.dp,
                        bottom = composerHeight + 16.dp,
                    ),
                )
                if (isEmpty) {
                    HomeStage(
                        hasProvider = uiState.providerConfig != null,
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier
                            .fillMaxSize()
                            .imePadding()
                            .padding(top = statusBarTop + 56.dp, bottom = composerHeight),
                    )
                }
            }

            StatusBarScrim(
                modifier = Modifier.align(Alignment.TopCenter),
                visible = !isEmpty,
            )

            TopControls(
                folderName = uiState.scopedFolderName?.takeIf {
                    uiState.conversationMode == ConversationMode.FOLDER
                },
                onOpenConversations = onOpenConversations,
                onOpenSettings = onOpenSettings,
                // Existing chats get a pencil; folder chats start the new chat in the same folder.
                fastMode = uiState.providerConfig?.takeIf { it.providerType == ProviderType.CODEX_CHATGPT }
                    ?.let { it.chatGptSpeed == ChatGptSpeed.FAST },
                fastModeEnabled = !uiState.isAgentRunning && !pickerState.selecting,
                onFastModeChange = { fast ->
                    uiState.providerConfig?.let { provider ->
                        modelPickerViewModel.selectSpeed(provider.id,
                            if (fast) ChatGptSpeed.FAST else ChatGptSpeed.STANDARD) {
                            viewModel.refreshProvider(resolveAutomatic = false)
                        }
                    }
                },
                onNewChat = if (isEmpty) null else {
                    {
                        val folder = uiState.conversationMode == ConversationMode.FOLDER
                        onNewChat(
                            uiState.scopedFolderUri.takeIf { folder },
                            uiState.scopedFolderName.takeIf { folder },
                        )
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .imePadding()
                    .onSizeChanged { composerHeightPx = it.height }
                    .windowInsetsPadding(WindowInsets.navigationBars),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SnackbarHost(snackbarHostState) { data ->
                    Snackbar(
                        snackbarData = data,
                        shape = RoundedCornerShape(20.dp),
                        containerColor = t.ink,
                        contentColor = t.inverse,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                if (isEmpty && prefs.showShortcuts) {
                    ShortcutRow(
                        onShortcut = { shortcutDraft = it },
                        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                    )
                }
                if (uiState.isAgentRunning) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(when {
                        uiState.isPaused -> "Paused · Follow-ups are saved"
                        uiState.pauseRequested -> "Pausing after the current action…"
                        uiState.queuedFollowUps > 0 -> "${uiState.queuedFollowUps} follow-up queued"
                        else -> "Working · You can send a follow-up"
                    }, style = MaterialTheme.typography.labelMedium, color = t.secondary, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::togglePause) { Text(if (uiState.pauseRequested) "Resume" else "Pause") }
                }
                Composer(
                    isAgentRunning = uiState.isAgentRunning,
                    hasProvider = uiState.providerConfig != null,
                    provider = uiState.providerConfig,
                    onModelSelected = { viewModel.refreshProvider(resolveAutomatic = false) },
                    onManageProviders = onOpenSettings,
                    draftText = shortcutDraft ?: uiState.draftText,
                    onDraftConsumed = {
                        if (shortcutDraft != null) shortcutDraft = null else viewModel.clearDraftText()
                    },
                    onSend = { viewModel.sendMessage(it) },
                    onBeginTyping = { viewModel.pauseForInput() },
                    onCancel = { viewModel.cancelAgent() },
                    modifier = Modifier
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)
                        .widthIn(max = 640.dp),
                )
            }
        }
    }
}

@Composable
internal fun ChatTimeline(
    observedState: State<ChatUiState>,
    contentPadding: PaddingValues,
    onLoadOlder: () -> Unit = {},
    onLoadNewer: () -> Unit = {},
    onLatest: () -> Unit = {},
    onViewport: (ChatViewport) -> Unit = {},
    onEditPrompt: (Message) -> Unit = {},
    onForkPrompt: (Message) -> Unit = {},
) {
    val uiState by observedState
    val listState = rememberLazyListState()
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var initialized by remember(uiState.conversationId) { mutableStateOf(false) }
    var followLatest by remember(uiState.conversationId) { mutableStateOf(true) }
    val reversed = remember(uiState.messages) { uiState.messages.asReversed() }
    val hasLive = !uiState.hasNewerMessages && (uiState.streamingText.isNotEmpty() || uiState.activeToolCalls.isNotEmpty() || uiState.isAgentRunning)

    fun saveViewport() {
        if (!initialized) return
        val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            uiState.messages.any { it.id == item.key }
        } ?: return
        val message = uiState.messages.firstOrNull { it.id == visible.key } ?: return
        onViewport(ChatViewport(message.id, message.orderIndex,
            if (visible.index == listState.firstVisibleItemIndex) listState.firstVisibleItemScrollOffset else 0,
            followLatest && !uiState.hasNewerMessages))
    }

    DisposableEffect(uiState.conversationId, uiState.messages, followLatest) {
        onDispose { saveViewport() }
    }
    LaunchedEffect(uiState.historyLoaded, uiState.conversationId) {
        if (!uiState.historyLoaded || initialized) return@LaunchedEffect
        val anchor = uiState.restoredViewport
        followLatest = anchor?.followLatest ?: true
        val index = if (anchor != null && !followLatest) reversed.indexOfFirst { it.id == anchor.messageId } else -1
        listState.scrollToItem(if (index >= 0) index + if (hasLive || uiState.hasNewerMessages) 1 else 0 else 0,
            if (index >= 0) anchor!!.offset else 0)
        initialized = true
    }
    LaunchedEffect(dragged) { if (dragged) followLatest = false }
    LaunchedEffect(listState) {
        snapshotFlow { !observedState.value.hasNewerMessages && !listState.canScrollBackward && !listState.isScrollInProgress }.collect { atBottom ->
            if (atBottom && initialized) followLatest = true
        }
    }
    LaunchedEffect(listState, onLoadOlder) {
        snapshotFlow {
            val state = observedState.value
            initialized && state.hasOlderMessages && !state.loadingOlderMessages &&
                (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= listState.layoutInfo.totalItemsCount - 5
        }.collect { nearTop -> if (nearTop) onLoadOlder() }
    }
    LaunchedEffect(listState, onLoadNewer) {
        snapshotFlow {
            val state = observedState.value
            initialized && state.hasNewerMessages && !state.loadingOlderMessages &&
                listState.firstVisibleItemIndex < 3
        }.collect { nearBottom -> if (nearBottom) onLoadNewer() }
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) saveViewport()
    }
    LaunchedEffect(initialized, followLatest, uiState.messages.lastOrNull()?.id, hasLive) {
        if (initialized && followLatest && !dragged) listState.scrollToItem(0)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (uiState.hasNewerMessages) {
                item(key = "newer", contentType = "history-control") {
                    TextButton(onClick = onLoadNewer, enabled = !uiState.loadingOlderMessages) {
                        Text("Load newer messages")
                    }
                }
            }
            if (hasLive) {
                item(key = "streaming", contentType = "streaming") {
                    if (uiState.streamingText.isNotEmpty() || uiState.activeToolCalls.isNotEmpty()) {
                        StreamingResponse(uiState.streamingText, uiState.activeToolCalls)
                    } else ThinkingIndicator()
                }
            }
            items(reversed, key = { it.id }, contentType = { it.role }) { MessageItem(it, onEditPrompt, onForkPrompt) }
            if (uiState.hasOlderMessages) {
                item(key = "older", contentType = "history-control") {
                    TextButton(onClick = onLoadOlder, enabled = !uiState.loadingOlderMessages) {
                        Text(if (uiState.loadingOlderMessages) "Loading older messages…" else "Load older messages")
                    }
                }
            }
        }
        if (initialized && (!followLatest || uiState.hasNewerMessages)) {
            TextButton(
                onClick = { onLatest(); followLatest = true },
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(bottom = contentPadding.calculateBottomPadding(), end = 18.dp),
            ) { Text("Latest") }
        }
    }
}

@Composable
private fun TopControls(
    folderName: String?,
    onOpenConversations: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewChat: (() -> Unit)?,
    fastMode: Boolean?,
    fastModeEnabled: Boolean,
    onFastModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = tokens()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(
            icon = Lucide.PanelLeft,
            contentDescription = "Conversations",
            onClick = onOpenConversations,
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (folderName != null) {
                GlassSurface(
                    modifier = Modifier.height(40.dp),
                    shape = CircleShape,
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LucideIcon(icon = Lucide.FolderOpen, contentDescription = null, tint = t.accent, size = 17.dp)
                        Text(
                            text = formatFolderDisplayName(folderName),
                            style = MaterialTheme.typography.labelLarge,
                            color = t.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (onNewChat != null) {
            GlassIconButton(
                icon = Lucide.SquarePen,
                contentDescription = "New chat",
                onClick = onNewChat,
            )
        }
        GlassIconButton(
            icon = Lucide.Settings,
            contentDescription = "Settings",
            onClick = onOpenSettings,
        )
        if (fastMode != null) {
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .toggleable(value = fastMode, enabled = fastModeEnabled,
                        role = Role.Switch, onValueChange = onFastModeChange)
                    .semantics { contentDescription = "Fast mode" }
                    .padding(start = 8.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Fast", style = MaterialTheme.typography.labelMedium,
                    color = if (fastModeEnabled) t.ink else t.secondary)
                Switch(checked = fastMode, onCheckedChange = null, enabled = fastModeEnabled)
            }
        }
    }
}

/**
 * Empty-chat stage: the mascot, idling and blinking, centred in the space above the
 * composer. Tapping it earns a smile.
 */
@Composable
private fun HomeStage(
    hasProvider: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = tokens()
    val prefs = LocalUiPrefs.current
    // Taps get a random reaction; left alone for a while it dozes off until tapped again.
    var reaction by remember { mutableStateOf<MascotFace?>(null) }
    var pokes by remember { mutableIntStateOf(0) }
    var asleep by remember { mutableStateOf(false) }
    LaunchedEffect(pokes) {
        if (pokes > 0) {
            reaction = HomeReactions[(pokes - 1) % HomeReactions.size]
            delay(1_200)
            reaction = null
        }
        delay(25_000)
        asleep = true
    }
    val face = reaction ?: if (asleep) MascotFace.Sleepy else MascotFace.Open

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        // Shrinks with the keyboard open so it never collides with the shortcuts.
        val mascotSize = (maxHeight * 0.55f).coerceIn(56.dp, 128.dp)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (prefs.mascotEnabled) {
                Box(contentAlignment = Alignment.BottomCenter) {
                    // Flat contact shadow grounds the blob on the page.
                    Box(
                        modifier = Modifier
                            .offset(y = mascotSize * 0.05f)
                            .size(width = mascotSize * 0.62f, height = mascotSize * 0.10f)
                            .background(Color.Black.copy(alpha = if (t.dark) 0.28f else 0.07f), CircleShape),
                    )
                    Mascot(
                        size = mascotSize,
                        face = face,
                        contentDescription = "Ophoner",
                        modifier = Modifier
                            .clip(CircleShape)
                            .pressFade(onClickLabel = "Say hi", onClick = {
                                asleep = false
                                pokes++
                            }),
                    )
                }
            } else {
                Text(
                    "What should we do?",
                    style = MaterialTheme.typography.headlineMedium,
                    color = t.ink,
                )
            }

            if (!hasProvider) {
                Spacer(Modifier.height(36.dp))
                GlassSurface(
                    modifier = Modifier
                        .height(44.dp)
                        .clip(CircleShape)
                        .pressFade(onClick = onOpenSettings),
                    shape = CircleShape,
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LucideIcon(icon = Lucide.Settings, contentDescription = null, tint = t.accent, size = 17.dp)
                        Text("Add a provider to start", style = MaterialTheme.typography.labelLarge, color = t.ink)
                    }
                }
            }
        }
    }
}

private val HomeReactions = listOf(MascotFace.Happy, MascotFace.Wink, MascotFace.Surprised, MascotFace.Curious)

/** Suggestion tiles sitting right above the composer, where the prompt is written. */
@Composable
private fun ShortcutRow(
    onShortcut: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = tokens()
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        for (shortcut in Shortcuts) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .pressFade(onClickLabel = shortcut.label, onClick = { onShortcut(shortcut.prompt) })
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconMark(icon = shortcut.icon, size = 48.dp)
                Text(
                    shortcut.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = t.ink,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun MessageItem(message: Message, onEditPrompt: (Message) -> Unit, onForkPrompt: (Message) -> Unit) {
    val isUser = message.role == MessageRole.USER
    val palette = chatPalette()
    val context = LocalContext.current

    val stats = remember(message.content) {
        message.content.filterIsInstance<ContentBlock.Stats>().firstOrNull()
    }
    val visibleText = remember(message.content) {
        message.content.filterIsInstance<ContentBlock.Text>().joinToString("\n\n") { it.text }
    }
    val segments = remember(message.content) { segmentMessage(message.content) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (segment in segments) {
            when (segment) {
                is MessageSegment.Tools -> ToolCallGroup(segment.calls)
                is MessageSegment.Prose -> {
                    val block = segment.block
                    if (isUser) {
                        Box(
                            modifier = Modifier.fillMaxWidth(0.84f),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Text(
                                text = block.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.onUserBubble,
                                modifier = Modifier
                                    .clip(UserBubbleShape)
                                    .background(palette.userBubble)
                                    .border(1.dp, palette.userBubbleBorder, UserBubbleShape)
                                    .padding(horizontal = 16.dp, vertical = 11.dp),
                            )
                        }
                    } else {
                        MarkdownText(
                            text = block.text,
                            color = palette.onAssistant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }

        if (!isUser && (visibleText.isNotEmpty() || stats != null)) {
            MessageFooter(
                timestamp = message.createdAt,
                stats = stats,
                copyText = visibleText.takeIf { it.isNotBlank() },
                onCopy = { text -> copyToClipboard(context, text) },
            )
        }
        if (isUser) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { copyToClipboard(context, visibleText) }) { Text("Copy", style = MaterialTheme.typography.labelMedium) }
                TextButton(onClick = { onEditPrompt(message) }) { Text("Edit in branch", style = MaterialTheme.typography.labelMedium) }
                TextButton(onClick = { onForkPrompt(message) }) { Text("Fork here", style = MaterialTheme.typography.labelMedium) }
            }
            MessageFooter(
                timestamp = message.createdAt,
                stats = null,
                copyText = null,
                onCopy = {},
            )
        }
    }
}

/** Message content in display order, with each run of finished tool calls folded together. */
private sealed interface MessageSegment {
    data class Prose(val block: ContentBlock.Text) : MessageSegment
    data class Tools(val calls: List<ToolCallEntry>) : MessageSegment
}

private fun segmentMessage(content: List<ContentBlock>): List<MessageSegment> {
    val results = content.filterIsInstance<ContentBlock.ToolResult>().associateBy { it.toolUseId }
    val segments = mutableListOf<MessageSegment>()
    var run = mutableListOf<ToolCallEntry>()
    fun flush() {
        if (run.isNotEmpty()) {
            segments += MessageSegment.Tools(run)
            run = mutableListOf()
        }
    }
    for (block in content) {
        when (block) {
            is ContentBlock.Text -> {
                flush()
                segments += MessageSegment.Prose(block)
            }
            is ContentBlock.ToolUse -> {
                val result = results[block.id]
                run += ToolCallEntry(
                    id = block.id,
                    name = block.name,
                    arguments = block.argumentsJson,
                    result = result?.output,
                    isError = result?.isError ?: false,
                )
            }
            // Results pair with their ToolUse; stats render in the footer.
            is ContentBlock.ToolResult, is ContentBlock.Stats -> Unit
        }
    }
    flush()
    return segments
}

@Composable
private fun MessageFooter(
    timestamp: Long,
    stats: ContentBlock.Stats?,
    copyText: String?,
    onCopy: (String) -> Unit,
) {
    val t = tokens()
    Row(
        modifier = Modifier.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (copyText != null && LocalUiPrefs.current.mascotEnabled) {
            Mascot(size = 16.dp, face = MascotFace.Happy, blink = false)
            Spacer(Modifier.width(6.dp))
        }
        val footer = remember(timestamp, stats) {
            buildList {
                add(formatClock(timestamp))
                stats?.let {
                    val tokensPerSec = estimateTokensPerSecond(it.outputChars, it.durationMs)
                    if (tokensPerSec != null) add("$tokensPerSec tok/s")
                }
            }.joinToString(" · ")
        }
        Text(
            text = footer,
            style = MaterialTheme.typography.labelMedium,
            color = t.secondary,
        )
        if (copyText != null) {
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .pressFade(onClickLabel = "Copy message", onClick = { onCopy(copyText) }),
                contentAlignment = Alignment.Center,
            ) {
                LucideIcon(icon = Lucide.Copy, contentDescription = "Copy message", tint = t.mutedIcon, size = 15.dp)
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
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
            MarkdownText(
                text = text,
                color = chatPalette().onAssistant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    val t = tokens()
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(t.panel)
            .border(1.dp, t.line, shape)
            .padding(start = 12.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (LocalUiPrefs.current.mascotEnabled) {
            Mascot(size = 24.dp, face = MascotFace.Thinking, bounce = true)
        } else {
            LoadingIndicator(
                modifier = Modifier.size(18.dp),
                color = t.accent,
            )
        }
        Text(
            "Thinking…",
            style = MaterialTheme.typography.labelLarge,
            color = t.secondary,
        )
    }
}

@Composable
private fun Composer(
    isAgentRunning: Boolean,
    hasProvider: Boolean,
    provider: dev.ophoner.data.model.ProviderConfig?,
    onModelSelected: () -> Unit,
    onManageProviders: () -> Unit,
    draftText: String?,
    onDraftConsumed: () -> Unit,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onBeginTyping: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val t = tokens()
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var isFocused by remember { mutableStateOf(false) }
    var choosingModel by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(draftText) {
        val draft = draftText ?: return@LaunchedEffect
        if (draft.isNotEmpty()) {
            value = TextFieldValue(draft, selection = TextRange(draft.length))
            onDraftConsumed()
            if (hasProvider) focusRequester.requestFocus()
        }
    }

    val isExpanded by remember(value, isFocused) {
        derivedStateOf { isFocused || value.text.isNotBlank() }
    }

    BackHandler(enabled = isFocused) { focusManager.clearFocus() }

    val minHeight by animateDpAsState(
        targetValue = if (isExpanded) 148.dp else 132.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "composer-min-height",
    )

    val canSend = value.text.isNotBlank() && hasProvider && !choosingModel
    val send = {
        if (canSend) {
            onSend(value.text.trim())
            value = TextFieldValue("")
            focusManager.clearFocus()
        }
    }

    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = ComposerShape,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .animateContentSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                val textStyle = MaterialTheme.typography.bodyLarge.copy(color = t.ink)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp),
                ) {
                    if (value.text.isEmpty()) {
                        Text(
                            text = if (!hasProvider) "Add a provider to start" else if (isAgentRunning) "Send a follow-up…" else "Message Ophoner",
                            style = textStyle,
                            color = t.secondary,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { isFocused = it.isFocused; if (it.isFocused && isAgentRunning) onBeginTyping() },
                        textStyle = textStyle,
                        cursorBrush = SolidColor(t.accent),
                        enabled = hasProvider,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Default,
                        ),
                        maxLines = if (isExpanded) 10 else 1,
                    )
                }
            }

            run {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isExpanded) Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .pressFade(onClickLabel = "Close composer", onClick = { focusManager.clearFocus() }),
                        contentAlignment = Alignment.Center,
                    ) {
                        LucideIcon(icon = Lucide.X, contentDescription = "Close composer", tint = t.mutedIcon, size = 19.dp)
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        ModelPicker(
                            selected = provider,
                            enabled = !isAgentRunning,
                            onSelected = onModelSelected,
                            onManageProviders = onManageProviders,
                            onBusyChange = { choosingModel = it },
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    if (isAgentRunning) TextButton(onClick = onCancel) { Text("Stop", color = t.danger) }
                    ComposerAction(
                        isAgentRunning = false,
                        canSend = canSend,
                        onSend = send,
                        onCancel = onCancel,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerAction(
    isAgentRunning: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    val t = tokens()
    val (background, tint) = when {
        isAgentRunning -> t.danger to t.inverse
        canSend -> t.accent to t.onAccent
        else -> t.ink.copy(alpha = 0.08f) to t.mutedIcon
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(background)
            .pressFade(
                enabled = isAgentRunning || canSend,
                onClickLabel = if (isAgentRunning) "Stop" else "Send",
                role = Role.Button,
                onClick = if (isAgentRunning) onCancel else onSend,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isAgentRunning) {
            LucideIcon(icon = Lucide.Square, contentDescription = "Stop", tint = tint, size = 15.dp)
        } else {
            LucideIcon(icon = Lucide.ArrowUp, contentDescription = "Send", tint = tint, size = 20.dp)
        }
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
