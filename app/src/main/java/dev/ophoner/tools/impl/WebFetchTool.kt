package dev.ophoner.tools.impl

import dev.ophoner.BuildConfig
import dev.ophoner.tools.html.htmlToText
import dev.ophoner.tools.Tool
import dev.ophoner.tools.ToolExecutor
import dev.ophoner.tools.ToolOutputLimits
import dev.ophoner.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

class WebFetchTool @Inject constructor(
    httpClient: OkHttpClient,
) : ToolExecutor {
    private val fetchClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(FetchGuardingDns)
        .build()

    override val definition = Tool(
        name = "web_fetch",
        description = "Fetch the content of a web page and return its text (HTML stripped). " +
            "Body text is capped at ${ToolOutputLimits.WEB_FETCH_MAX_CHARS} characters.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("url") {
                    put("type", "string")
                    put("description", "URL to fetch")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("url")) }
        },
    )

    override suspend fun execute(toolUseId: String, arguments: JsonObject): ToolResult {
        return try {
            val url = arguments["url"]?.jsonPrimitive?.content
                ?: return ToolResult(toolUseId, "Missing required parameter: url", isError = true)

            val html = withContext(Dispatchers.IO) { fetchText(url) }
            val text = htmlToText(html)
            ToolResult(
                toolUseId,
                ToolOutputLimits.truncateWithNotice(text, ToolOutputLimits.WEB_FETCH_MAX_CHARS, "web_fetch"),
            )
        } catch (e: RejectedUrlException) {
            ToolResult(toolUseId, "Error: URL rejected by security validator: ${e.message}", isError = true)
        } catch (e: Exception) {
            ToolResult(toolUseId, "Error fetching URL: ${e.message}", isError = true)
        }
    }

    private fun fetchText(initialUrl: String): String {
        var current = initialUrl
        for (attempt in 0 until MAX_REQUESTS) {
            WebUrlGuard.rejectionReason(current)?.let { throw RejectedUrlException(it) }
            val request = Request.Builder()
                .url(current)
                .header("User-Agent", "Ophoner/${BuildConfig.VERSION_NAME}")
                .build()
            fetchClient.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    if (attempt == MAX_REQUESTS - 1) {
                        throw java.io.IOException("too many redirects")
                    }
                    val location = response.header("Location")?.trim()
                    if (location.isNullOrEmpty()) throw java.io.IOException("redirect missing Location")
                    val next = response.request.url.resolve(location)
                        ?: throw java.io.IOException("redirect Location could not be resolved")
                    if (next.scheme != "http" && next.scheme != "https") {
                        throw RejectedUrlException("redirect scheme '${next.scheme}' is not allowed")
                    }
                    current = next.toString()
                    return@use
                }
                if (!response.isSuccessful) {
                    throw java.io.IOException("HTTP ${response.code}: ${response.message}")
                }
                val body = response.body
                return body.source().use { source ->
                    source.request(ToolOutputLimits.WEB_FETCH_MAX_RAW_BYTES + 1)
                    val buffer = source.buffer
                    val toRead = minOf(buffer.size, ToolOutputLimits.WEB_FETCH_MAX_RAW_BYTES)
                    buffer.readUtf8(toRead)
                }
            }
        }
        throw java.io.IOException("too many redirects")
    }

    private class RejectedUrlException(message: String) : Exception(message)

    private companion object {
        const val MAX_REQUESTS = 3
    }
}
