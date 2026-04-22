package dev.ophoner.tools.impl

import dev.ophoner.tools.Tool
import dev.ophoner.tools.ToolExecutor
import dev.ophoner.tools.ToolResult
import dev.ophoner.tools.sandbox.FileAccessManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import javax.inject.Inject

class FileReadTool @Inject constructor(
    private val fileAccessManager: FileAccessManager,
) : ToolExecutor {
    override val definition = Tool(
        name = "file_read",
        description = "Read the full contents of a file at the given relative path within the working directory",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Relative path to the file within the working directory")
                }
            }
            putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("path")) }
        },
    )

    override suspend fun execute(toolUseId: String, arguments: JsonObject): ToolResult {
        return try {
            val path = arguments["path"]?.jsonPrimitive?.content
                ?: return ToolResult(toolUseId, "Missing required parameter: path", isError = true)

            validatePath(path)?.let { reason ->
                return ToolResult(
                    toolUseId,
                    "Error: path rejected by security validator: $reason",
                    isError = true,
                )
            }

            val content = fileAccessManager.readFile(path)
            ToolResult(toolUseId, content)
        } catch (e: Exception) {
            ToolResult(toolUseId, "Error reading file: ${e.message}", isError = true)
        }
    }
}

/**
 * Defense-in-depth path validation layered on top of FileAccessManager.
 * FileAccessManager resolves paths as relative segments under a user-selected
 * DocumentFile tree, so traversal outside the tree is already blocked by the
 * SAF URI sandbox. These checks add a fast-fail layer against obviously bad
 * inputs before we reach the content resolver.
 *
 * Returns null when the path is acceptable, or a human-readable reason when
 * it must be rejected.
 */
internal fun validatePath(rawPath: String): String? {
    if (rawPath.contains('\u0000')) return "path contains null byte"
    if (rawPath.isBlank()) return "path is blank"

    // Reject absolute paths that point at known-sensitive Android/Linux mounts.
    val forbiddenPrefixes = listOf(
        "/system",
        "/proc",
        "/sys",
        "/vendor",
        "/apex",
        "/dev",
    )
    for (prefix in forbiddenPrefixes) {
        if (rawPath == prefix || rawPath.startsWith("$prefix/")) {
            return "path points into sensitive system location ($prefix)"
        }
    }

    // Block access into other apps' private data directories.
    val appDataPrefixes = listOf("/data/data/", "/data/user/0/", "/data/user/")
    for (prefix in appDataPrefixes) {
        if (rawPath.startsWith(prefix)) {
            val remainder = rawPath.removePrefix(prefix)
            val pkg = remainder.substringBefore('/')
            if (pkg.isNotEmpty() && pkg != "dev.ophoner" && pkg != "dev.ophoner.debug") {
                return "path points into another app's private data ($pkg)"
            }
        }
    }

    // Normalize and check for escaping traversal segments. FileAccessManager
    // treats paths as tree-relative, but guard against callers passing raw
    // absolute paths with ../ segments that would escape after canonicalization.
    val canonical = try {
        File(rawPath).canonicalPath
    } catch (_: Exception) {
        return "path could not be canonicalized"
    }
    if (canonical.contains("/../") || canonical.endsWith("/..")) {
        return "path contains traversal segments after normalization"
    }
    for (prefix in forbiddenPrefixes) {
        if (canonical == prefix || canonical.startsWith("$prefix/")) {
            return "canonical path resolves into sensitive system location ($prefix)"
        }
    }

    return null
}
