package dev.ophoner.tools.html

/**
 * Single-pass HTML text extraction and DuckDuckGo result scan.
 *
 * Compiled regular expressions are still rebuilt by the Kotlin [Regex] call site
 * unless they are stored, and patterns such as `<script>.*?</script>` backtrack
 * on malformed input. A linear scan is O(n), strips the same tags and entities
 * the tools already promised, and stops once the requested result count is known.
 */
internal fun htmlToText(html: String): String {
    val out = StringBuilder(html.length.coerceAtMost(64_000))
    // A failed search to the end of the string stays a miss for every later index.
    var noGtFrom = Int.MAX_VALUE
    var noScriptFrom = Int.MAX_VALUE
    var noStyleFrom = Int.MAX_VALUE
    fun indexOfGt(from: Int): Int {
        if (from >= noGtFrom) return -1
        val end = html.indexOf('>', from)
        if (end < 0) noGtFrom = from
        return end
    }
    fun emit(ch: Char) {
        val normalized = if (ch.isWhitespace()) ' ' else ch
        if (normalized == ' ' && (out.isEmpty() || out.last() == ' ')) return
        out.append(normalized)
    }
    var i = 0
    while (i < html.length) {
        val c = html[i]
        if (c == '<') {
            val skipped = skipElement(html, i, noScriptFrom, noStyleFrom)
            if (skipped.absentFrom < noScriptFrom && skipped.closer == "</script>") noScriptFrom = skipped.absentFrom
            if (skipped.absentFrom < noStyleFrom && skipped.closer == "</style>") noStyleFrom = skipped.absentFrom
            if (skipped.end > i) {
                emit(' ')
                i = skipped.end
                continue
            }
            val end = indexOfGt(i + 1)
            if (end < 0) {
                emit(c)
                i++
                continue
            }
            emit(' ')
            i = end + 1
            continue
        }
        if (c == '&') {
            val entity = namedEntity(html, i)
            if (entity != null) {
                emit(entity.first)
                i = entity.second
                continue
            }
        }
        emit(c)
        i++
    }
    while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
    return out.toString()
}

private class Skip(val end: Int, val closer: String, val absentFrom: Int)

private fun skipElement(html: String, start: Int, noScriptFrom: Int, noStyleFrom: Int): Skip {
    val closer = when {
        html.regionMatches(start, "<script", 0, 7, ignoreCase = true) -> "</script>"
        html.regionMatches(start, "<style", 0, 6, ignoreCase = true) -> "</style>"
        else -> return Skip(start, "", Int.MAX_VALUE)
    }
    val knownMiss = if (closer == "</script>") noScriptFrom else noStyleFrom
    val from = start + closer.length
    if (from >= knownMiss) return Skip(start, closer, knownMiss)
    val close = html.indexOf(closer, from, ignoreCase = true)
    return if (close >= 0) Skip(close + closer.length, closer, Int.MAX_VALUE) else Skip(start, closer, from)
}

private val ENTITIES = arrayOf("&nbsp;" to ' ', "&amp;" to '&', "&lt;" to '<', "&gt;" to '>')

private fun namedEntity(html: String, start: Int): Pair<Char, Int>? {
    for ((token, value) in ENTITIES) {
        if (html.regionMatches(start, token, 0, token.length, ignoreCase = false)) {
            return value to start + token.length
        }
    }
    return null
}

internal data class SearchHit(val title: String, val url: String, val snippet: String)

/**
 * Incremental DuckDuckGo HTML parser. [append] scans only newly arrived text and
 * reports [satisfied] once [limit] hits are complete, so the caller can stop reading.
 */
internal class SearchResultScanner(private val limit: Int) {
    private val html = StringBuilder()
    private val hits = ArrayList<SearchHit>(limit.coerceAtLeast(0).coerceAtMost(8))
    private var cursor = 0
    private var heldTitle = ""
    private var heldHref: String? = null
    private var heldAfter = 0

    val satisfied: Boolean get() = hits.size >= limit

    fun append(text: String) {
        if (text.isEmpty() || satisfied) return
        html.append(text)
        drain(eof = false)
    }

    fun finish(): List<SearchHit> {
        drain(eof = true)
        return hits.toList()
    }

