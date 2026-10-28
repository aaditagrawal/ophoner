package dev.ophoner.mcp

import android.content.Context
import dev.ophoner.data.repository.SecureStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.ophoner.tools.Tool
import dev.ophoner.tools.ToolExecutionContext
import dev.ophoner.tools.ToolExecutor
import dev.ophoner.tools.ToolPolicy
import dev.ophoner.tools.ToolResult
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** Persistent server catalog with in-memory, non-persistent MCP sessions. */
@Singleton
class McpManager(
    private val store: McpStore,
    private val http: OkHttpClient,
    private val json: Json,
    private val now: () -> Long = System::currentTimeMillis,
    private val secrets: McpSecrets = MemoryMcpSecrets(),
    private val oauth: McpOAuth? = null,
) {
    @Inject constructor(@ApplicationContext context: Context, http: OkHttpClient, json: Json, secureStorage: SecureStorage) :
        this(PreferencesMcpStore(context), http, json,
            secrets = EncryptedMcpSecrets(secureStorage),
            oauth = McpOAuth(http, json, EncryptedMcpSecrets(secureStorage), LoopbackMcpOAuthBrowser(context)))

    private val mutex = Mutex()
    @Volatile private var snapshot: List<McpServer> = emptyList()
    private var loaded = false
    private val clients = mutableMapOf<String, McpClient>()
    /** Cache-only startup avoids blocking an offline conversation on remote servers. */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loaded) {
                val raw = store.read()
                val restored = if (raw == null) emptyList() else {
                    require(raw.length <= MAX_CATALOG_CHARS) { "Stored MCP catalog exceeds size limit." }
                    json.decodeFromString<List<McpServer>>(raw)
                }
                require(restored.size <= MAX_SERVERS && restored.map { it.name }.distinct().size == restored.size) { "Invalid stored MCP server catalog." }
                restored.forEach { validateServer(it) }
                validateSelection(restored)
                snapshot = restored
                loaded = true
            }
        }
    }

    fun servers(): List<McpServer> = snapshot

    suspend fun add(name: String, url: String, oauthEnabled: Boolean = false, clientId: String? = null, scope: String? = null): McpServer =
        addServer(McpServer(name, validateMcpUrl(url), registrationId = java.util.UUID.randomUUID().toString(), oauth = oauthEnabled, clientId = clientId, scope = scope))

    suspend fun addStdio(name: String, command: List<String>, directory: String? = null, environment: Map<String, String> = emptyMap()): McpServer {
        validateStdio(command, directory)
        require(environment.size <= 64 && environment.all { (key, value) -> key.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")) && value.length <= 8192 && '\u0000' !in value } && environment.values.sumOf { it.length } <= 32_768) { "Invalid or oversized stdio environment." }
        return addServer(McpServer(name, registrationId = java.util.UUID.randomUUID().toString(), transport = "stdio", command = command, workingDirectory = directory), environment)
    }

    private suspend fun addServer(candidate: McpServer, environment: Map<String, String> = emptyMap()): McpServer = withContext(Dispatchers.IO) {
        validateServer(candidate)
        // Registration holds the catalog lock to prevent overlapping replacements and secret writes.
        mutex.withLock {
            check(loaded) { "Initialize MCP before adding a server." }
            require(snapshot.none { it.name == candidate.name }) { "MCP server already exists. Remove it before changing its configuration." }
            require(snapshot.size < MAX_SERVERS) { "At most $MAX_SERVERS MCP servers may be registered." }
            val client = createClient(candidate, environment)
            try {
                val discovered = try { candidate.copy(tools = client.listTools(), refreshedAt = now()) }
                    catch (e: McpAuthorizationRequired) { candidate.copy(oauth = true, authorizationMetadataUrl = oauthMetadataUrl(e.challenge)?.let { validateMcpUrl(it) }) }
                validateServer(discovered)
                val active = mutableListOf<String>()
                for (spec in discovered.tools.take(8)) {
                    if (selectionFits(snapshot + discovered.copy(activeTools = active + spec.name))) active.add(spec.name)
                }
                val selected = discovered.copy(activeTools = active)
                if (selected.transport == "stdio") secrets.write("env.${selected.name}", json.encodeToString(environment))
                save(snapshot + selected)
                clients[selected.name] = client
                selected
            } catch (e: Throwable) { client.close(); secrets.write("env.${candidate.name}", null); throw e }
        }
    }

    private fun createClient(server: McpServer, environment: Map<String, String>? = null): McpClient =
        if (server.transport == "stdio") McpStdioClient(server.command, server.workingDirectory,
            environment ?: secrets.read("env.${server.name}")?.let { json.decodeFromString<Map<String, String>>(it) }.orEmpty(), json)
        else McpHttpClient(server.url, http, Json(json) { isLenient = false },
            accessToken = { oauth?.token(server) }, unauthorized = { oauth?.invalidate(server) })

    suspend fun authenticate(name: String): McpServer {
        val server = mutex.withLock { snapshot.firstOrNull { it.name == name } ?: error("Unknown MCP server.") }
        val auth = oauth ?: error("OAuth is unavailable in this runtime.")
        auth.authenticate(server)
        mutex.withLock {
            if (snapshot.none { it.name == name && sameEndpoint(it, server) }) {
                auth.forget(server)
                error("MCP server was removed during sign-in.")
            }
            clients.remove(name)?.close()
        }
        val refreshed = refresh(name).single()
        // A new protected registration has no cached tools to select until after sign-in.
        return mutex.withLock {
            val current = snapshot.first { it.name == refreshed.name && sameEndpoint(it, refreshed) }
            var selected = current.copy(oauth = true)
            if (current.activeTools.isEmpty()) {
                for (tool in current.tools.take(8)) {
                    val next = selected.copy(activeTools = selected.activeTools + tool.name)
                    if (selectionFits(snapshot.map { if (it.name == name) next else it })) selected = next
                }
            }
            save(snapshot.map { if (it.name == name) selected else it })
            selected
        }
    }

    suspend fun logout(name: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val server = snapshot.firstOrNull { it.name == name } ?: error("Unknown MCP server.")
            oauth?.forget(server)
            clients.remove(name)?.close()
        }
    }

    suspend fun remove(name: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(loaded) { "Initialize MCP before removing a server." }
            if (snapshot.none { it.name == name }) return@withLock false
            val server = snapshot.first { it.name == name }
            save(snapshot.filterNot { it.name == name })
            clients.remove(name)?.close()
            oauth?.forget(server)
            secrets.write("env.$name", null)
            true
        }
    }

    suspend fun refresh(name: String? = null): List<McpServer> = withContext(Dispatchers.IO) {
        val targets = mutex.withLock {
            check(loaded) { "Initialize MCP before refreshing servers." }
            snapshot.filter { name == null || it.name == name }.also {
                require(name == null || it.isNotEmpty()) { "Unknown MCP server: $name" }
            }
        }
        targets.map { original ->
            val client = createClient(original)
            try {
                val tools = client.listTools()
                mutex.withLock {
                    val current = snapshot.firstOrNull { it.name == original.name && sameEndpoint(it, original) }
                        ?: error("MCP server changed during refresh.")
                    val updated = current.copy(tools = tools, refreshedAt = now(), activeTools = current.activeTools.filter { selected -> tools.any { it.name == selected } })
                    validateServer(updated)
                    val next = snapshot.map { if (it.name == original.name) updated else it }
                    validateSelection(next)
                    save(next)
                    clients.put(original.name, client)?.close()
                    updated
                }
            } catch (e: Throwable) { client.close(); throw e }
        }
    }

    fun toolCatalog(): List<Tool> = snapshot.flatMap { server -> server.tools.map { definition(server, it) } }

    /** Select by the provider-safe names in toolCatalog, keeping total model schema cost bounded. */
    suspend fun selectTools(names: List<String>): List<Tool> = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(loaded) { "Initialize MCP before selecting tools." }
            require(names.distinct().size == names.size && names.size <= MAX_ACTIVE_TOOLS) { "Select at most $MAX_ACTIVE_TOOLS distinct MCP tools." }
            val known = toolCatalog().map { it.name }.toSet()
            require(names.all { it in known }) { "Selection contains an unknown MCP tool name. Use mcp_manage tools first." }
            val selected = names.toSet()
            val next = snapshot.map { server -> server.copy(activeTools = server.tools.filter { mcpToolName(server.name, it.name) in selected }.map { it.name }) }
            validateSelection(next)
            save(next)
            toolCatalog().filter { it.name in selected }
        }
    }

    private fun definition(server: McpServer, spec: McpToolSpec): Tool = Tool(
        name = mcpToolName(server.name, spec.name),
        description = "MCP tool from ${server.name}: ${spec.description}",
        parameters = spec.inputSchema,
        policy = ToolPolicy(requiresApproval = true, ownsPhoneUi = server.transport == "stdio", timeoutMillis = 90_000),
    )

    /** Definitions are immutable snapshots; execution rechecks that registration still exists. */
    fun executors(): List<ToolExecutor> = snapshot.flatMap { server -> server.tools.filter { it.name in server.activeTools }.map { spec ->
        object : ToolExecutor {
            override val definition = definition(server, spec)
            override suspend fun execute(toolUseId: String, arguments: JsonObject): ToolResult {
                val context = currentCoroutineContext()[ToolExecutionContext]
                if (context?.actionApproved != true && context?.yoloMode != true) {
                    return ToolResult(toolUseId, "Remote MCP execution requires approval.", isError = true)
                }
                val client = mutex.withLock {
                    val current = snapshot.firstOrNull { it.name == server.name && sameEndpoint(it, server) }
                    require(current?.tools?.any { it == spec && it.name in current.activeTools } == true) { "MCP registration or tool schema changed; refresh the tool catalog before calling." }
                    clients.getOrPut(server.name) { createClient(server) }
                }
                return try {
                    val result = client.callTool(spec.name, arguments)
                    require(result["content"] is JsonArray || result["structuredContent"] != null) { "MCP tool returned no content." }
                    val text = buildString {
                        (result["content"] as? JsonArray)?.forEach { block ->
                            val obj = block as? JsonObject
                            if (obj?.string("type") == "text") append(obj.string("text").orEmpty())
                            else append("[Remote non-text content omitted: ${obj?.string("type") ?: "unknown"}]")
                            append('\n')
                        }
                        result["structuredContent"]?.let { append(it.toString()) }
                    }
                    ToolResult(toolUseId, if (text.length > MAX_OUTPUT_CHARS) text.take(MAX_OUTPUT_CHARS) + "\n[MCP output truncated]" else text,
                        isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull == true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ToolResult(toolUseId, "MCP call failed: ${e.message?.take(1024)}. It was not retried; verify remote state before repeating a mutation.", isError = true)
                }
            }
        }
    } }

    private fun save(servers: List<McpServer>) {
        val encoded = json.encodeToString(servers)
        require(encoded.length <= MAX_CATALOG_CHARS) { "MCP catalog exceeds storage limit." }
        store.write(encoded)
        snapshot = servers.toList()
    }

    private fun selectionFits(servers: List<McpServer>): Boolean {
        val active = servers.flatMap { server -> server.tools.filter { it.name in server.activeTools } }
        return active.size <= MAX_ACTIVE_TOOLS && active.sumOf { it.inputSchema.toString().length + it.description.length + 128 } <= MAX_ACTIVE_SCHEMA_CHARS
    }

    private fun validateSelection(servers: List<McpServer>) {
        require(selectionFits(servers)) { "Active MCP tools exceed the count or context budget. Select fewer tools." }
    }

    private fun validateServer(server: McpServer) {
        validateMcpName(server.name)
        require(server.transport in setOf("http", "stdio")) { "Unknown MCP transport." }
        if (server.transport == "stdio") {
            validateStdio(server.command, server.workingDirectory)
            require(server.url.isEmpty() && !server.oauth && server.clientId == null && server.scope == null) { "Stdio does not use HTTP/OAuth settings." }
        } else {
            validateMcpUrl(server.url)
            require(server.command.isEmpty() && server.workingDirectory == null) { "HTTP does not use a local command." }
        }
        server.authorizationMetadataUrl?.let { validateMcpUrl(it) }
        require(server.clientId == null || server.clientId.length in 1..2048) { "Invalid OAuth client ID." }
        require(server.scope == null || (server.scope.length <= 4096 && server.scope.all { it.code in 0x20..0x7e })) { "Invalid OAuth scope." }
        require(server.activeTools.distinct().size == server.activeTools.size && server.activeTools.all { name -> server.tools.any { it.name == name } }) { "Invalid active MCP tool selection." }
        require(server.tools.size <= McpHttpClient.MAX_TOOLS) { "Too many MCP tools." }
        require(server.tools.map { it.name }.distinct().size == server.tools.size) { "Duplicate MCP tool names." }
        require(server.tools.sumOf { it.inputSchema.toString().length } <= MAX_SERVER_SCHEMA_CHARS) { "MCP schemas exceed the per-server budget." }
        server.tools.forEach {
            require(it.name.isNotBlank() && it.name.length <= 256 && it.description.length <= 2048) { "Invalid MCP tool metadata." }
            require(it.inputSchema.string("type") == "object" && it.inputSchema.toString().length <= McpHttpClient.MAX_SCHEMA_CHARS) { "Invalid MCP input schema." }
        }
    }

    private fun sameEndpoint(a: McpServer, b: McpServer) = a.registrationId == b.registrationId && a.transport == b.transport && a.url == b.url && a.command == b.command && a.workingDirectory == b.workingDirectory

    companion object {
        private const val MAX_SERVERS = 8
        private const val MAX_CATALOG_CHARS = 2_000_000
        private const val MAX_SERVER_SCHEMA_CHARS = 65_536
        private const val MAX_OUTPUT_CHARS = 24_000
        private const val MAX_ACTIVE_TOOLS = 16
        private const val MAX_ACTIVE_SCHEMA_CHARS = 32_000
    }
}
