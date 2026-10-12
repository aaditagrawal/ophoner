package dev.ophoner.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Execution policy stays local to the tool contract, not scattered through the loop. */
data class ToolPolicy(
    val parallelSafe: Boolean = false,
    val timeoutMillis: Long = 60_000,
    val ownsPhoneUi: Boolean = false,
    val requiresApproval: Boolean = false,
    val readActions: Set<String>? = null,
) {
    fun needsApproval(arguments: JsonObject): Boolean = requiresApproval ||
        (readActions != null && (arguments["action"] as? JsonPrimitive)?.content !in readActions)
}

/** Defaults for built-ins. Custom tools can supply their own policy on [Tool]. */
object ToolPolicies {
    fun forTool(name: String): ToolPolicy = when (name) {
        "file_read", "file_list", "web_fetch", "web_search", "app_list", "skill_load" ->
            ToolPolicy(parallelSafe = true)
        "shell_execute" -> ToolPolicy(timeoutMillis = 180_000, ownsPhoneUi = true, requiresApproval = true)
        "file_write", "file_delete", "file_move" -> ToolPolicy(requiresApproval = true)
        "screen_control", "screen_capture", "device_control", "intent_launch", "phone_actions" ->
            ToolPolicy(ownsPhoneUi = true)
        "shared_files" -> ToolPolicy(readActions = setOf("list", "read"))
        "contacts" -> ToolPolicy(readActions = setOf("search"))
        "calendar" -> ToolPolicy(readActions = setOf("calendars", "events"))
        "notifications" -> ToolPolicy(ownsPhoneUi = true, readActions = setOf("list"))
        "advanced_device" -> ToolPolicy(ownsPhoneUi = true,
            readActions = setOf("identity", "get_setting", "list_packages", "foreground_app", "diagnostics"))
        else -> ToolPolicy()
    }
}
