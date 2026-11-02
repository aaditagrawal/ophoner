package dev.ophoner.tools.impl

import android.util.Log
import dev.ophoner.tools.Tool
import dev.ophoner.tools.ToolExecutionContext
import dev.ophoner.tools.ToolExecutor
import dev.ophoner.tools.ToolResult
import dev.ophoner.tools.sandbox.SandboxedShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

class ShellExecuteTool @Inject constructor(
    private val shell: SandboxedShell,
) : ToolExecutor {
    override val definition = Tool(
        name = "shell_execute",
        description = "Execute a shell command. Uses Shizuku's shell identity when enabled and connected, otherwise the app sandbox. No root required. Returns execution mode, stdout, stderr, and exit code. Disabled in folder chats. Commands require approval unless YOLO is enabled.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("command") {
                    put("type", "string")
                    put("description", "The shell command to run")
                }
                putJsonObject("timeout_ms") {
                    put("type", "integer")
                    put("description", "Timeout in milliseconds (default: 30000)")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("command")) }
        },
    )

    // Soft-block: unscoped commands require YOLO mode (or a future confirmation UI).
    // Hard denylist always applies, even in YOLO mode.
    override suspend fun execute(toolUseId: String, arguments: JsonObject): ToolResult {
        val command = arguments["command"]?.jsonPrimitive?.content
            ?: return ToolResult(toolUseId, "Missing required parameter: command", isError = true)
        val timeout = arguments["timeout_ms"]?.jsonPrimitive?.long ?: 30_000L
        val yolo = currentCoroutineContext()[ToolExecutionContext]?.yoloMode == true
        val context = currentCoroutineContext()[ToolExecutionContext]
        if (!context?.rootUri.isNullOrBlank()) return ToolResult(toolUseId, "Shell is disabled in folder chats. Use scoped file tools or start a general chat.", true)

        // Policy check before any execution.
        val decision = classifyCommand(command)
        if (decision is CommandDecision.Reject) {
            writeAudit(command, "REJECT(${decision.reason})")
            return ToolResult(
                toolUseId,
                "Error: command rejected by security validator: ${decision.reason}",
                isError = true,
            )
        }

        val allow = decision as CommandDecision.Allow
        if (!yolo && context?.actionApproved != true) {
            writeAudit(command, "SOFT_BLOCK(yolo_required)")
            return ToolResult(
                toolUseId,
                "Error: shell execution needs approval. Run through the agent approval flow or enable YOLO mode.",
                isError = true,
            )
        }

        val auditCategory = if (allow.category == "unscoped" && yolo) "yolo/unscoped" else allow.category
        return try {
            writeAudit(command, "ALLOW($auditCategory)")
            val result = shell.execute(command, timeoutMs = timeout)
            val modeTag = if (result.privileged) "[shizuku]" else "[sandbox]"
            val output = buildString {
                append("$modeTag ")
                if (result.stdout.isNotEmpty()) append(result.stdout)
                if (result.stderr.isNotEmpty()) {
                    if (length > modeTag.length + 1) append("\n")
                    append("[stderr] ${result.stderr}")
                }
                if (length == modeTag.length + 1) append("(no output)")
                append("\n[exit code: ${result.exitCode}]")
            }
            ToolResult(toolUseId, output, isError = result.exitCode != 0)
        } catch (e: Exception) {
            writeAudit(command, "ERROR(${e.javaClass.simpleName})")
            ToolResult(toolUseId, "Error executing command: ${e.message}", isError = true)
        }
    }

    private sealed class CommandDecision {
        data class Allow(val category: String) : CommandDecision()
        data class Reject(val reason: String) : CommandDecision()
    }

    private fun classifyCommand(command: String): CommandDecision {
        return when (val decision = ShellPolicy.classify(command)) {
            is ShellPolicy.Decision.Allow -> CommandDecision.Allow(decision.category)
            is ShellPolicy.Decision.Reject -> CommandDecision.Reject(decision.reason)
        }
    }

    private suspend fun writeAudit(command: String, outcome: String) {
        // Best-effort audit logging — failures must not block execution.
        withContext(Dispatchers.IO) {
            try {
                val dir = resolveAuditDir() ?: return@withContext
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "shell_audit.log")
                val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
                val sanitized = command.replace('\n', ' ').replace('\r', ' ')
                FileWriter(file, /* append = */ true).use { writer ->
                    writer.append(timestamp)
                    writer.append('\t')
                    writer.append(outcome)
                    writer.append('\t')
                    writer.append(sanitized)
                    writer.append('\n')
                }
            } catch (t: Throwable) {
                // Swallow — logging must never block execution or surface to the LLM.
                Log.w("ShellExecuteTool", "audit log write failed: ${t.message}")
            }
        }
    }

    private fun resolveAuditDir(): File? {
        // We don't take a Context through the constructor (ToolModule constructs
        // this class manually and is out of scope for this change). Use the
        // process-wide Application handle via ActivityThread — a standard
        // Android platform API — to reach getExternalFilesDir(null). If the
        // application hasn't attached yet, fall through and drop the log line.
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val app = activityThread.getMethod("currentApplication").invoke(null)
                as? android.app.Application ?: return null
            app.getExternalFilesDir(null)
        } catch (_: Throwable) {
            null
        }
    }
}
