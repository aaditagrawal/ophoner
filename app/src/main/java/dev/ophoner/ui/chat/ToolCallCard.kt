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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ophoner.ui.components.IconMark
import dev.ophoner.ui.theme.LocalUiPrefs
import dev.ophoner.ui.components.propForTool
import dev.ophoner.ui.components.workingFaceForTool
import dev.ophoner.ui.components.MascotFace
import dev.ophoner.ui.components.Mascot
import dev.ophoner.ui.components.Lucide
import dev.ophoner.ui.components.LucideIcon
import dev.ophoner.ui.components.pressFade
import dev.ophoner.ui.theme.chatPalette
import dev.ophoner.ui.theme.monoFamily
import dev.ophoner.ui.theme.tokens
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val CardShape = RoundedCornerShape(18.dp)
private val DetailShape = RoundedCornerShape(12.dp)

@Composable
fun ToolCallCard(
    name: String,
    arguments: String,
    result: String?,
    isExecuting: Boolean,
    isError: Boolean,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val t = tokens()
    val palette = chatPalette()
    val toolIcon = remember(name) { propForTool(name) }
    val mascot = LocalUiPrefs.current.mascotEnabled
    // Large file_write arguments must not parse on the frame thread.
    val preview by produceState<String?>(null, arguments) {
        value = withContext(Dispatchers.Default) { argumentPreview(arguments) }
    }
    val hasDetails = (arguments.isNotEmpty() && arguments != "{}") || result != null

    val status = when {
        isExecuting -> "Running…"
        isError -> "Failed"
        else -> null
    }
    val subtitle = listOfNotNull(status, preview).joinToString(" · ").ifEmpty { "Done" }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(palette.toolCardBg)
            .border(1.dp, palette.toolCardBorder, CardShape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressFade(
                    enabled = !isExecuting && hasDetails,
                    onClickLabel = if (expanded) "Hide details" else "Show details",
                    onClick = { expanded = !expanded },
                )
                .padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mascot) {
                // The mascot acts out the call: working while it runs, pleased when done.
                Mascot(
                    size = 34.dp,
                    face = when {
                        isError -> MascotFace.Error
                        isExecuting -> workingFaceForTool(name)
                        else -> MascotFace.Happy
                    },
                    color = if (isError) t.danger else t.accent,
                    blink = false,
                    bounce = isExecuting,
                    prop = toolIcon,
                    modifier = Modifier.padding(start = 2.dp, end = 4.dp),
                )
            } else {
                IconMark(
                    icon = toolIcon,
                    size = 36.dp,
                    tint = if (isError) t.danger else t.accent,
                    background = if (isError) t.danger.copy(alpha = 0.12f) else t.active,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = displayNameForTool(name),
                    style = MaterialTheme.typography.labelLarge,
                    color = t.ink,
                    maxLines = 1,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isError) t.danger else t.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isExecuting && !mascot) {
                Spacer(Modifier.width(8.dp))
                LoadingIndicator(
                    modifier = Modifier.size(18.dp),
                    color = t.accent,
                )
            } else if (hasDetails && !isExecuting) {
                Spacer(Modifier.width(8.dp))
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = spring(stiffness = 600f),
                    label = "tool-chevron",
                )
                LucideIcon(
                    icon = Lucide.ChevronDown,
                    contentDescription = null,
                    tint = t.mutedIcon,
                    size = 17.dp,
                    modifier = Modifier.rotate(rotation),
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
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                    .fillMaxWidth()
                    .clip(DetailShape)
                    .background(palette.codeBlockBg)
                    .border(1.dp, palette.toolCardBorder, DetailShape)
                    .padding(12.dp),
            ) {
                val codeStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = monoFamily(),
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                )
                if (arguments.isNotEmpty() && arguments != "{}") {
                    Text(
                        text = remember(arguments) { formatJson(arguments) },
                        style = codeStyle,
                        color = t.secondary,
                        maxLines = 8,
                    )
                }
                if (result != null) {
                    if (arguments.isNotEmpty() && arguments != "{}") {
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        text = result.take(400).let { if (result.length > 400) "$it..." else it },
                        style = codeStyle,
                        color = if (isError) t.danger else palette.codeText,
                        maxLines = 12,
                    )
                }
            }
        }
    }
}

