package dev.ophoner.tools.impl

import dev.ophoner.tools.Tool
import dev.ophoner.tools.ToolExecutor
import dev.ophoner.tools.ToolOutputLimits
import dev.ophoner.tools.ToolResult
import dev.ophoner.tools.sandbox.FileAccessManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

class FileReadTool @Inject constructor(
    private val fileAccessManager: FileAccessManager,
) : ToolExecutor {
    override val definition = Tool(
        name = "file_read",
        description = "Read a file at the given relative path within the working directory. " +
            "Output is capped at ${ToolOutputLimits.FILE_READ_MAX_CHARS} characters; " +
            "use offset/limit to page through larger files.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Relative path to the file within the working directory")
                }
                putJsonObject("offset") {
                    put("type", "integer")
                    put("description", "Character offset to start reading from (default: 0)")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put(
                        "description",
                        "Maximum number of characters to return (default: up to ${ToolOutputLimits.FILE_READ_MAX_CHARS})",
                    )
                }
            }
            putJsonArray("required") { add(JsonPrimitive("path")) }
        },
    )

    override suspend fun execute(toolUseId: String, arguments: JsonObject): ToolResult {
        return try {
            val path = arguments["path"]?.jsonPrimitive?.content
                ?: return ToolResult(toolUseId, "Missing required parameter: path", isError = true)
            val offset = arguments["offset"]?.jsonPrimitive?.intOrNull ?: 0
            val limit = arguments["limit"]?.jsonPrimitive?.intOrNull

            validatePath(path)?.let { reason ->
                return ToolResult(
                    toolUseId,
                    "Error: path rejected by security validator: $reason",
                    isError = true,
                )
            }

            val result = fileAccessManager.readFile(
                relativePath = path,
                offset = offset,
                limit = limit,
            )
            val output = buildString {
                append(result.content)
                if (result.truncated) {
                    append("\n\n[file truncated at ${result.totalCharsRead} chars from offset ")
                    append(result.startedAtOffset)
                    append("; pass offset=")
                    append(result.startedAtOffset + result.totalCharsRead)
                    append(" to continue]")
                }
            }
            ToolResult(toolUseId, output)
        } catch (e: Exception) {
            ToolResult(toolUseId, "Error reading file: ${e.message}", isError = true)
        }
    }
}
