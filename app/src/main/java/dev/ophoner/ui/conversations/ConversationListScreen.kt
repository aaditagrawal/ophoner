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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ophoner.data.model.Conversation
import dev.ophoner.data.model.ConversationMode
import dev.ophoner.ui.components.FolderIconBadge
import dev.ophoner.ui.components.GroupedSection
import dev.ophoner.ui.util.formatFolderDisplayName
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// Hash palette for folder badges — one rule for the list; no accent conflict.
private val FolderPalette = listOf(
    Color(0xFF4F8FCC), // dusty blue
    Color(0xFFD96B6B), // dusty rose
    Color(0xFF6BAE7A), // sage
    Color(0xFFD9A24A), // amber-tan
    Color(0xFF5FA9AD), // muted teal
    Color(0xFF9B7BC4), // lavender
    Color(0xFFD58BAE), // dusty pink
    Color(0xFF7DA876), // moss
)

private fun folderColor(name: String): Color {
    // Safe non-negative modulo; abs(Int.MIN_VALUE) would overflow.
    val idx = ((name.hashCode().toLong() % FolderPalette.size) + FolderPalette.size) % FolderPalette.size
    return FolderPalette[idx.toInt()]
}

private val ControlCorner = RoundedCornerShape(10.dp)
private val RowCorner = RoundedCornerShape(10.dp)

@OptIn(ExperimentalMaterial3Api::class)
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

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val name = it.lastPathSegment?.replace("primary:", "/") ?: "folder"
            viewModel.pinFolder(it.toString(), name)
            onNewFolderConversation(it.toString(), name)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Chats",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TeSquareControl(
                    onClick = { folderPickerLauncher.launch(null) },
                    contentDescription = "New folder chat",
                ) {
                    Icon(
                        Icons.Outlined.CreateNewFolder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
                TePrimaryControl(
                    onClick = onNewConversation,
                    label = "New chat",
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val generalConvs = remember(conversations) {
            conversations.filter { it.mode == ConversationMode.GENERAL }
        }
        val projects = remember(conversations, pinnedFolders) {
            buildProjects(conversations, pinnedFolders)
        }

        if (generalConvs.isEmpty() && projects.isEmpty()) {
            EmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            val expandedFolders = remember { mutableStateMapOf<String, Boolean>() }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(padding),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 96.dp,
                    start = 16.dp,
                    end = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (projects.isNotEmpty()) {
                    item("header-projects") { SectionLabel("Projects") }
                    item("projects-group") {
                        GroupedSection {
                            Column {
                                for ((index, project) in projects.withIndex()) {
                                    val expanded = expandedFolders[project.key] ?: false
                                    val color = folderColor(project.name)
                                    FolderRow(
                                        name = formatFolderDisplayName(project.name),
                                        count = project.chats.size,
                                        color = color,
                                        expanded = expanded,
                                        onToggle = { expandedFolders[project.key] = !expanded },
                                        onNewChat = {
                                            onNewFolderConversation(project.uri, project.name)
                                        },
                                        onRemove = { viewModel.removeProject(project.uri) },
                                    )
                                    AnimatedVisibility(
                                        visible = expanded,
                                        enter = expandVertically(
                                            animationSpec = spring(
                                                stiffness = Spring.StiffnessMediumLow,
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                            ),
                                        ),
                                        exit = shrinkVertically(
                                            animationSpec = spring(
                                                stiffness = Spring.StiffnessMedium,
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                            ),
                                        ),
                                    ) {
                                        if (project.chats.isEmpty()) {
                                            EmptyProjectHint(accentColor = color)
                                        } else {
                                            for (conv in project.chats) {
                                                FolderChildRow(
                                                    conversation = conv,
                                                    accentColor = color,
                                                    onClick = { onOpenConversation(conv.id) },
                                                    onDelete = { viewModel.deleteConversation(conv.id) },
                                                )
                                            }
                                        }
                                    }
                                    if (index < projects.lastIndex && !expanded) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 58.dp),
                                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                            thickness = 0.5.dp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (generalConvs.isNotEmpty()) {
                    item("header-recents") { SectionLabel("Recents") }
                    item("recents-group") {
                        GroupedSection {
                            Column {
                                generalConvs.forEachIndexed { index, conv ->
                                    RecentRow(
                                        conversation = conv,
                                        onClick = { onOpenConversation(conv.id) },
                                        onDelete = { viewModel.deleteConversation(conv.id) },
                                    )
                                    if (index < generalConvs.lastIndex) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 16.dp),
                                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                            thickness = 0.5.dp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TePrimaryControl(
    onClick: () -> Unit,
    label: String,
) {
    Row(
        modifier = Modifier
            .clip(ControlCorner)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Outlined.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun TeSquareControl(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(ControlCorner)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                shape = ControlCorner,
            )
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "o",
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "No chats yet",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Start a new chat or open a folder to work with local files.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(
            letterSpacing = 0.6.sp,
            fontWeight = FontWeight.Medium,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
    )
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
            .clip(RowCorner)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FolderIconBadge(tint = color, expanded = expanded)
        Spacer(Modifier.width(12.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (expanded) {
            IconButton(
                onClick = onNewChat,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = "New chat in $name",
                    tint = color,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = {
                    if (confirmRemove) onRemove() else confirmRemove = true
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = if (confirmRemove) "Confirm remove" else "Remove project",
                    tint = if (confirmRemove) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        Icon(
            Icons.Outlined.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier
                .size(20.dp)
                .rotate(rotation),
        )
    }
}

@Composable
private fun EmptyProjectHint(accentColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 4.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(accentColor.copy(alpha = 0.45f)),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "No chats yet — tap + to start one",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
        )
    }
}

@Composable
private fun FolderChildRow(
    conversation: Conversation,
    accentColor: Color,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(accentColor.copy(alpha = 0.45f)),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RowCorner)
                .clickable(onClick = onClick)
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    conversation.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatRelativeDate(conversation.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                )
            }
            DeleteAction(key = conversation.id, onDelete = onDelete)
        }
    }
}

@Composable
private fun RecentRow(
    conversation: Conversation,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RowCorner)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                conversation.title.ifBlank { "Untitled" },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                formatRelativeDate(conversation.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            )
        }
        DeleteAction(key = conversation.id, onDelete = onDelete)
    }
}

@Composable
private fun DeleteAction(
    key: Any,
    onDelete: () -> Unit,
) {
    var confirm by rememberSaveable(key) { mutableStateOf(false) }
    LaunchedEffect(confirm) {
        if (confirm) {
            delay(3000)
            confirm = false
        }
    }
    IconButton(
        onClick = {
            if (confirm) {
                onDelete()
                confirm = false
            } else {
                confirm = true
            }
        },
        modifier = Modifier.size(36.dp),
    ) {
        Icon(
            Icons.Outlined.DeleteOutline,
            contentDescription = if (confirm) "Confirm delete" else "Delete",
            tint = if (confirm) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun formatRelativeDate(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "just now"
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
            if (days == 1L) "yesterday" else "${days}d ago"
        }
        diff < TimeUnit.DAYS.toMillis(365) -> {
            SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
        }
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}
