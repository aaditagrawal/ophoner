package dev.ophoner.tools.impl

import dev.ophoner.tools.Tool
import dev.ophoner.tools.ToolExecutor
import dev.ophoner.tools.ToolResult
import dev.ophoner.tools.sandbox.FileAccessManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

class FileWriteTool @Inject constructor(
    private val fileAccessManager: FileAccessManager,
) : ToolExecutor {
    override val definition = Tool(
        name = "file_write",
        description = "Write content to a file at the given relative path (creates or overwrites)",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Relative path to the file within the working directory")
                }
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "Content to write to the file")
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("path"))
                add(JsonPrimitive("content"))
            }
        },
    )

    override suspend fun execute(toolUseId: String, arguments: JsonObject): ToolResult {
        return try {
            val path = arguments["path"]?.jsonPrimitive?.content
                ?: return ToolResult(toolUseId, "Missing required parameter: path", isError = true)
            val content = arguments["content"]?.jsonPrimitive?.content
                ?: return ToolResult(toolUseId, "Missing required parameter: content", isError = true)

            // Defense-in-depth: validate path before handing off to FileAccessManager.
            // FileAccessManager already scopes writes to a user-selected DocumentFile
            // tree via SAF, but we reject obviously dangerous inputs early.
            validatePath(path)?.let { reason ->
                return ToolResult(
                    toolUseId,
                    "Error: path rejected by security validator: $reason",
                    isError = true,
                )
            }

            fileAccessManager.writeFile(path, content)
            ToolResult(toolUseId, "File written: $path (${content.length} chars)")
        } catch (e: Exception) {
            ToolResult(toolUseId, "Error writing file: ${e.message}", isError = true)
        }
    }
}
