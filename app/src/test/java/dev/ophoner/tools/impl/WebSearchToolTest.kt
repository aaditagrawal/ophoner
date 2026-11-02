package dev.ophoner.tools.impl

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class WebSearchToolTest {
    @Test fun stopsReadingAfterTheRequestedResult() = runBlocking {
        val html = buildString {
            append(hit(1))
            append(hit(2))
            append("x".repeat(200_000))
        }.toByteArray()
        val read = AtomicInteger()
        val result = WebSearchTool(client(countingBody(html, read))).execute(
            "search",
            buildJsonObject {
                put("query", "example")
                put("max_results", 1)
            },
        )
        assertFalse(result.isError)
        assertTrue(result.output.contains("Title 1"))
        assertFalse(result.output.contains("Title 2"))
        assertTrue("read ${read.get()} of ${html.size}", read.get() < html.size / 2)
    }

    @Test fun malformedHtmlIsAnEmptyResult() = runBlocking {
        val result = WebSearchTool(client("<<<<not html".toResponseBody())).execute(
            "search",
            buildJsonObject { put("query", "example") },
        )
        assertFalse(result.isError)
        assertTrue(result.output.contains("No results"))
    }

    private fun hit(i: Int) =
        """<a class="result__a" href="/l/?uddg=https%3A%2F%2Fexample.com%2F$i">Title $i</a>""" +
            """<a class="result__snippet">Snippet $i</a>"""

    private fun client(body: okhttp3.ResponseBody) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()
    }.build()

    private fun countingBody(bytes: ByteArray, read: AtomicInteger): okhttp3.ResponseBody {
        val source = object : Source {
            var position = 0
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (position >= bytes.size) return -1
                val count = minOf(byteCount, (bytes.size - position).toLong(), 8_192L).toInt()
                sink.write(bytes, position, count)
                position += count
                read.addAndGet(count)
                return count.toLong()
            }
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }
        return source.buffer().asResponseBody("text/html".toMediaType(), bytes.size.toLong())
    }
}
