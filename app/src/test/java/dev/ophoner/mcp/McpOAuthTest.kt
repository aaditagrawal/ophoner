package dev.ophoner.mcp

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class McpOAuthTest {
    private val json = Json { encodeDefaults = true }
    private val server = McpServer("secure", "https://resource.example/mcp", oauth = true)
    private class Fixture : Interceptor {
        val requests = mutableListOf<Request>()
        var tokenCalls = 0
        var wrongResource = false
        var insecureEndpoint = false
        var invalidGrant = false
        var omitGetChallenge = false
        var lastTokenForm = ""
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests.add(request)
            var status = 200
            val body = when (request.url.encodedPath) {
                "/mcp" -> { status = 401; "{}" }
                "/metadata" -> """{"resource":"https://resource.example/${if (wrongResource) "other" else "mcp"}","authorization_servers":["https://auth.example/tenant"],"scopes_supported":["tools:read"]}"""
                "/.well-known/oauth-authorization-server/tenant" -> """{"issuer":"https://auth.example/tenant","authorization_endpoint":"https://auth.example/authorize","token_endpoint":"${if (insecureEndpoint) "http" else "https"}://auth.example/token","registration_endpoint":"https://auth.example/register","code_challenge_methods_supported":["S256"]}"""
                "/register" -> """{"client_id":"public-client","token_endpoint_auth_method":"none"}"""
                "/token" -> {
                    tokenCalls++
                    lastTokenForm = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    if (invalidGrant) { status = 400; """{"error":"invalid_grant"}""" }
                    else """{"token_type":"Bearer","access_token":"access-$tokenCalls","refresh_token":"refresh-$tokenCalls","expires_in":120}"""
                }
                else -> error("Unexpected OAuth request ${request.url}")
            }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .header("WWW-Authenticate", if (omitGetChallenge && request.method == "GET" && request.url.encodedPath == "/mcp") "Bearer" else "Bearer resource_metadata=\"https://resource.example/metadata\"")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
    }
    @Test fun `PKCE resource binding registration and refresh rotation stay out of catalog`(): Unit = runBlocking {
        val fixture = Fixture()
        val secrets = MemoryMcpSecrets()
        var time = 1000L
        var authorization: HttpUrl? = null
        val auth = McpOAuth(OkHttpClient.Builder().addInterceptor(fixture).build(), json, secrets, McpOAuthBrowser { state, url ->
            authorization = url("http://127.0.0.1:12345/mcp/oauth/callback").toHttpUrl()
            McpOAuthReply("http://127.0.0.1:12345/mcp/oauth/callback", "code-only-in-memory", state)
        }, { time })
        auth.authenticate(server)
        assertEquals("public-client", authorization!!.queryParameter("client_id"))
        assertEquals(server.url, authorization!!.queryParameter("resource"))
        assertEquals("tools:read", authorization!!.queryParameter("scope"))
        assertEquals("S256", authorization!!.queryParameter("code_challenge_method"))
        val form = "https://example.com/?${fixture.lastTokenForm}".toHttpUrl()
        val verifier = form.queryParameter("code_verifier")!!
        assertEquals(authorization!!.queryParameter("code_challenge"), Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())))
        assertEquals(server.url, form.queryParameter("resource"))
        assertEquals("access-1", auth.token(server))
        time += 70_000
        assertEquals("access-2", auth.token(server))
        assertTrue(fixture.lastTokenForm.contains("refresh_token=refresh-1"))
        time += 70_000
        coroutineScope { (1..5).map { async { auth.token(server) } }.awaitAll() }
        assertEquals(3, fixture.tokenCalls)
        assertTrue(fixture.lastTokenForm.contains("refresh_token=refresh-2"))
        assertTrue(fixture.requests.all { it.header("Authorization") == null })
        auth.forget(server)
        assertNull(auth.token(server))
    }
    @Test fun `mismatched state is rejected without exchanging or saving credentials`(): Unit = runBlocking {
        val fixture = Fixture()
        val secrets = MemoryMcpSecrets()
        val auth = McpOAuth(OkHttpClient.Builder().addInterceptor(fixture).build(), json, secrets, McpOAuthBrowser { _, url ->
            url("http://127.0.0.1:12345/mcp/oauth/callback")
            McpOAuthReply("http://127.0.0.1:12345/mcp/oauth/callback", "code", "wrong")
        })
        assertTrue(runCatching { auth.authenticate(server) }.isFailure)
        assertEquals(0, fixture.tokenCalls)
        assertNull(secrets.read("oauth.secure"))
    }
    @Test fun `metadata resource mismatch and insecure token endpoint fail before opening browser`(): Unit = runBlocking {
        for (wrongResource in listOf(true, false)) {
            val fixture = Fixture().apply { this.wrongResource = wrongResource; insecureEndpoint = !wrongResource }
            val auth = McpOAuth(OkHttpClient.Builder().addInterceptor(fixture).build(), json, MemoryMcpSecrets(), McpOAuthBrowser { _, _ -> error("Browser must not open") })
            assertTrue(runCatching { auth.authenticate(server) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
    @Test fun `pre registered public client skips registration and invalid grant removes credentials`(): Unit = runBlocking {
        val fixture = Fixture()
        var time = 1000L
        val auth = McpOAuth(OkHttpClient.Builder().addInterceptor(fixture).build(), json, MemoryMcpSecrets(), McpOAuthBrowser { state, url ->
            url("http://127.0.0.1:12345/mcp/oauth/callback")
            McpOAuthReply("http://127.0.0.1:12345/mcp/oauth/callback", "code", state)
        }, { time })
        auth.authenticate(server.copy(clientId = "existing"))
        assertFalse(fixture.requests.any { it.url.encodedPath == "/register" })
        fixture.invalidGrant = true
        time += 70_000
        assertTrue(runCatching { auth.token(server) }.isFailure)
        assertNull(auth.token(server))
    }
    @Test fun `HTTP sends bearer on every message and never replays rejected mutation`(): Unit = runBlocking {
        val methods = mutableListOf<String>()
        var invalidated = 0
        val client = McpHttpClient(server.url, OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("Bearer private-token", request.header("Authorization"))
            val payload = json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            val method = payload.string("method")!!
            methods.add(method)
            val result = when (method) {
                "initialize" -> """{"protocolVersion":"2025-03-26","capabilities":{"tools":{}}}"""
                "notifications/initialized" -> "{}"
                else -> "{}"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture")
                .code(if (method == "tools/call") 401 else if (method == "notifications/initialized") 202 else 200)
                .body("""{"jsonrpc":"2.0","id":${payload["id"]},"result":$result}""".toResponseBody("application/json".toMediaType())).build()
        }.build(), json, { "private-token" }, { invalidated++ })
        assertTrue(runCatching { client.callTool("mutate", JsonObject(emptyMap())) }.exceptionOrNull() is McpAuthorizationRequired)
        assertEquals(1, methods.count { it == "tools/call" })
        assertEquals(1, invalidated)
    }
    @Test fun `protected registration survives sign in and logout removes bearer access`(): Unit = runBlocking {
        val fixture = Fixture().apply { omitGetChallenge = true }
        val secrets = MemoryMcpSecrets()
        var saved: String? = null
        val store = object : McpStore {
            override fun read() = saved
            override fun write(value: String) { saved = value }
        }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath != "/mcp" || request.method != "POST" || request.header("Authorization") != "Bearer access-1") return@addInterceptor fixture.intercept(chain)
            val payload = json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            val method = payload.string("method")
            val result = when (method) {
                "initialize" -> """{"protocolVersion":"2025-03-26","capabilities":{"tools":{}}}"""
                "tools/list" -> """{"tools":[{"name":"secure","inputSchema":{"type":"object"}}]}"""
                "tools/call" -> """{"content":[{"type":"text","text":"done"}]}"""
                else -> "{}"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture")
                .code(if (method == "notifications/initialized") 202 else 200)
                .body("""{"jsonrpc":"2.0","id":${payload["id"]},"result":$result}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val auth = McpOAuth(http, json, secrets, McpOAuthBrowser { state, url ->
            url("http://127.0.0.1:12345/mcp/oauth/callback")
            McpOAuthReply("http://127.0.0.1:12345/mcp/oauth/callback", "code", state)
        })
        val manager = McpManager(store, http, json, secrets = secrets, oauth = auth)
        manager.initialize()
        assertTrue(manager.add("secure", server.url).oauth)
        assertTrue(manager.executors().isEmpty())
        assertEquals(1, manager.authenticate("secure").activeTools.size)
        assertFalse(saved!!.contains("access-1"))
        assertFalse(saved!!.contains("refresh-1"))
        withContext(dev.ophoner.tools.ToolExecutionContext(actionApproved = true)) {
            assertFalse(manager.executors().single().execute("call", JsonObject(emptyMap())).isError)
            manager.logout("secure")
            assertTrue(manager.executors().single().execute("call2", JsonObject(emptyMap())).isError)
        }
        assertNull(secrets.read("oauth.secure"))
    }

}