private fun displayNameForTool(name: String): String = when (name) {
    "file_read" -> "Read file"
    "file_write" -> "Write file"
    "file_list" -> "List folder"
    "file_delete" -> "Delete file"
    "file_move" -> "Move file"
    "web_search" -> "Web search"
    "web_fetch" -> "Fetch page"
    "shell_execute" -> "Shell command"
    "device_control" -> "Device control"
    "intent_launch" -> "Launch app"
    "app_list" -> "List apps"
    "skill_load" -> "Load skill"
    "shared_files" -> "Shared files"
    "screen_capture" -> "Look at screen"
    "screen_control" -> "Use screen"
    "device_capabilities" -> "Check device"
    "advanced_device" -> "Device settings"
    "phone_actions" -> "Phone"
    "calendar" -> "Calendar"
    "contacts" -> "Contacts"
    "location" -> "Location"
    "media" -> "Media"
    "notifications" -> "Notifications"
    else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private val PrettyJson = kotlinx.serialization.json.Json { prettyPrint = true }

private fun formatJson(json: String): String {
    // The card displays eight lines. Avoid formatting megabytes just to clip them.
    if (json.length > 4_000) return json.take(4_000) + "\n…"
    return try {
        val element = PrettyJson.parseToJsonElement(json)
        PrettyJson.encodeToString(JsonObject.serializer(), element.jsonObject)
    } catch (_: Exception) {
        json
    }
}

/** First string argument (command, path, url, query…) as a one-line summary. */
private fun argumentPreview(json: String): String? {
    if (json.isBlank() || json == "{}") return null
    return try {
        PrettyJson.parseToJsonElement(json).jsonObject.values
            .firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.lineSequence()?.firstOrNull()
            ?.take(80)
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}

/** A finished tool call, as persisted in a message. */
data class ToolCallEntry(
    val id: String,
    val name: String,
    val arguments: String,
    val result: String?,
    val isError: Boolean,
)

private val GroupShape = RoundedCornerShape(18.dp)

/**
 * Finished tool calls folded into one quiet row: the mascot, the props it used, and a
 * summary. Tapping expands the individual cards.
 */
@Composable
fun ToolCallGroup(calls: List<ToolCallEntry>) {
    if (calls.isEmpty()) return
    var expanded by rememberSaveable(calls.first().id) { mutableStateOf(false) }
    val t = tokens()
    val palette = chatPalette()
    val mascot = LocalUiPrefs.current.mascotEnabled
    val failed = calls.count { it.isError }
    val props = remember(calls) { calls.map { propForTool(it.name) }.distinct().take(4) }
    val title = if (calls.size == 1) displayNameForTool(calls.first().name) else "Used ${calls.size} tools"
    val subtitle = remember(calls, failed) {
        val names = calls.map { displayNameForTool(it.name) }.distinct().joinToString(", ")
        when {
            failed == calls.size && calls.size == 1 -> "Failed"
            failed > 0 -> "$failed failed · $names"
            calls.size == 1 -> "Done"
            else -> names
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GroupShape)
            .background(palette.toolCardBg)
            .border(1.dp, palette.toolCardBorder, GroupShape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressFade(
                    onClickLabel = if (expanded) "Hide tool calls" else "Show tool calls",
                    onClick = { expanded = !expanded },
                )
                .padding(start = 10.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mascot) {
                Mascot(
                    size = 28.dp,
                    face = if (failed > 0) MascotFace.Error else MascotFace.Happy,
                    color = if (failed > 0) t.danger else t.accent,
                    blink = false,
                )
            } else {
                IconMark(icon = props.first(), size = 30.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = t.ink,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(8.dp))
                    for (prop in props) {
                        LucideIcon(icon = prop, contentDescription = null, tint = t.accent, size = 13.dp)
                        Spacer(Modifier.width(4.dp))
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (failed > 0) t.danger else t.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            val rotation by animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                animationSpec = spring(stiffness = 600f),
                label = "group-chevron",
            )
            LucideIcon(
                icon = Lucide.ChevronDown,
                contentDescription = null,
                tint = t.mutedIcon,
                size = 17.dp,
                modifier = Modifier.rotate(rotation),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (call in calls) {
                    ToolCallCard(
                        name = call.name,
                        arguments = call.arguments,
                        result = call.result,
                        isExecuting = false,
                        isError = call.isError,
                    )
                }
            }
        }
    }
}
