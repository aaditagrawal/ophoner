package dev.ophoner.tools.impl

import dev.ophoner.BuildConfig
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

            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("$SEARCH_ENDPOINT?q=$encoded")
                .header("User-Agent", "Ophoner/${BuildConfig.VERSION_NAME}")
                .build()

            val html = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body ?: return@withContext ""
                    // Cap response size to protect the fragile regex parser below
                    // from pathologically large HTML payloads. We read into a
                    // ByteArray incrementally and stop at the cap.
                    val stream = body.byteStream()
                    val buf = ByteArray(8 * 1024)
                    val out = java.io.ByteArrayOutputStream()
                    var total = 0
                    while (total < MAX_HTML_BYTES) {
                        val remaining = MAX_HTML_BYTES - total
                        val read = stream.read(buf, 0, minOf(buf.size, remaining))
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        total += read
                    }
                    String(out.toByteArray(), Charsets.UTF_8)
                }
            }

            val results = parseSearchResults(html)
            if (results.isEmpty()) {
                ToolResult(toolUseId, "No results found for: $query")
            } else {
                val maxResults = arguments["max_results"]?.jsonPrimitive?.int ?: 5
                val output = results.take(maxResults).mapIndexed { i, (title, url, snippet) ->
                    "${i + 1}. $title\n   $url\n   $snippet"
                }.joinToString("\n\n")
                ToolResult(toolUseId, output)
            }
        } catch (e: Exception) {
            ToolResult(toolUseId, "Error searching: ${e.message}", isError = true)
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

    private fun parseSearchResults(html: String): List<Triple<String, String, String>> {
        val results = mutableListOf<Triple<String, String, String>>()
        val linkRegex = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snippetRegex = Regex("""<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

        val links = linkRegex.findAll(html).toList()
        val snippets = snippetRegex.findAll(html).toList()

        for (i in links.indices) {
            val url = links[i].groupValues[1].let { rawUrl ->
                Regex("""uddg=([^&]*)""").find(rawUrl)?.groupValues?.get(1)?.let {
                    java.net.URLDecoder.decode(it, "UTF-8")
                } ?: rawUrl
            }
            val title = links[i].groupValues[2].replace(Regex("<[^>]*>"), "").trim()
            val snippet = snippets.getOrNull(i)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]*>"), "")?.trim() ?: ""
            if (title.isNotEmpty()) {
                results.add(Triple(title, url, snippet))
            }
        }
        return results
    }
}
