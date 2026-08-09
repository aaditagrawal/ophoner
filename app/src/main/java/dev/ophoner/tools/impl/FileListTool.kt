package dev.ophoner.tools.impl

import dev.ophoner.tools.Tool
import dev.ophoner.tools.ToolExecutor
import dev.ophoner.tools.ToolOutputLimits
import dev.ophoner.tools.ToolResult
import dev.ophoner.tools.sandbox.FileAccessManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

class FileListTool @Inject constructor(
    private val fileAccessManager: FileAccessManager,
) : ToolExecutor {
    override val definition = Tool(
        name = "file_list",
        description = "List files and directories at a relative path within the active working directory (folder-scoped when in folder mode). Output is capped for large trees.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Relative path to list (use '.' or empty for root)")
                }
                putJsonObject("recursive") {
                    put("type", "boolean")
                    put("description", "If true, list recursively")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("path")) }
        },
    )

    override suspend fun execute(toolUseId: String, arguments: JsonObject): ToolResult {
        return try {
            val path = arguments["path"]?.jsonPrimitive?.content ?: "."
            val recursive = arguments["recursive"]?.jsonPrimitive?.boolean ?: false
            validatePath(path)?.let { reason ->
                return ToolResult(
                    toolUseId,
                    "Error: path rejected by security validator: $reason",
                    isError = true,
                )
            }
            val listed = fileAccessManager.listDirectory(
                path,
                recursive,
                maxEntries = ToolOutputLimits.FILE_LIST_MAX_ENTRIES,
            )
            val body = listed.files.joinToString("\n") { info ->
                val prefix = if (info.isDirectory) "[DIR] " else "      "
                val size = if (info.isDirectory) "" else " (${info.size} bytes)"
                "$prefix${info.path}$size"
            }
            val text = when {
                body.isEmpty() -> "(empty directory)"
                listed.truncated -> ToolOutputLimits.truncateWithNotice(
                    body + "\n\n[listing truncated at ${ToolOutputLimits.FILE_LIST_MAX_ENTRIES} entries]",
                    ToolOutputLimits.FILE_LIST_MAX_CHARS,
                    "file_list",
                )
                else -> ToolOutputLimits.truncateWithNotice(
                    body,
                    ToolOutputLimits.FILE_LIST_MAX_CHARS,
                    "file_list",
                )
            }
            ToolResult(toolUseId, text)
        } catch (e: Exception) {
            ToolResult(toolUseId, "Error listing directory: ${e.message}", isError = true)
        }
    }
}
