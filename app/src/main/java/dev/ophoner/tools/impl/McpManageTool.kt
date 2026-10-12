package dev.ophoner.tools.impl

import dev.ophoner.mcp.McpManager
import dev.ophoner.tools.Tool
import dev.ophoner.tools.ToolExecutionContext
import dev.ophoner.tools.ToolExecutor
import dev.ophoner.tools.ToolPolicy
import dev.ophoner.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

/** Manage remote Streamable HTTP MCP servers through the same approval flow as other actions. */
class McpManageTool @Inject constructor(private val manager: McpManager) : ToolExecutor {
    override val definition = Tool(
        name = "mcp_manage",
        description = "Manage MCP servers. Add HTTP with name/url, or stdio with transport=stdio and command array (absolute executable then arguments). Stdio runs as the Android app, not a privileged shell; runtimes must already be installed and accessible. Protected HTTP servers require authenticate, which opens browser sign-in; never request tokens or authorization codes in chat. Optional client_id supports pre-registered public OAuth clients. Logout removes local OAuth tokens. Add activates up to 8 tools within capacity. Use tools (optional query) to discover cached tool names, then select with full names to replace the active set for the next model turn; include every tool you want to keep active. Selection changes context only; actual remote calls require approval. Only add servers requested by the user.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") { listOf("list", "add", "remove", "refresh", "tools", "select", "authenticate", "logout").forEach { add(JsonPrimitive(it)) } }
                }
                putJsonObject("name") { put("type", "string"); put("description", "Server name; required for add/remove, optional for refresh") }
                putJsonObject("url") { put("type", "string"); put("description", "Streamable HTTP endpoint; required for add") }
                putJsonObject("transport") { put("type", "string"); putJsonArray("enum") { add(JsonPrimitive("http")); add(JsonPrimitive("stdio")) } }
                putJsonObject("command") { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", "Absolute executable path followed by arguments; no implicit shell") }
                putJsonObject("cwd") { put("type", "string"); put("description", "Absolute local working directory for stdio") }
                putJsonObject("env") { put("type", "object"); putJsonObject("additionalProperties") { put("type", "string") }; put("description", "Environment overrides for stdio. Tool arguments are recorded in chat; do not put secrets here.") }
                putJsonObject("client_id") { put("type", "string"); put("description", "Optional pre-registered public OAuth client ID; otherwise dynamic registration is used") }
                putJsonObject("scope") { put("type", "string"); put("description", "Optional OAuth scope override") }
                putJsonObject("query") { put("type", "string"); put("description", "Optional text filter for tools: searches full tool names and descriptions") }
                putJsonObject("names") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "Full tool names from tools; replaces the active set (maximum 16, subject to schema size). Empty array disables all remote tools.")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("action")) }
            put("additionalProperties", false)
        },
        policy = ToolPolicy(timeoutMillis = 360_000, ownsPhoneUi = true, readActions = setOf("list", "tools", "select")),
    )

    override suspend fun execute(toolUseId: String, arguments: JsonObject): ToolResult {
        fun string(name: String): String? = (arguments[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val action = string("action") ?: return ToolResult(toolUseId, "Missing string parameter: action", true)
        if (action !in setOf("list", "add", "remove", "refresh", "tools", "select", "authenticate", "logout")) return ToolResult(toolUseId, "Unknown MCP management action: $action", true)
        val context = currentCoroutineContext()[ToolExecutionContext]
        if (action !in setOf("list", "tools", "select") && context?.actionApproved != true && context?.yoloMode != true) {
            return ToolResult(toolUseId, "MCP configuration changes require approval through the agent approval flow.", true)
        }
        return try {
            manager.initialize()
            val name = string("name")
            val output = when (action) {
                "list" -> manager.servers().joinToString("\n") { "${it.name} (${it.transport}): ${it.tools.size} tools, ${it.activeTools.size} active${if (it.oauth) "; OAuth enabled, use authenticate to sign in" else ""}" }.ifEmpty { "No MCP servers configured." }
                "tools" -> {
                    val query = string("query").orEmpty()
                    val catalog = manager.toolCatalog().filter {
                        it.name.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true)
                    }.sortedBy { it.name }
                    val lines = StringBuilder()
                    var shown = 0
                    for (tool in catalog) {
                        val description = tool.description.replace('\n', ' ').replace('\r', ' ')
                        val shortDescription = if (description.length > 180) description.take(177) + "..." else description
                        val line = "${tool.name}: $shortDescription\n"
                        if (lines.length + line.length > 11_800) break
                        lines.append(line)
                        shown++
                    }
                    if (shown < catalog.size) lines.append("[Showing $shown of ${catalog.size} matching tools. Narrow query to see remaining tools.]\n")
                    lines.toString().ifEmpty { "No matching MCP tools. Add or refresh a server to discover its tools." }
                }
                "select" -> {
                    val names = arguments["names"] as? JsonArray ?: error("Select requires a names array")
                    val selected = manager.selectTools(names.map {
                        (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
                            ?: error("Every selected name must be a string")
                    })
                    if (selected.isEmpty()) "No remote tools active. Use tools and select to activate tools."
                    else "Active remote tools for the next model turn:\n" + selected.joinToString("\n") { it.name }
                }
                "add" -> {
                    require(!name.isNullOrBlank()) { "Add requires a server name" }
                    val server = if (string("transport") == "stdio") {
                        require(string("url") == null && string("client_id") == null && string("scope") == null) { "Stdio does not use URL/OAuth fields" }
                        val command = (arguments["command"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: error("Command entries must be strings") } ?: error("Stdio requires command")
                        val env = (arguments["env"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: error("Environment values must be strings") }.orEmpty()
                        manager.addStdio(name, command, string("cwd"), env)
                    } else {
                        require(string("transport") in listOf(null, "http")) { "Unknown MCP transport" }
                        require(arguments["command"] == null && arguments["cwd"] == null && arguments["env"] == null) { "HTTP does not use command/cwd/env" }
                        val url = string("url")
                        require(!url.isNullOrBlank()) { "Add requires an endpoint URL" }
                        manager.add(name, url, clientId = string("client_id"), scope = string("scope"))
                    }
                    if (server.oauth && server.tools.isEmpty()) "Registered '${server.name}'. Sign-in required: call mcp_manage authenticate with this name to open browser authorization."
                    else "Added MCP server '${server.name}' with ${server.tools.size} tools; ${server.activeTools.size} active next turn. Use tools to discover names and select to change the active set."
                }
                "authenticate" -> {
                    require(!name.isNullOrBlank()) { "Authenticate requires a server name" }
                    val server = manager.authenticate(name)
                    "Signed in to '${server.name}'; ${server.tools.size} tools discovered, ${server.activeTools.size} active."
                }
                "logout" -> {
                    require(!name.isNullOrBlank()) { "Logout requires a server name" }
                    manager.logout(name)
                    "Local OAuth credentials removed for '$name'."
                }
                "remove" -> {
                    require(!name.isNullOrBlank()) { "Remove requires a server name" }
                    if (manager.remove(name)) "Removed MCP server '$name'." else "MCP server '$name' was not configured."
                }
                else -> {
                    val servers = manager.refresh(name)
                    "Refreshed ${servers.size} MCP servers with ${servers.sumOf { it.tools.size }} tools; ${servers.sumOf { it.activeTools.size }} active next turn. Use tools and select to change the active set."
                }
            }
            ToolResult(toolUseId, output)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(toolUseId, "MCP setup failed: ${e.message ?: e.javaClass.simpleName}", true)
        }
    }
}
