package dev.ophoner.mcp

import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket

class McpOAuthBrowserTest {
    @Test fun `loopback ignores wrong state accepts valid callback and closes listener`(): Unit = runBlocking {
        var port = 0
        val completed = CompletableDeferred<Unit>()
        val browser = LoopbackMcpOAuthBrowser { url ->
            val redirect = url.toHttpUrl().queryParameter("redirect_uri")!!
            port = redirect.toHttpUrl().port
            launch(Dispatchers.IO) {
                try {
                    val client = OkHttpClient()
                    client.newCall(Request.Builder().url("$redirect?state=wrong&code=bad").build()).execute().use { assertEquals(400, it.code) }
                    client.newCall(Request.Builder().url("$redirect?state=expected&code=private-code").build()).execute().use { assertEquals(200, it.code) }
                    completed.complete(Unit)
                } catch (e: Throwable) { completed.completeExceptionally(e) }
            }
        }
        val reply = withTimeout(5000) { browser.authorize("expected") { redirect ->
            "https://auth.example/authorize".toHttpUrl().newBuilder().addQueryParameter("redirect_uri", redirect).build().toString()
        } }
        completed.await()
        assertEquals("private-code", reply.code)
        assertTrue(runCatching { Socket("127.0.0.1", port).close() }.isFailure)
    }
    @Test fun `cancelling browser sign in closes pending listener`(): Unit = runBlocking {
        val opened = CompletableDeferred<Int>()
        val browser = LoopbackMcpOAuthBrowser { url -> opened.complete(url.toHttpUrl().queryParameter("redirect_uri")!!.toHttpUrl().port) }
        val pending = launch { browser.authorize("expected") { redirect -> "https://auth.example/?redirect_uri=$redirect" } }
        val port = opened.await()
        pending.cancelAndJoin()
        assertTrue(runCatching { Socket("127.0.0.1", port).close() }.isFailure)
    }
}
