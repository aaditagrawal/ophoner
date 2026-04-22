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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import javax.inject.Inject

class WebFetchTool @Inject constructor(
    private val httpClient: OkHttpClient,
) : ToolExecutor {
    override val definition = Tool(
        name = "web_fetch",
        description = "Fetch the content of a web page and return its text (HTML stripped)",
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

            validateUrlForSsrf(url)?.let { reason ->
                return ToolResult(
                    toolUseId,
                    "Error: URL rejected by security validator: $reason",
                    isError = true,
                )
            }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Ophoner/${BuildConfig.VERSION_NAME}")
                .build()

            val html = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext "HTTP ${response.code}: ${response.message}"
                    }
                    response.body?.string() ?: ""
                }
            }

            val text = stripHtml(html)
            val truncated = if (text.length > 8000) text.take(8000) + "\n\n[truncated]" else text
            ToolResult(toolUseId, truncated)
        } catch (e: Exception) {
            ToolResult(toolUseId, "Error fetching URL: ${e.message}", isError = true)
        }
    }

    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * SSRF guard. Returns null when the URL is safe to fetch, or a
     * human-readable rejection reason.
     *
     * Uses DNS resolution on the IO dispatcher to catch hosts that alias to
     * private addresses. We check each resolved address against RFC1918,
     * loopback, link-local, CGNAT, and ULA ranges.
     */
    private suspend fun validateUrlForSsrf(rawUrl: String): String? {
        val uri = try {
            URI(rawUrl)
        } catch (_: URISyntaxException) {
            return "malformed URL"
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return "scheme '${uri.scheme ?: "(none)"}' is not allowed (only http/https)"
        }

        val host = uri.host?.lowercase()
            ?: return "URL has no host component"

        // Explicit string-level blocks before DNS resolution.
        if (host == "localhost") return "host 'localhost' is blocked"
        if (host.endsWith(".local")) return "host ending in .local is blocked"
        if (host.endsWith(".internal")) return "host ending in .internal is blocked"

        // Resolve host and check every returned address.
        val addresses = try {
            withContext(Dispatchers.IO) { InetAddress.getAllByName(host) }
        } catch (e: Exception) {
            return "host could not be resolved: ${e.message}"
        }

        for (address in addresses) {
            classifyAddress(address)?.let { return "host resolves to $it (${address.hostAddress})" }
        }

        return null
    }

    /**
     * Returns a rejection reason if this address sits in a disallowed range,
     * or null if it's a normal public address.
     */
    private fun classifyAddress(address: InetAddress): String? {
        if (address.isLoopbackAddress) return "loopback address"
        if (address.isAnyLocalAddress) return "any-local (0.0.0.0/::) address"
        if (address.isLinkLocalAddress) return "link-local address"
        if (address.isSiteLocalAddress) return "site-local (RFC1918) address"
        if (address.isMulticastAddress) return "multicast address"

        // isSiteLocalAddress already covers 10/8, 172.16/12, 192.168/16.
        // Explicitly catch CGNAT (100.64.0.0/10) which isn't flagged by the
        // JDK helpers but is still a private-ish range we don't want to hit.
        if (address is Inet4Address) {
            val bytes = address.address
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 100 && b1 in 64..127) return "CGNAT (100.64.0.0/10)"
            // Defense in depth in case isSiteLocalAddress misses something.
            if (b0 == 10) return "private range 10.0.0.0/8"
            if (b0 == 172 && b1 in 16..31) return "private range 172.16.0.0/12"
            if (b0 == 192 && b1 == 168) return "private range 192.168.0.0/16"
            if (b0 == 169 && b1 == 254) return "link-local 169.254.0.0/16"
            if (b0 == 127) return "loopback 127.0.0.0/8"
        }

        if (address is Inet6Address) {
            val bytes = address.address
            val b0 = bytes[0].toInt() and 0xFF
            // fc00::/7 (unique local)
            if (b0 and 0xFE == 0xFC) return "ULA fc00::/7"
            // fe80::/10 (link-local) — also covered by isLinkLocalAddress, keep explicit.
            val firstTwo = ((b0 shl 8) or (bytes[1].toInt() and 0xFF)) and 0xFFC0
            if (firstTwo == 0xFE80) return "IPv6 link-local fe80::/10"
        }

        return null
    }
}
