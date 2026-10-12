package dev.ophoner.ui.conversations

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.rememberHazeState
import dev.ophoner.data.model.Conversation
import dev.ophoner.data.model.ConversationMode
import dev.ophoner.ui.components.FolderIconBadge
import dev.ophoner.ui.components.GlassIconButton
import dev.ophoner.ui.components.GroupedSection
import dev.ophoner.ui.components.LocalGlassState
import dev.ophoner.ui.components.Lucide
import dev.ophoner.ui.components.LucideIcon
import dev.ophoner.ui.components.Mascot
import dev.ophoner.ui.components.MascotFace
import dev.ophoner.ui.theme.LocalUiPrefs
import dev.ophoner.ui.components.PrimaryButton
import dev.ophoner.ui.components.RowDivider
import dev.ophoner.ui.components.ScreenTitle
import dev.ophoner.ui.components.SectionLabel
import dev.ophoner.ui.components.StatusBarScrim
import dev.ophoner.ui.components.glassSource
import dev.ophoner.ui.components.pressFade
import dev.ophoner.ui.theme.tokens
import dev.ophoner.ui.util.formatFolderDisplayName
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit


@Composable
fun ConversationListScreen(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    onNewFolderConversation: (uri: String, name: String) -> Unit,
    viewModel: ConversationListViewModel = hiltViewModel(),
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val pinnedFolders by viewModel.pinnedFolders.collectAsStateWithLifecycle()
    val glass = rememberHazeState()
    val t = tokens()

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val name = it.lastPathSegment?.replace("primary:", "/") ?: "folder"
            viewModel.pinFolder(it.toString(), name)
            onNewFolderConversation(it.toString(), name)
        }
    }

    val generalConvs = remember(conversations) {
        conversations.filter { it.mode == ConversationMode.GENERAL }
    }
    val projects = remember(conversations, pinnedFolders) {
        buildProjects(conversations, pinnedFolders)
    }
    val expandedFolders = remember { mutableStateMapOf<String, Boolean>() }
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    CompositionLocalProvider(LocalGlassState provides glass) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(t.background),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .glassSource(glass),
                contentPadding = PaddingValues(
                    start = 18.dp,
                    end = 18.dp,
                    top = statusBarTop + 64.dp,
                    bottom = navBarBottom + 104.dp,
                ),
            ) {
                item("title") {
                    ScreenTitle(
                        title = "Chats",
                        subtitle = when {
                            conversations.isEmpty() -> null
                            conversations.size == 1 -> "1 conversation"
                            else -> "${conversations.size} conversations"
                        },
                        modifier = Modifier.padding(top = 12.dp, bottom = 36.dp),
                    )
                }

                if (generalConvs.isEmpty() && projects.isEmpty()) {
                    item("empty") { EmptyState() }
                }

                if (projects.isNotEmpty()) {
                    item("header-projects") { SectionLabel("Projects") }
                    for (project in projects) {
                        val expanded = expandedFolders[project.key] ?: false
                        item(key = "project:${project.key}", contentType = "project") {
                            FolderRow(
                                name = formatFolderDisplayName(project.name),
                                count = project.chats.size,
                                color = t.accent,
                                expanded = expanded,
                                onToggle = { expandedFolders[project.key] = !expanded },
                                onNewChat = { onNewFolderConversation(project.uri, project.name) },
                                onRemove = { viewModel.removeProject(project.uri) },
                            )
                        }
                        if (expanded) {
                            if (project.chats.isEmpty()) {
                                item(key = "empty:${project.key}", contentType = "empty-project") {
                                    Text(
                                        "No chats yet. Tap + to start one.",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = t.secondary,
                                        modifier = Modifier.padding(start = 66.dp, end = 16.dp, bottom = 10.dp),
                                    )
                                }
                            } else {
                                itemsIndexed(project.chats, key = { _, chat -> "project-chat:${chat.id}" }, contentType = { _, _ -> "project-chat" }) { _, conv ->
                                    FolderChildRow(
                                        conversation = conv,
                                        onClick = { onOpenConversation(conv.id) },
                                        onDelete = { viewModel.deleteConversation(conv.id) },
                                    )
                                }
                            }
                        }
                        item(key = "divider:${project.key}", contentType = "divider") {
                            RowDivider(startIndent = 66.dp)
                        }
                    }
                    item("projects-bottom") { Spacer(Modifier.height(36.dp)) }
                }

                if (generalConvs.isNotEmpty()) {
                    item("header-recents") { SectionLabel("Recents") }
                    itemsIndexed(generalConvs, key = { _, conv -> conv.id }) { index, conv ->
                        RecentRow(
                            conversation = conv,
                            showDivider = index < generalConvs.lastIndex,
                            onClick = { onOpenConversation(conv.id) },
                            onDelete = { viewModel.deleteConversation(conv.id) },
                        )
                    }
                }
            }

            StatusBarScrim(modifier = Modifier.align(Alignment.TopCenter))

            GlassIconButton(
                icon = Lucide.ArrowLeft,
                contentDescription = "Back",
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlassIconButton(
                    icon = Lucide.FolderPlus,
                    contentDescription = "New folder chat",
                    onClick = { folderPickerLauncher.launch(null) },
                )
                PrimaryButton(
                    text = "New chat",
                    icon = Lucide.SquarePen,
                    onClick = onNewConversation,
                    height = 48.dp,
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    val t = tokens()
    Column(
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (LocalUiPrefs.current.mascotEnabled) {
            Mascot(size = 56.dp, face = MascotFace.Sleepy, blink = false, modifier = Modifier.padding(bottom = 8.dp))
        }
        Text(
            "No chats yet",
            style = MaterialTheme.typography.headlineSmall,
            color = t.ink,
        )
        Text(
            "Start a new chat, or open a folder to work with local files.",
            style = MaterialTheme.typography.bodyMedium,
            color = t.secondary,
        )
    }
}

@Composable
private fun FolderRow(
    name: String,
    count: Int,
    color: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
    onNewChat: () -> Unit,
    onRemove: () -> Unit,
) {
    val t = tokens()
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(durationMillis = 180),
        label = "folder-chevron",
    )
    var confirmRemove by rememberSaveable(name) { mutableStateOf(false) }
    LaunchedEffect(confirmRemove) {
        if (confirmRemove) {
            delay(3000)
            confirmRemove = false
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .pressFade(onClickLabel = if (expanded) "Collapse" else "Expand", onClick = onToggle)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FolderIconBadge(tint = color, expanded = expanded)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                color = t.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (count == 1) "1 chat" else "$count chats",
                style = MaterialTheme.typography.labelMedium,
                color = t.secondary,
            )
        }
        if (expanded) {
            RowAction(
                icon = Lucide.Plus,
                contentDescription = "New chat in $name",
                tint = t.icon,
                onClick = onNewChat,
            )
            RowAction(
                icon = Lucide.Trash,
                contentDescription = if (confirmRemove) "Confirm remove" else "Remove project",
                tint = if (confirmRemove) t.danger else t.mutedIcon,
                onClick = { if (confirmRemove) onRemove() else confirmRemove = true },
            )
        }
        LucideIcon(
            icon = Lucide.ChevronDown,
            contentDescription = null,
            tint = t.mutedIcon,
            size = 18.dp,
            modifier = Modifier.rotate(rotation),
        )
    }
}

