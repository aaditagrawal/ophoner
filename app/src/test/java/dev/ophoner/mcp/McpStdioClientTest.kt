package dev.ophoner.mcp

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class McpStdioClientTest {
    private val json = Json { encodeDefaults = true }
    private fun fixture(): java.io.File = Files.createTempFile("mcp-fixture", ".sh").toFile().apply {
        writeText("""
            while IFS= read -r line; do
              id=${'$'}{line#*\"id\":}; id=${'$'}{id%%,*}
              case "${'$'}line" in
                *notifications/initialized*) continue ;;
                *initialize*) result='{"protocolVersion":"2025-03-26","capabilities":{"tools":{}}}' ;;
                *tools/list*) result='{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}' ;;
                *tools/call*)
                  case "${'$'}line" in
                    *hang*) IFS= read -r unused ;;
                    *crash*) exit 1 ;;
                  esac
                  printf 'diagnostic only\n' >&2
                  result="{\"content\":[{\"type\":\"text\",\"text\":\"${'$'}${'$'}:${'$'}MCP_TEST\"}]}"
                  ;;
              esac
              printf '{"jsonrpc":"2.0","id":%s,"result":%s}\n' "${'$'}id" "${'$'}result"
            done
        """.trimIndent())
    }
    @Test fun `stdio negotiates and reuses process with isolated env and stderr`(): Unit = runBlocking {
        val file = fixture()
        val client = McpStdioClient(listOf("/bin/sh", file.path), file.parent, mapOf("MCP_TEST" to "custom"), json)
        try {
            assertEquals("echo", client.listTools().single().name)
            val first = client.callTool("echo", JsonObject(emptyMap()))
            assertEquals(first, client.callTool("echo", JsonObject(emptyMap())))
            assertTrue(first.toString().contains(":custom"))
        } finally { client.close(); file.delete() }
        assertTrue(runCatching { client.listTools() }.isFailure)
    }
    @Test fun `cancelled call kills process and later call starts a fresh session`(): Unit = runBlocking {
        val file = fixture()
        val client = McpStdioClient(listOf("/bin/sh", file.path), null, emptyMap(), json)
        try {
            val first = client.callTool("echo", JsonObject(emptyMap()))
            assertNull(withTimeoutOrNull(200) { client.callTool("hang", JsonObject(emptyMap())) })
            val second = withTimeout(2000) { client.callTool("echo", JsonObject(emptyMap())) }
            assertNotEquals(first, second)
            val oldPid = first["content"]!!.jsonArray.first().jsonObject.string("text")!!.substringBefore(':').toLong()
            assertFalse(ProcessHandle.of(oldPid).map { it.isAlive }.orElse(false))
        } finally { client.close(); file.delete() }
    }
    @Test fun `crashed process is not replayed and idle session is retired`(): Unit = runBlocking {
        val file = fixture()
        val client = McpStdioClient(listOf("/bin/sh", file.path), null, emptyMap(), json, idleMillis = 50)
        try {
            val first = client.callTool("echo", JsonObject(emptyMap()))
            delay(150)
            val second = client.callTool("echo", JsonObject(emptyMap()))
            assertNotEquals(first, second)
            assertTrue(runCatching { client.callTool("crash", JsonObject(emptyMap())) }.isFailure)
            assertTrue(client.listTools().isNotEmpty())
        } finally { client.close(); file.delete() }
    }
    @Test fun `manager persists stdio config separately from env and removal closes process`(): Unit = runBlocking {
        val file = fixture()
        var saved: String? = null
        val store = object : McpStore {
            override fun read() = saved
            override fun write(value: String) { saved = value }
        }
        val secrets = MemoryMcpSecrets()
        val manager = McpManager(store, okhttp3.OkHttpClient(), json, secrets = secrets)
        try {
            manager.initialize()
            manager.addStdio("local", listOf("/bin/sh", file.path), environment = mapOf("MCP_TEST" to "environment-value"))
            assertFalse(saved!!.contains("environment-value"))
            assertTrue(secrets.read("env.local")!!.contains("environment-value"))
            val result = withContext(dev.ophoner.tools.ToolExecutionContext(actionApproved = true)) {
                manager.executors().single().execute("call", JsonObject(emptyMap()))
            }
            val pid = result.output.substringBefore(':').toLong()
            assertTrue(ProcessHandle.of(pid).get().isAlive)
            manager.remove("local")
            withTimeout(2000) { while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(10) }
            assertNull(secrets.read("env.local"))
        } finally { manager.remove("local"); file.delete() }
    }

}
