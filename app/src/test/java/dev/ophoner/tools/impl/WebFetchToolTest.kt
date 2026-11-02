package dev.ophoner.tools.impl

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class WebFetchToolTest {
    private fun tool(status: Int): WebFetchTool {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("Test response")
                .body("<p>Example</p>".toResponseBody())
                .build()
        }.build()
        return WebFetchTool(client)
    }

    @Test
    fun httpFailuresAreToolErrors() = runBlocking {
        // A public numeric address passes the validator without a DNS/network request.
        val arguments = buildJsonObject { put("url", "https://8.8.8.8/example") }
        for (status in listOf(404, 500)) {
            val result = tool(status).execute("fetch", arguments)
            assertTrue(result.isError)
            assertTrue(result.output.contains("HTTP $status"))
        }
    }

    @Test
    fun successfulResponsesRemainSuccessful() = runBlocking {
        val result = tool(200).execute("fetch", buildJsonObject { put("url", "https://8.8.8.8/example") })
        assertFalse(result.isError)
        assertTrue(result.output.contains("Example"))
    }

    @Test
    fun scriptsAndBrokenMarkupDoNotLeakOrThrow() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("<script>secret</script><p>Visible</p><".toResponseBody())
                .build()
        }.build()
        val result = WebFetchTool(client).execute("fetch", buildJsonObject { put("url", "https://8.8.8.8/example") })
        assertFalse(result.isError)
        assertTrue(result.output.contains("Visible"))
        assertFalse(result.output.contains("secret"))
    }

    @Test
    fun redirectToLoopbackDoesNotReturnThePrivateBody() = runBlocking {
        val hits = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            hits.incrementAndGet()
            val path = chain.request().url.encodedPath
            if (path == "/secret") {
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("PRIVATE".toResponseBody())
                    .build()
            } else {
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("Found")
                    .header("Location", "http://127.0.0.1/secret")
                    .body("".toResponseBody())
                    .build()
            }
        }.build()
        val result = WebFetchTool(client).execute("fetch", buildJsonObject { put("url", "https://8.8.8.8/start") })
        assertTrue(result.isError)
        assertFalse(result.output.contains("PRIVATE"))
        assertTrue(hits.get() == 1)
    }

    @Test
    fun redirectToAnotherPublicUrlIsFollowed() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.encodedPath == "/next") {
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("<p>Followed</p>".toResponseBody())
                    .build()
            } else {
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("Found")
                    .header("Location", "/next")
                    .body("".toResponseBody())
                    .build()
            }
        }.build()
        val result = WebFetchTool(client).execute("fetch", buildJsonObject { put("url", "https://8.8.8.8/start") })
        assertFalse(result.isError)
        assertTrue(result.output.contains("Followed"))
    }

    @Test
    fun nonHttpRedirectIsRejected() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(302)
                .message("Found")
                .header("Location", "file:///etc/passwd")
                .body("SECRET".toResponseBody())
                .build()
        }.build()
        val result = WebFetchTool(client).execute("fetch", buildJsonObject { put("url", "https://8.8.8.8/start") })
        assertTrue(result.isError)
        assertFalse(result.output.contains("SECRET"))
    }
}