    private fun drain(eof: Boolean) {
        while (hits.size < limit) {
            if (heldHref != null) {
                val snippet = readSnippet(heldAfter, eof)
                if (snippet == null) return
                hits.add(SearchHit(heldTitle, duckDuckGoTarget(heldHref!!), snippet.text))
                cursor = snippet.resumeAt
                heldHref = null
                continue
            }
            val marker = html.indexOf(RESULT_LINK, cursor)
            if (marker < 0) {
                val keepFrom = html.length - (RESULT_LINK.length - 1)
                if (keepFrom > cursor) cursor = keepFrom.coerceAtLeast(0)
                return
            }
            val tagStart = html.lastIndexOf('<', marker)
            val tagBoundary = if (tagStart >= 0) html.indexOf('>', tagStart) else -1
            if (tagStart < 0 || tagBoundary in 0 until marker || !html.regionMatches(tagStart, "<a", 0, 2, ignoreCase = true)) {
                cursor = marker + RESULT_LINK.length
                continue
            }
            val tagEnd = html.indexOf('>', marker)
            if (tagEnd < 0) {
                cursor = if (eof) html.length else tagStart
                return
            }
            val close = html.indexOf("</a>", tagEnd, ignoreCase = true)
            if (close < 0) {
                cursor = if (eof) html.length else tagStart
                return
            }
            val href = htmlAttribute(html.substring(tagStart, tagEnd + 1), "href")
            val title = htmlToText(html.substring(tagEnd + 1, close))
            val after = close + 4
            if (href == null || title.isEmpty()) {
                cursor = after
                continue
            }
            val snippet = readSnippet(after, eof)
            if (snippet == null) {
                // Keep the finished title and only scan new bytes for the snippet.
                heldTitle = title
                heldHref = href
                heldAfter = after
                cursor = after
                return
            }
            hits.add(SearchHit(title, duckDuckGoTarget(href), snippet.text))
            cursor = snippet.resumeAt
        }
    }

    private fun readSnippet(from: Int, eof: Boolean): Snippet? {
        val nextLink = html.indexOf(RESULT_LINK, from)
        val marker = html.indexOf(RESULT_SNIPPET, from)
        if (marker < 0 || (nextLink in 0 until marker)) {
            if (nextLink >= 0 || eof) return Snippet("", from)
            return null
        }
        val tagStart = html.lastIndexOf('<', marker)
        if (tagStart < from || !html.regionMatches(tagStart, "<a", 0, 2, ignoreCase = true)) {
            if (nextLink >= 0 || eof) return Snippet("", from)
            return null
        }
        val tagEnd = html.indexOf('>', marker)
        if (tagEnd < 0) return if (eof) Snippet("", html.length) else null
        val close = html.indexOf("</a>", tagEnd, ignoreCase = true)
        if (close < 0) {
            return if (eof) Snippet(htmlToText(html.substring(tagEnd + 1)), html.length) else null
        }
        return Snippet(htmlToText(html.substring(tagEnd + 1, close)), close + 4)
    }

    private data class Snippet(val text: String, val resumeAt: Int)

    private companion object {
        const val RESULT_LINK = "result__a"
        const val RESULT_SNIPPET = "result__snippet"
    }
}

internal fun htmlAttribute(tag: String, name: String): String? {
    val lower = tag.lowercase()
    val key = "$name="
    var at = lower.indexOf(key)
    while (at >= 0) {
        val boundaryOk = at == 0 || !lower[at - 1].isLetterOrDigit()
        if (boundaryOk) {
            val valueAt = at + key.length
            if (valueAt < tag.length && (tag[valueAt] == '"' || tag[valueAt] == '\'')) {
                val quote = tag[valueAt]
                val end = tag.indexOf(quote, valueAt + 1)
                if (end > valueAt) return tag.substring(valueAt + 1, end)
            }
        }
        at = lower.indexOf(key, at + key.length)
    }
    return null
}

internal fun duckDuckGoTarget(rawHref: String): String {
    val key = "uddg="
    val at = rawHref.indexOf(key)
    if (at < 0) return rawHref
    val start = at + key.length
    val end = rawHref.indexOf('&', start).let { if (it < 0) rawHref.length else it }
    return try {
        java.net.URLDecoder.decode(rawHref.substring(start, end), Charsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        rawHref
    }
}