@Composable
private fun FolderChildRow(
    conversation: Conversation,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val t = tokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressFade(onClick = onClick)
            .padding(start = 66.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                conversation.title.ifBlank { "Untitled" },
                style = MaterialTheme.typography.bodyMedium,
                color = t.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatRelativeDate(conversation.updatedAt),
                style = MaterialTheme.typography.labelMedium,
                color = t.secondary,
            )
        }
        DeleteAction(key = conversation.id, onDelete = onDelete)
    }
}

@Composable
private fun RecentRow(
    conversation: Conversation,
    showDivider: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val t = tokens()
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .pressFade(onClick = onClick)
                .padding(start = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    conversation.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleSmall,
                    color = t.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatRelativeDate(conversation.updatedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = t.secondary,
                )
            }
            DeleteAction(key = conversation.id, onDelete = onDelete)
        }
        if (showDivider) RowDivider(startIndent = 4.dp)
    }
}

@Composable
private fun RowAction(
    icon: Int,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .pressFade(onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LucideIcon(icon = icon, contentDescription = contentDescription, tint = tint, size = 18.dp)
    }
}

@Composable
private fun DeleteAction(
    key: Any,
    onDelete: () -> Unit,
) {
    val t = tokens()
    var confirm by rememberSaveable(key) { mutableStateOf(false) }
    LaunchedEffect(confirm) {
        if (confirm) {
            delay(3000)
            confirm = false
        }
    }
    RowAction(
        icon = Lucide.Trash,
        contentDescription = if (confirm) "Confirm delete" else "Delete",
        tint = if (confirm) t.danger else t.mutedIcon.copy(alpha = 0.7f),
        onClick = {
            if (confirm) {
                onDelete()
                confirm = false
            } else {
                confirm = true
            }
        },
    )
}

private fun formatRelativeDate(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
        diff < TimeUnit.HOURS.toMillis(1) -> {
            val mins = TimeUnit.MILLISECONDS.toMinutes(diff)
            "${mins}m ago"
        }
        diff < TimeUnit.DAYS.toMillis(1) -> {
            val hours = TimeUnit.MILLISECONDS.toHours(diff)
            "${hours}h ago"
        }
        diff < TimeUnit.DAYS.toMillis(7) -> {
            val days = TimeUnit.MILLISECONDS.toDays(diff)
            if (days == 1L) "Yesterday" else "${days}d ago"
        }
        diff < TimeUnit.DAYS.toMillis(365) -> {
            SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
        }
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}
