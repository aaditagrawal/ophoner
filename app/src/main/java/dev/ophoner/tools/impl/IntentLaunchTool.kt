package dev.ophoner.tools.impl

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dev.ophoner.tools.Tool
import dev.ophoner.tools.ToolExecutor
import dev.ophoner.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

/**
 * Launch Android intents: open URLs, share text, start packages/activities,
 * or open common system settings panels.
 */
class IntentLaunchTool @Inject constructor(
    private val context: Context,
) : ToolExecutor {

    override val definition = Tool(
        name = "intent_launch",
        description = buildString {
            append("Launch an Android intent. Actions: open_url (browser/handler), ")
            append("share_text (system share sheet), launch_app (package or package/activity), ")
            append("open_panel (system settings: wifi, bluetooth, location, nfc, airplane, ")
            append("display, sound, apps, settings).")
        },
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    put(
                        "description",
                        "open_url | share_text | launch_app | open_panel",
                    )
                    putJsonArray("enum") {
                        add(JsonPrimitive("open_url"))
                        add(JsonPrimitive("share_text"))
                        add(JsonPrimitive("launch_app"))
                        add(JsonPrimitive("open_panel"))
                    }
                }
                putJsonObject("url") {
                    put("type", "string")
                    put("description", "URL for open_url (http/https/mailto/tel/…)")
                }
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "Text for share_text")
                }
                putJsonObject("package_name") {
                    put("type", "string")
                    put("description", "Application package for launch_app (e.g. com.android.chrome)")
                }
                putJsonObject("activity") {
                    put("type", "string")
                    put(
                        "description",
                        "Optional fully-qualified activity class for launch_app " +
                            "(e.g. com.android.chrome.Main). If omitted, launches the package's launcher activity.",
                    )
                }
                putJsonObject("panel") {
                    put("type", "string")
                    put(
                        "description",
                        "System panel for open_panel: wifi, bluetooth, location, nfc, " +
                            "airplane, display, sound, apps, settings",
                    )
                    putJsonArray("enum") {
                        add(JsonPrimitive("wifi"))
                        add(JsonPrimitive("bluetooth"))
                        add(JsonPrimitive("location"))
                        add(JsonPrimitive("nfc"))
                        add(JsonPrimitive("airplane"))
                        add(JsonPrimitive("display"))
                        add(JsonPrimitive("sound"))
                        add(JsonPrimitive("apps"))
                        add(JsonPrimitive("settings"))
                    }
                }
            }
            putJsonArray("required") { add(JsonPrimitive("action")) }
        },
    )

    override suspend fun execute(toolUseId: String, arguments: JsonObject): ToolResult {
        val action = arguments["action"]?.jsonPrimitive?.content
            ?: return ToolResult(toolUseId, "Missing required parameter: action", isError = true)

        return withContext(Dispatchers.Main) {
            try {
                val output = when (action) {
                    "open_url" -> openUrl(arguments["url"]?.jsonPrimitive?.content)
                    "share_text" -> shareText(arguments["text"]?.jsonPrimitive?.content)
                    "launch_app" -> launchApp(
                        packageName = arguments["package_name"]?.jsonPrimitive?.content,
                        activity = arguments["activity"]?.jsonPrimitive?.content,
                    )
                    "open_panel" -> openPanel(arguments["panel"]?.jsonPrimitive?.content)
                    else -> return@withContext ToolResult(
                        toolUseId,
                        "Unknown action: $action",
                        isError = true,
                    )
                }
                if (output.startsWith("Error:")) {
                    ToolResult(toolUseId, output, isError = true)
                } else {
                    ToolResult(toolUseId, output)
                }
            } catch (e: ActivityNotFoundException) {
                ToolResult(toolUseId, "Error: no activity found to handle intent: ${e.message}", isError = true)
            } catch (e: Exception) {
                ToolResult(toolUseId, "Error launching intent: ${e.message}", isError = true)
            }
        }
    }

    private fun openUrl(url: String?): String {
        if (url.isNullOrBlank()) return "Error: missing 'url' parameter."
        val uri = runCatching { Uri.parse(url.trim()) }.getOrNull()
            ?: return "Error: invalid URL."
        if (uri.scheme.isNullOrBlank()) {
            return "Error: URL must include a scheme (e.g. https://)."
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Opened URL: $url"
    }

    private fun shareText(text: String?): String {
        if (text.isNullOrBlank()) return "Error: missing 'text' parameter."
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, "Share via").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        return "Opened share sheet (${text.length} chars)."
    }

    private fun launchApp(packageName: String?, activity: String?): String {
        if (packageName.isNullOrBlank()) return "Error: missing 'package_name' parameter."
        val intent = if (!activity.isNullOrBlank()) {
            Intent().apply {
                setClassName(packageName, activity)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            context.packageManager.getLaunchIntentForPackage(packageName)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                ?: return "Error: no launchable activity for package '$packageName'."
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            return "Error: cannot resolve activity for $packageName" +
                (activity?.let { "/$it" } ?: "")
        }
        context.startActivity(intent)
        return if (!activity.isNullOrBlank()) {
            "Launched $packageName/$activity"
        } else {
            "Launched package $packageName"
        }
    }

    private fun openPanel(panel: String?): String {
        if (panel.isNullOrBlank()) return "Error: missing 'panel' parameter."
        val action = PANEL_ACTIONS[panel.lowercase()]
            ?: return "Error: unknown panel '$panel'. Use wifi, bluetooth, location, nfc, " +
                "airplane, display, sound, apps, or settings."
        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Opened system panel: $panel"
    }

    companion object {
        private val PANEL_ACTIONS = mapOf(
            "wifi" to Settings.ACTION_WIFI_SETTINGS,
            "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            "nfc" to Settings.ACTION_NFC_SETTINGS,
            "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
            "display" to Settings.ACTION_DISPLAY_SETTINGS,
            "sound" to Settings.ACTION_SOUND_SETTINGS,
            "apps" to Settings.ACTION_APPLICATION_SETTINGS,
            "settings" to Settings.ACTION_SETTINGS,
        )
    }
}
