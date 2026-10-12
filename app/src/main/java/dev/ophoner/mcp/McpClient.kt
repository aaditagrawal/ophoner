package dev.ophoner.mcp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.Closeable
import java.io.IOException

internal interface McpClient : Closeable {
    suspend fun listTools(): List<McpToolSpec>
    suspend fun callTool(name: String, arguments: JsonObject): JsonObject
    override fun close() {}
}

internal abstract class McpProtocolClient : McpClient {
    private val mutex = Mutex()
    @Volatile private var initialized = false

    override suspend fun listTools(): List<McpToolSpec> = mutex.withLock {
        ensureInitialized()
        val found = mutableListOf<McpToolSpec>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val result = rpc("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } })
            val entries = result["tools"] as? JsonArray ?: throw IOException("MCP tools/list omitted tools.")
            for (entry in entries) {
                val obj = entry as? JsonObject ?: throw IOException("Malformed MCP tool definition.")
                val name = obj.string("name") ?: throw IOException("MCP tool has no name.")
                val schema = obj["inputSchema"] as? JsonObject ?: throw IOException("MCP tool has no input schema.")
                require(name.isNotBlank() && name.length <= 256) { "MCP tool name is invalid." }
                require(schema.toString().length <= McpHttpClient.MAX_SCHEMA_CHARS && schema.string("type") == "object") { "MCP schema must be a bounded JSON object schema." }
                require(found.none { it.name == name }) { "Duplicate MCP tool name: $name" }
                require(found.size < McpHttpClient.MAX_TOOLS) { "MCP server exceeds ${McpHttpClient.MAX_TOOLS} tools." }
                found.add(McpToolSpec(name, obj.string("description").orEmpty().take(2048), schema))
            }
            cursor = result.string("nextCursor")
            if (cursor != null) require(cursor.length <= 2048 && cursors.add(cursor) && cursors.size <= McpHttpClient.MAX_TOOLS) { "MCP pagination did not terminate." }
        } while (cursor != null)
        found
    }

    override suspend fun callTool(name: String, arguments: JsonObject): JsonObject = mutex.withLock {
        ensureInitialized()
        // Never replay tools/call, including after an expired session or lost response.
        rpc("tools/call", buildJsonObject { put("name", name); put("arguments", arguments) })
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        val result = rpc("initialize", buildJsonObject {
            put("protocolVersion", McpHttpClient.PROTOCOL)
            put("capabilities", buildJsonObject {})
            putJsonObject("clientInfo") { put("name", "ophoner"); put("version", "1") }
        })
        require(result.string("protocolVersion") == McpHttpClient.PROTOCOL) { "MCP server did not negotiate supported protocol ${McpHttpClient.PROTOCOL}." }
        require((result["capabilities"] as? JsonObject)?.get("tools") is JsonObject) { "MCP server does not expose tools." }
        rpc("notifications/initialized", null, notification = true)
        initialized = true
    }


    protected fun resetSession() { initialized = false }
    protected abstract suspend fun rpc(method: String, params: JsonObject?, notification: Boolean = false): JsonObject
}
