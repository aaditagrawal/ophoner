package dev.ophoner.tools.impl

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.ophoner.tools.Tool
import dev.ophoner.tools.ToolExecutor
import dev.ophoner.tools.ToolOutputLimits
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
 * List installed launchable apps via ACTION_MAIN / CATEGORY_LAUNCHER.
 * Does not require QUERY_ALL_PACKAGES.
 */
class AppListTool @Inject constructor(
    private val context: Context,
) : ToolExecutor {

    override val definition = Tool(
        name = "app_list",
        description = "List installed apps that have a launcher entry (label, package, activity). " +
            "Optionally filter by a case-insensitive substring match on label or package name.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put(
                        "description",
                        "Optional filter matched against app label or package name (case-insensitive)",
                    )
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Max apps to return (default 200, max 500)")
                }
            }
        },
    )

    override suspend fun execute(toolUseId: String, arguments: JsonObject): ToolResult {
        val query = arguments["query"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty()
        val limitArg = arguments["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: DEFAULT_LIMIT
        val limit = limitArg.coerceIn(1, MAX_LIMIT)

        return withContext(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PackageManager.MATCH_ALL
                } else {
                    0
                }
                @Suppress("DEPRECATION")
                val resolveInfos = pm.queryIntentActivities(launcherIntent, flags)

                val apps = resolveInfos.mapNotNull { ri ->
                    val activityInfo = ri.activityInfo ?: return@mapNotNull null
                    val packageName = activityInfo.packageName
                    val activityName = activityInfo.name
                    val label = ri.loadLabel(pm)?.toString()?.ifBlank { packageName } ?: packageName
                    AppEntry(label = label, packageName = packageName, activity = activityName)
                }
                    .distinctBy { "${it.packageName}/${it.activity}" }
                    .sortedBy { it.label.lowercase() }
                    .let { list ->
                        if (query.isEmpty()) list
                        else list.filter {
                            it.label.lowercase().contains(query) ||
                                it.packageName.lowercase().contains(query)
                        }
                    }

                val total = apps.size
                val truncated = apps.take(limit)
                val body = buildString {
                    appendLine("Launchable apps: showing ${truncated.size} of $total")
                    if (query.isNotEmpty()) appendLine("Filter: \"$query\"")
                    appendLine()
                    for (app in truncated) {
                        appendLine("${app.label}")
                        appendLine("  package: ${app.packageName}")
                        appendLine("  activity: ${app.activity}")
                    }
                    if (total > limit) {
                        appendLine()
                        append("[truncated to $limit apps; pass a higher limit or a query filter]")
                    }
                }
                val output = ToolOutputLimits.truncateWithNotice(
                    body.trimEnd(),
                    ToolOutputLimits.FILE_LIST_MAX_CHARS,
                    label = "app_list",
                )
                ToolResult(toolUseId, output)
            } catch (e: Exception) {
                ToolResult(toolUseId, "Error listing apps: ${e.message}", isError = true)
            }
        }
    }

    private data class AppEntry(
        val label: String,
        val packageName: String,
        val activity: String,
    )

    companion object {
        private const val DEFAULT_LIMIT = 200
        private const val MAX_LIMIT = 500
    }
}
