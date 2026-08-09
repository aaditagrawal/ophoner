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

class FileMoveTool @Inject constructor(
    private val fileAccessManager: FileAccessManager,
) : ToolExecutor {
    override val definition = Tool(
        name = "file_move",
        description = "Move or rename a file within the working directory (copy then delete source)",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("from") {
                    put("type", "string")
                    put("description", "Relative source path within the working directory")
                }
                putJsonObject("to") {
                    put("type", "string")
                    put("description", "Relative destination path within the working directory")
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("from"))
                add(JsonPrimitive("to"))
            }
        },
    )

    override suspend fun execute(toolUseId: String, arguments: JsonObject): ToolResult {
        return try {
            val from = arguments["from"]?.jsonPrimitive?.content
                ?: return ToolResult(toolUseId, "Missing required parameter: from", isError = true)
            val to = arguments["to"]?.jsonPrimitive?.content
                ?: return ToolResult(toolUseId, "Missing required parameter: to", isError = true)

            validatePath(from)?.let { reason ->
                return ToolResult(
                    toolUseId,
                    "Error: source path rejected by security validator: $reason",
                    isError = true,
                )
            }
            validatePath(to)?.let { reason ->
                return ToolResult(
                    toolUseId,
                    "Error: destination path rejected by security validator: $reason",
                    isError = true,
                )
            }

            fileAccessManager.moveFile(from, to)
            ToolResult(toolUseId, "Moved: $from -> $to")
        } catch (e: Exception) {
            ToolResult(toolUseId, "Error moving file: ${e.message}", isError = true)
        }
    }
}
