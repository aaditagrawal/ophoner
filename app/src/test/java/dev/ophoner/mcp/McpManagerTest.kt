package dev.ophoner.mcp

import dev.ophoner.tools.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class McpManagerTest {
    private class Store(var value: String? = null) : McpStore {
        var failWrites = false
        override fun read() = value
        override fun write(value: String) { if (failWrites) throw IOException("disk full"); this.value = value }
    }
    private val json = Json { encodeDefaults = true }
    private val schema = buildJsonObject { put("type", "object"); put("properties", JsonObject(emptyMap())) }
    private fun spec(name: String) = McpToolSpec(name, "Remote action", schema)

    private class Fixture(private val json: Json) : Interceptor {
        val methods = CopyOnWriteArrayList<String>()
        var useSse = false
        var failCall = false
        var expireCall = false
        var wrongId = false
        var wrongVersion = false
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            assertEquals("application/json, text/event-stream", request.header("Accept"))
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            val payload = json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val method = payload["method"]!!.jsonPrimitive.content
            methods.add(method)
            if (method != "initialize") assertEquals("test-session", request.header("Mcp-Session-Id"))
            if (method == "tools/call" && failCall) throw IOException("connection lost after request")
            val result = when (method) {
                "initialize" -> buildJsonObject {
                    put("protocolVersion", "2025-03-26")
                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                }
                "notifications/initialized" -> null
                "tools/list" -> buildJsonObject {
                    val second = (payload["params"] as? JsonObject)?.get("cursor") != null
                    putJsonArray("tools") { add(buildJsonObject {
                        put("name", if (second) "second" else "first")
                        put("description", "A remote tool")
                        putJsonObject("inputSchema") { put("type", "object"); putJsonObject("properties") {} }
                    }) }
                    if (!second) put("nextCursor", "page2")
                }
                "tools/call" -> buildJsonObject {
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "Remote success")
                        })
                    }
                }
                else -> error("Unexpected method $method")
            }
            val rpc = buildJsonObject {
                put("jsonrpc", if (wrongVersion && method == "tools/call") "1.0" else "2.0")
                put("id", if (wrongId && method == "tools/call") JsonPrimitive(9999) else payload["id"] ?: JsonNull)
                if (result != null) put("result", result)
            }.toString()
            val body = if (useSse && result != null) "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\ndata: $rpc\n\n" else rpc
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (expireCall && method == "tools/call") 404 else if (result == null) 202 else 200)
                .message("OK").header("Mcp-Session-Id", "test-session")
                .body(body.toResponseBody((if (useSse) "text/event-stream" else "application/json").toMediaType())).build()
        }
        fun client() = OkHttpClient.Builder().addInterceptor(this).build()
    }

    @Test fun registrationPaginatesPersistsAndCallsUsingSessionWithApproval() = runBlocking {
        val fixture = Fixture(json)
        val store = Store()
        val manager = McpManager(store, fixture.client(), json)
        manager.initialize()
        assertTrue(fixture.methods.isEmpty())
        val server = manager.add("demo", "https://example.com/mcp")
        assertEquals(listOf("first", "second"), server.tools.map { it.name })
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/list"), fixture.methods.toList())
        assertTrue(store.value!!.contains("https://example.com/mcp"))
        val executor = manager.executors().first()
        assertTrue(executor.definition.policy.requiresApproval)
        assertTrue(executor.execute("denied", JsonObject(emptyMap())).isError)
        assertFalse(fixture.methods.contains("tools/call"))
        val result = withContext(ToolExecutionContext(actionApproved = true)) { executor.execute("approved", JsonObject(emptyMap())) }
        assertFalse(result.isError)
        assertTrue(result.output.contains("Remote success"))
        assertEquals(1, fixture.methods.count { it == "initialize" })
    }

    @Test fun sseResponsesAndNotificationsAreSupported() = runBlocking {
        val fixture = Fixture(json).apply { useSse = true }
        val manager = McpManager(Store(), fixture.client(), json)
        manager.initialize()
        manager.add("demo", "https://example.com/mcp")
        val result = withContext(ToolExecutionContext(actionApproved = true)) {
            manager.executors().first().execute("id", JsonObject(emptyMap()))
        }
        assertFalse(result.isError)
    }

    @Test fun failedMutationIsNeverReplayed() = runBlocking {
        val fixture = Fixture(json)
        val manager = McpManager(Store(), fixture.client(), json)
        manager.initialize()
        manager.add("demo", "https://example.com/mcp")
        fixture.failCall = true
        val result = withContext(ToolExecutionContext(actionApproved = true)) {
            manager.executors().first().execute("id", JsonObject(emptyMap()))
        }
        assertTrue(result.isError)
        assertTrue(result.output.contains("not retried"))
        assertEquals(1, fixture.methods.count { it == "tools/call" })
    }

    @Test fun mismatchedIdsAndProtocolVersionsAreNotSuccess() = runBlocking {
        for (badId in listOf(true, false)) {
            val fixture = Fixture(json)
            val manager = McpManager(Store(), fixture.client(), json)
            manager.initialize()
            manager.add("demo", "https://example.com/mcp")
            fixture.wrongId = badId
            fixture.wrongVersion = !badId
            val result = withContext(ToolExecutionContext(actionApproved = true)) {
                manager.executors().first().execute("id", JsonObject(emptyMap()))
            }
            assertTrue(result.isError)
        }
    }

    @Test fun cachedStartupIsOfflineAndSelectionPersists() = runBlocking {
        val fixture = Fixture(json)
        val server = McpServer("demo", "https://example.com/mcp", (1..20).map { spec("tool$it") }, activeTools = listOf("tool1"))
        val store = Store(json.encodeToString(listOf(server)))
        val manager = McpManager(store, fixture.client(), json)
        manager.initialize()
        assertTrue(fixture.methods.isEmpty())
        assertEquals(20, manager.toolCatalog().size)
        assertEquals(1, manager.executors().size)
        val selected = manager.toolCatalog().last().name
        manager.selectTools(listOf(selected))
        assertEquals(selected, manager.executors().single().definition.name)
        val restored = McpManager(store, fixture.client(), json)
        restored.initialize()
        assertEquals(selected, restored.executors().single().definition.name)
        assertTrue(fixture.methods.isEmpty())
    }

    @Test fun removalAndSelectionInvalidateOldExecutors() = runBlocking {
        val store = Store(json.encodeToString(listOf(McpServer("demo", "https://example.com/mcp", listOf(spec("tool")), activeTools = listOf("tool")))))
        val fixture = Fixture(json)
        val manager = McpManager(store, fixture.client(), json)
        manager.initialize()
        val old = manager.executors().single()
        manager.selectTools(emptyList())
        try {
            withContext(ToolExecutionContext(actionApproved = true)) { old.execute("id", JsonObject(emptyMap())) }
            fail("Stale executor must not run")
        } catch (_: IllegalArgumentException) { }
        assertTrue(manager.remove("demo"))
        assertTrue(manager.servers().isEmpty())
        assertTrue(fixture.methods.isEmpty())
    }

    @Test fun selectionLimitsAndFailedPersistenceLeaveCatalogUnchanged() = runBlocking {
        val store = Store(json.encodeToString(listOf(McpServer("demo", "https://example.com/mcp", (1..20).map { spec("tool$it") }))))
        val manager = McpManager(store, OkHttpClient(), json)
        manager.initialize()
        try { manager.selectTools(manager.toolCatalog().take(17).map { it.name }); fail("Too many selected tools") }
        catch (_: IllegalArgumentException) { }
        store.failWrites = true
        try { manager.remove("demo"); fail("Expected storage failure") } catch (_: IOException) { }
        assertEquals(1, manager.servers().size)
    }

    @Test fun urlValidationAndNamesProtectBoundaries() {
        assertEquals("http://127.0.0.1:8080/mcp", validateMcpUrl("http://127.0.0.1:8080/mcp"))
        for (url in listOf("http://example.com/mcp", "https://user:secret@example.com/mcp", "https://example.com/mcp#fragment", "file:///tmp/server")) {
            assertThrows(IllegalArgumentException::class.java) { validateMcpUrl(url) }
        }
        assertNotEquals(mcpToolName("demo", "a/b"), mcpToolName("demo", "a_b"))
        val name = mcpToolName("a".repeat(24), "b".repeat(256))
        assertTrue(name.length <= 64)
        assertTrue(name.matches(Regex("[a-zA-Z0-9_-]+")))
    }
}
