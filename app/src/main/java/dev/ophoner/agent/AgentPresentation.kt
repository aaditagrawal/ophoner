package dev.ophoner.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Shared by the chat, accessibility overlay and foreground notification. */
object AppVisibility { val visible = MutableStateFlow(false) }

fun AgentRunState.statusLabel(): String = when {
    !isRunning -> error?.let { "Stopped with an error" } ?: when (stopReason) {
        AgentStopReason.COMPLETED -> "Task complete"
        AgentStopReason.CANCELLED -> "Stopped"
        else -> "Task ended"
    }
    isPaused -> "Paused"
    pauseRequested -> "Pausing after current action"
    retryAttempt > 0 -> "Reconnecting · attempt $retryAttempt"
    activeToolCalls.any { it.isExecuting } -> {
        val tool = activeToolCalls.first { it.isExecuting }
        val action = runCatching { Json.parseToJsonElement(tool.arguments).jsonObject["action"]?.jsonPrimitive?.content }.getOrNull()
        when (tool.name) {
            "screen_control" -> when (action) {
                "read" -> "Reading the screen"
                "set_text" -> "Entering text"
                "click", "tap" -> "Tapping a control"
                "scroll_forward", "scroll_backward", "swipe" -> "Scrolling"
                "back", "home", "recents" -> "Navigating"
                else -> "Using the phone"
            }
            "screen_capture" -> "Looking at the screen"
            "contacts" -> "Working with contacts"
            "calendar" -> "Working with your calendar"
            "notifications" -> "Working with notifications"
            "web_search" -> "Searching the web"
            "shell_execute", "advanced_device" -> "Running a device action"
            else -> tool.name.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }
    streamingText.isNotBlank() -> "Responding"
    else -> "Thinking"
}
