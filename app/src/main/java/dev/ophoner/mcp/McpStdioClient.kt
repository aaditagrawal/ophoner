package dev.ophoner.mcp

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One serialized session per server, retired after 60 seconds idle. Never invokes a shell implicitly. */
internal class McpStdioClient(
    private val command: List<String>,
    private val directory: String?,
    private val environment: Map<String, String>,
    private val json: Json,
    private val idleMillis: Long = 60_000,
) : McpClient {
    private val mutex = Mutex()
    private val lifecycle = Any()
    private var connection: Connection? = null
    private var retirement: java.util.concurrent.ScheduledFuture<*>? = null
    private var closed = false

    override suspend fun listTools() = useConnection { it.listTools() }
    override suspend fun callTool(name: String, arguments: JsonObject) = useConnection { it.callTool(name, arguments) }

    private suspend fun <T> useConnection(block: suspend (Connection) -> T): T = mutex.withLock {
        val current = synchronized(lifecycle) {
            check(!closed) { "MCP stdio client is closed." }
            retirement?.cancel(false)
            connection ?: Connection().also { connection = it }
        }
        try {
            withTimeout(30_000) { block(current) }
        } catch (e: Throwable) {
            synchronized(lifecycle) { if (connection === current) { current.close(); connection = null } }
            throw e
        } finally {
            synchronized(lifecycle) {
                if (!closed && connection === current) retirement = timer.schedule({
                    synchronized(lifecycle) { if (connection === current) { current.close(); connection = null } }
                }, idleMillis, TimeUnit.MILLISECONDS)
            }
        }
    }

    override fun close() = synchronized(lifecycle) {
        closed = true
        retirement?.cancel(false)
        connection?.close()
        connection = null
    }

    private inner class Connection : McpProtocolClient() {
        private val process = ProcessBuilder(command).apply {
            validateStdio(command, directory)
            directory?.let { directory(File(it)) }
            // Deliberately avoid inheriting app credentials or unrelated server environment.
            val inherited = environment().filterKeys { it in setOf("PATH", "HOME", "TMPDIR", "LANG", "ANDROID_ROOT", "ANDROID_DATA", "LD_LIBRARY_PATH") }
            environment().clear()
            environment().putAll(inherited)
            environment().putAll(environment)
            redirectErrorStream(false)
        }.start()
        private val source = process.inputStream.source().buffer()
        private val ids = AtomicLong()
        init {
            // Drain stderr independently so verbose servers cannot block stdout. Never expose credentials in logs.
            workers.execute { try { process.errorStream.use { stream -> val bytes = ByteArray(4096); while (stream.read(bytes) >= 0) {} } } catch (_: IOException) {} }
        }
        override suspend fun rpc(method: String, params: JsonObject?, notification: Boolean): JsonObject = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { close() }
            workers.execute {
                try {
                    val id = if (notification) null else ids.incrementAndGet()
                    write(buildJsonObject {
                        put("jsonrpc", "2.0"); id?.let { put("id", it) }; put("method", method)
                        params?.let { put("params", it) }
                    })
                    if (notification) { continuation.resume(JsonObject(emptyMap())); return@execute }
                    var consumed = 0
                    repeat(128) {
                        val line = source.readUtf8LineStrict((MAX_BYTES - consumed).toLong())
                        consumed += line.toByteArray(Charsets.UTF_8).size + 1
                        require(consumed <= MAX_BYTES) { "MCP stdio response exceeds size limit." }
                        val message = json.parseToJsonElement(line) as? JsonObject ?: throw IOException("Invalid MCP stdio message.")
                        require(message.string("jsonrpc") == "2.0") { "Invalid MCP JSON-RPC version." }
                        if (message["method"] != null) {
                            message["id"]?.let { requestId -> write(buildJsonObject {
                                put("jsonrpc", "2.0"); put("id", requestId)
                                if (message.string("method") == "ping") put("result", JsonObject(emptyMap()))
                                else putJsonObject("error") { put("code", -32601); put("message", "Client operation not supported") }
                            }) }
                        } else {
                            require(message["id"] == JsonPrimitive(id)) { "MCP stdio response ID did not match." }
                            if (message["error"] != null) throw IOException("MCP server returned a JSON-RPC error. The call was not retried.")
                            val result = message["result"] as? JsonObject ?: throw IOException("MCP response has no result.")
                            continuation.resume(result)
                            return@execute
                        }
                    }
                    throw IOException("Too many MCP messages without a matching response.")
                } catch (e: Exception) { close(); continuation.resumeWithException(IOException("MCP stdio connection failed. No call was retried; verify its outcome before repeating a mutation.", e)) }
            }
        }
        private fun write(message: JsonObject) {
            val bytes = (message.toString() + "\n").toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_BYTES) { "MCP request exceeds size limit." }
            process.outputStream.write(bytes)
            process.outputStream.flush()
        }
        override fun close() {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }
    companion object {
        private const val MAX_BYTES = 1_048_576
        private val workers = Executors.newCachedThreadPool { task -> Thread(task, "mcp-stdio").apply { isDaemon = true } }
        private val timer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "mcp-idle").apply { isDaemon = true } }
    }
}
