package dev.ophoner.tools.impl

import dev.ophoner.BuildConfig
import dev.ophoner.tools.html.SearchResultScanner
import dev.ophoner.tools.Tool
import dev.ophoner.tools.ToolExecutor
import dev.ophoner.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URISyntaxException
import javax.inject.Inject

private const val MAX_HTML_BYTES = 2 * 1024 * 1024 // 2 MB cap to bound memory on hostile responses
private const val SEARCH_ENDPOINT = "https://html.duckduckgo.com/html/"

class WebSearchTool @Inject constructor(
    private val httpClient: OkHttpClient,
) : ToolExecutor {
    override val definition = Tool(
        name = "web_search",
        description = "Search the web using DuckDuckGo and return results",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Search query")
                }
                putJsonObject("max_results") {
                    put("type", "integer")
                    put("description", "Maximum number of results (default: 5)")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("query")) }
        },
    )

    override suspend fun execute(toolUseId: String, arguments: JsonObject): ToolResult {
        return try {
            val query = arguments["query"]?.jsonPrimitive?.content
                ?: return ToolResult(toolUseId, "Missing required parameter: query", isError = true)

            // Light SSRF check — the endpoint is hardcoded, but we re-validate
            // the scheme in case this constant is ever templated from config.
            validateSearchEndpoint(SEARCH_ENDPOINT)?.let { reason ->
                return ToolResult(
                    toolUseId,
                    "Error: search endpoint rejected by security validator: $reason",
                    isError = true,
                )
            }

            val maxResults = (arguments["max_results"]?.jsonPrimitive?.int ?: 5).coerceAtLeast(0)
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("$SEARCH_ENDPOINT?q=$encoded")
                .header("User-Agent", "Ophoner/${BuildConfig.VERSION_NAME}")
                .build()

            val scanner = SearchResultScanner(maxResults)
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    val stream = response.body.byteStream()
                    val buf = ByteArray(8 * 1024)
                    val decoder = Charsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
                    val chars = java.nio.CharBuffer.allocate(buf.size)
                    var total = 0
                    while (total < MAX_HTML_BYTES && !scanner.satisfied) {
                        val read = stream.read(buf, 0, minOf(buf.size, MAX_HTML_BYTES - total))
                        if (read <= 0) break
                        total += read
                        decodeChunk(decoder, buf, read, chars, endOfInput = false, scanner)
                    }
                    if (!scanner.satisfied) decodeChunk(decoder, buf, 0, chars, endOfInput = true, scanner)
                }
            }

            val results = scanner.finish()
            if (results.isEmpty()) {
                ToolResult(toolUseId, "No results found for: $query")
            } else {
                val output = results.mapIndexed { i, hit ->
                    "${i + 1}. ${hit.title}\n   ${hit.url}\n   ${hit.snippet}"
                }.joinToString("\n\n")
                ToolResult(toolUseId, output)
            }
        } catch (e: Exception) {
            ToolResult(toolUseId, "Error searching: ${e.message}", isError = true)
        }
    }

    private fun decodeChunk(
        decoder: java.nio.charset.CharsetDecoder,
        buf: ByteArray,
        length: Int,
        chars: java.nio.CharBuffer,
        endOfInput: Boolean,
        scanner: SearchResultScanner,
    ) {
        val bytes = java.nio.ByteBuffer.wrap(buf, 0, length)
        while (true) {
            chars.clear()
            val decoded = decoder.decode(bytes, chars, endOfInput)
            chars.flip()
            if (chars.hasRemaining()) scanner.append(chars.toString())
            if (decoded.isOverflow) continue
            break
        }
        if (!endOfInput) return
        while (true) {
            chars.clear()
            val flushed = decoder.flush(chars)
            chars.flip()
            if (chars.hasRemaining()) scanner.append(chars.toString())
            if (flushed.isOverflow) continue
            return
        }
    }

    private fun validateSearchEndpoint(url: String): String? {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return "malformed endpoint URL"
        }
        if (uri.scheme?.lowercase() != "https") {
            return "search endpoint must use https (got '${uri.scheme}')"
        }
        val host = uri.host?.lowercase() ?: return "endpoint has no host"
        if (host != "html.duckduckgo.com") {
            return "endpoint host '$host' is not the expected DuckDuckGo host"
        }
        return null
    }
}
