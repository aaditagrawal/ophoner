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
}
