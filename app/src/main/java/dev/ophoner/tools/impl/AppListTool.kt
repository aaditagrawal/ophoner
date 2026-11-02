package dev.ophoner.tools.impl

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    private val catalog = LaunchableAppCache(
        locale = { context.resources.configuration.locales[0].toLanguageTag() },
        load = ::loadLaunchable,
    )
    private val invalidation = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            catalog.invalidate()
        }
    }

    init {
        val packages = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        register(packages)
        register(IntentFilter(Intent.ACTION_LOCALE_CHANGED))
    }

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
                val page = selectLaunchableApps(catalog.snapshot(), query, limit)
                val body = buildString {
                    appendLine("Launchable apps: showing ${page.shown.size} of ${page.total}")
                    if (query.isNotEmpty()) appendLine("Filter: \"$query\"")
                    appendLine()
                    for (app in page.shown) {
                        appendLine(app.label)
                        appendLine("  package: ${app.packageName}")
                        appendLine("  activity: ${app.activity}")
                    }
                    if (page.total > limit) {
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

    private fun loadLaunchable(): List<LaunchableApp> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        @Suppress("DEPRECATION")
        val resolveInfos = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        val seen = HashSet<String>(resolveInfos.size)
        val apps = ArrayList<LaunchableApp>(resolveInfos.size)
        for (info in resolveInfos) {
            val activityInfo = info.activityInfo ?: continue
            if (!seen.add(activityInfo.packageName + "/" + activityInfo.name)) continue
            val label = info.loadLabel(pm).toString().ifBlank { activityInfo.packageName }
            apps.add(LaunchableApp(label, activityInfo.packageName, activityInfo.name))
        }
        return apps
    }

    private fun register(filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(invalidation, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(invalidation, filter)
        }
    }

    companion object {
        private const val DEFAULT_LIMIT = 200
        private const val MAX_LIMIT = 500
    }
}
