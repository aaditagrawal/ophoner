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
        description = "Execute a shell command on the Android device. Returns stdout, stderr, and exit code. Commands run in the app sandbox (no root).",
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
        if (allow.category == "unscoped" && !yolo) {
            writeAudit(command, "SOFT_BLOCK(yolo_required)")
            return ToolResult(
                toolUseId,
                "Error: command is not on the shell allowlist. Enable YOLO mode in Settings " +
                    "(Agent) to auto-allow unscoped shell commands, or use an allowlisted " +
                    "read-only command (ls, cat, grep, …).",
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
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return CommandDecision.Reject("empty command")
        if (trimmed.contains('\u0000')) return CommandDecision.Reject("null byte in command")

        // Denylist: patterns that are catastrophic regardless of context.
        for ((pattern, reason) in DENY_PATTERNS) {
            if (pattern.containsMatchIn(trimmed)) {
                return CommandDecision.Reject(reason)
            }
        }

        // Extract the first token — accounting for leading env assignments
        // and common prefixes like `sudo` or `env`.
        val firstToken = firstCommandToken(trimmed) ?: trimmed.substringBefore(' ')
        if (firstToken in ALLOW_FIRST_TOKENS) {
            return CommandDecision.Allow("allowlist")
        }

        // Not on allowlist and not on denylist — permit but mark as UNSCOPED
        // so the audit log distinguishes it from vetted commands.
        return CommandDecision.Allow("unscoped")
    }

    private fun firstCommandToken(command: String): String? {
        // Strip leading `VAR=value` assignments and a single `sudo`/`env` prefix.
        var rest = command.trimStart()
        while (true) {
            val space = rest.indexOf(' ')
            if (space <= 0) return rest.takeIf { it.isNotEmpty() }
            val head = rest.substring(0, space)
            if (head.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*=.*"))) {
                rest = rest.substring(space + 1).trimStart()
                continue
            }
            if (head == "sudo" || head == "env") {
                rest = rest.substring(space + 1).trimStart()
                continue
            }
            return head
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

    companion object {
        private val ALLOW_FIRST_TOKENS = setOf(
            "ls", "pwd", "cat", "grep", "find", "echo", "which", "ps", "cd",
            "head", "tail", "wc", "stat", "df", "du", "date", "uname", "env",
            "printenv", "whoami",
        )

        // Catastrophic patterns — matched anywhere in the command string.
        private val DENY_PATTERNS: List<Pair<Regex, String>> = listOf(
            Regex("""\brm\s+(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r)\s+/(\s|$)""") to
                "rm -rf targeting /",
            Regex("""\brm\s+-[a-zA-Z]*r[a-zA-Z]*f[a-zA-Z]*\s+/\*""") to
                "rm -rf targeting /*",
            Regex("""\bmkfs(\.|\s)""") to "mkfs (filesystem format)",
            Regex("""\bdd\s+[^|]*\bif=""") to "dd with input file",
            Regex(""":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:""") to "fork bomb",
            Regex("""\bchmod\s+(-[a-zA-Z]*\s+)?777\s+/(\s|$)""") to "chmod 777 on /",
            Regex("""\bchmod\s+-R\s+777\s+/""") to "recursive chmod 777 on /",
            Regex("""(^|[\s;&|`])>\s*/system/""") to "write into /system",
            Regex("""(^|[\s;&|`])>>\s*/system/""") to "append into /system",
            Regex("""\btee\s+(-[a-zA-Z]+\s+)?/system/""") to "tee into /system",
            Regex("""\bmount\s+""") to "mount operation",
            Regex("""\bumount\s+""") to "umount operation",
            Regex("""\breboot\b""") to "reboot",
            Regex("""\bshutdown\b""") to "shutdown",
            Regex("""\bhalt\b""") to "halt",
            Regex("""\bsu\s+(-|\w+)?""") to "privilege escalation (su)",
            // Writing into another app's private data directory.
            Regex("""(^|[\s;&|`>])(>|>>|tee\s+\S*\s+)?/data/data/(?!dev\.ophoner(\.debug)?(/|$))""") to
                "access to another app's /data/data",
            Regex("""(^|[\s;&|`>])(>|>>|tee\s+\S*\s+)?/data/user/\d+/(?!dev\.ophoner(\.debug)?(/|$))""") to
                "access to another app's /data/user",
        )
    }
}
