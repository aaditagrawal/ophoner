package dev.ophoner.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ophoner.ui.theme.AccentAmber
import dev.ophoner.ui.theme.AccentRed
import dev.ophoner.ui.theme.chatPalette
import dev.ophoner.ui.theme.monoFamily
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private val ToolRadius = 8.dp

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
    val shape = RoundedCornerShape(ToolRadius)
    val mute = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val toolIcon = remember(name) { iconForTool(name) }

    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(palette.toolCardBg)
                .border(1.dp, palette.toolCardBorder, shape)
                .clickable { if (!isExecuting) expanded = !expanded }
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(
                        when {
                            isExecuting -> AccentAmber
                            isError -> AccentRed
                            else -> mute
                        },
                    ),
            )
            Spacer(Modifier.width(10.dp))
            Icon(
                imageVector = toolIcon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(Modifier.width(8.dp))

            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = monoFamily(),
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )

            if (isExecuting) {
                Spacer(Modifier.width(8.dp))
                LoadingIndicator(
                    modifier = Modifier.size(14.dp),
                    color = AccentAmber,
                )
            } else if (arguments.isNotEmpty() || result != null) {
                Spacer(Modifier.width(8.dp))
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = spring(stiffness = 600f),
                    label = "tool-chevron",
                )
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(rotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
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
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(palette.codeBlockBg)
                    .border(1.dp, palette.toolCardBorder.copy(alpha = 0.6f), shape)
                    .padding(11.dp),
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

private fun iconForTool(name: String): ImageVector = when (name) {
    "file_read" -> Icons.Outlined.Description
    "file_write" -> Icons.Outlined.EditNote
    "file_list" -> Icons.Outlined.FolderOpen
    "file_delete" -> Icons.Outlined.DeleteOutline
    "file_move" -> Icons.AutoMirrored.Outlined.DriveFileMove
    "web_search" -> Icons.Outlined.Search
    "web_fetch" -> Icons.Outlined.Language
    "shell_execute" -> Icons.Outlined.Terminal
    "device_control" -> Icons.Outlined.PhoneAndroid
    else -> Icons.Outlined.Build
}

private val PrettyJson = kotlinx.serialization.json.Json { prettyPrint = true }

private fun formatJson(json: String): String {
    return try {
        val element = PrettyJson.parseToJsonElement(json)
        PrettyJson.encodeToString(JsonObject.serializer(), element.jsonObject)
    } catch (_: Exception) {
        json
    }
}
