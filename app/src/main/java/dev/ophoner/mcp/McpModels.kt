package dev.ophoner.mcp

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.security.MessageDigest

@Serializable
data class McpToolSpec(val name: String, val description: String = "", val inputSchema: JsonObject)

@Serializable
data class McpServer(
    val name: String,
    val url: String = "",
    val tools: List<McpToolSpec> = emptyList(),
    val refreshedAt: Long = 0,
    val activeTools: List<String> = emptyList(),
    val registrationId: String = "",
    val transport: String = "http",
    val command: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val oauth: Boolean = false,
    val authorizationMetadataUrl: String? = null,
    val clientId: String? = null,
    val scope: String? = null,
)

class McpAuthorizationRequired(val challenge: String? = null) : IOException("MCP sign-in required. Use mcp_manage authenticate for this server. The tool call was not retried.")

internal fun validateStdio(command: List<String>, directory: String?) {
    require(command.isNotEmpty() && command.size <= 64 && command.first().startsWith("/")) { "Stdio requires an absolute executable path and at most 63 arguments." }
    require(command.all { it.length <= 4096 && '\u0000' !in it } && command.sumOf { it.length } <= 32_768) { "Invalid or oversized stdio command." }
    require(directory == null || (directory.startsWith("/") && directory.length <= 4096 && '\u0000' !in directory)) { "Stdio working directory must be an absolute path." }
}

/** Small persistence boundary, replaceable in JVM tests. Session IDs are never persisted. */
interface McpStore {
    fun read(): String?
    fun write(value: String)
}

internal class PreferencesMcpStore(context: Context) : McpStore {
    private val prefs = context.getSharedPreferences("mcp_servers", Context.MODE_PRIVATE)
    override fun read(): String? = prefs.getString("catalog", null)
    override fun write(value: String) {
        if (!prefs.edit().putString("catalog", value).commit()) throw IOException("Could not persist MCP server configuration.")
    }
}

internal fun validateMcpName(name: String) {
    require(name.matches(Regex("[a-z][a-z0-9_-]{0,23}"))) {
        "MCP server name must be 1-24 lowercase letters, digits, underscores or hyphens, starting with a letter."
    }
}

internal fun validateMcpUrl(value: String): String {
    val url = value.toHttpUrl()
    require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) {
        "MCP URLs must not contain embedded credentials or fragments."
    }
    require(url.isHttps || (url.scheme == "http" && url.host in setOf("localhost", "127.0.0.1", "::1"))) {
        "MCP requires HTTPS, except HTTP on localhost or a loopback address."
    }
    require(value.length <= 2048) { "MCP URL is too long." }
    return url.toString()
}

/** The digest distinguishes original names that sanitize to the same provider-safe spelling. */
internal fun mcpToolName(server: String, tool: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest("$server\u0000$tool".toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }
    val suffix = tool.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(18)
    return "mcp_${server}_${suffix}_$digest"
}
