package dev.ophoner.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.system.measureNanoTime

class MarkdownPerformanceTest {
    private val style = InlineStyle(FontFamily.Monospace, Color.Gray, Color.White, Color.Black, Color.White)

    @Test fun `cached patterns preserve formatting for every streaming prefix`() {
        val text = "Plain **bold** ***both*** *italic* `code` \$x + y\$ and \\(z\\). Escaped \\$5."
        for (end in 0..text.length) {
            val prefix = text.take(end)
            assertEquals(prefix, legacyParseInlineMarkdown(prefix, style), parseInlineMarkdown(prefix, style))
        }
    }

    @Test fun `report inline markdown benchmark when requested`() {
        if (System.getenv("OPHONER_PERF") != "1") return
        val text = "Plain **bold** ***both*** *italic* `code` \$x+y\$ ".repeat(80)
        repeat(10) { legacyParseInlineMarkdown(text, style); parseInlineMarkdown(text, style) }
        val before = List(15) { measureNanoTime { legacyParseInlineMarkdown(text, style) } }.sorted()[7]
        val after = List(15) { measureNanoTime { parseInlineMarkdown(text, style) } }.sorted()[7]
        println("MARKDOWN median: before=${before / 1e6} ms after=${after / 1e6} ms chars=${text.length}")
    }
}

private fun legacyParseInlineMarkdown(
    text: String,
    style: InlineStyle,
): AnnotatedString {
    return buildAnnotatedString {
        var remaining = text
        while (remaining.isNotEmpty()) {
            val boldItalic = Regex("\\*\\*\\*(.+?)\\*\\*\\*").find(remaining)
            val bold = Regex("\\*\\*(.+?)\\*\\*").find(remaining)
            val italic = Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)").find(remaining)
            val code = Regex("`([^`]+)`").find(remaining)
            val inlineMath = findInlineMath(remaining)

            val matches = listOfNotNull(
                boldItalic?.let { it.range.first to "bolditalic" },
                bold?.let { it.range.first to "bold" },
                italic?.let { it.range.first to "italic" },
                code?.let { it.range.first to "code" },
                inlineMath?.let { it.start to "math" },
            ).sortedBy { it.first }

            if (matches.isEmpty()) {
                append(remaining)
                break
            }

            val (pos, type) = matches.first()
            val matchRange: IntRange
            val content: String
            when (type) {
                "bolditalic" -> {
                    val match = boldItalic!!
                    matchRange = match.range
                    content = match.groupValues[1]
                }
                "bold" -> {
                    val match = bold!!
                    matchRange = match.range
                    content = match.groupValues[1]
                }
                "italic" -> {
                    val match = italic!!
                    matchRange = match.range
                    content = match.groupValues[1]
                }
                "code" -> {
                    val match = code!!
                    matchRange = match.range
                    content = match.groupValues[1]
                }
                "math" -> {
                    val match = inlineMath!!
                    matchRange = match.start..match.endInclusive
                    content = match.content
                }
                else -> break
            }

            if (pos > 0) append(remaining.substring(0, pos))

            when (type) {
                "bolditalic" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                    append(content)
                }
                "bold" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(content)
                }
                "italic" -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(content)
                }
                "code" -> withStyle(SpanStyle(
                    fontFamily = style.monoFamily,
                    background = style.codeBg,
                    color = style.codeText,
                    fontSize = 13.sp,
                )) {
                    append(content)
                }
                "math" -> withStyle(SpanStyle(
                    fontFamily = style.monoFamily,
                    background = style.mathBg,
                    color = style.mathText,
                    fontSize = 13.sp,
                )) {
                    append(content)
                }
            }

            remaining = remaining.substring(matchRange.last + 1)
        }
    }
}

private data class DisplayMathParseResult(
    val math: String,
    val nextIndex: Int,
)

private data class InlineMathMatch(
    val start: Int,
    val endInclusive: Int,
    val content: String,
)

private fun parseDisplayMath(lines: List<String>, startIndex: Int): DisplayMathParseResult? {
    val trimmed = lines[startIndex].trim()
    val closer = when {
        trimmed.startsWith("$$") -> "$$"
        trimmed.startsWith("\\[") -> "\\]"
        else -> return null
    }
    val firstLine = trimmed.drop(2)
    val sameLineEnd = firstLine.indexOf(closer)

    if (sameLineEnd >= 0) {
        return DisplayMathParseResult(
            math = firstLine.substring(0, sameLineEnd).trim(),
            nextIndex = startIndex + 1,
        )
    }

    val mathLines = mutableListOf(firstLine)
    var i = startIndex + 1
    while (i < lines.size) {
        val line = lines[i]
        val end = line.indexOf(closer)
        if (end >= 0) {
            mathLines.add(line.substring(0, end))
            return DisplayMathParseResult(
                math = mathLines.joinToString("\n").trim(),
                nextIndex = i + 1,
            )
        }
        mathLines.add(line)
        i++
    }

    return DisplayMathParseResult(
        math = mathLines.joinToString("\n").trim(),
        nextIndex = lines.size,
    )
}

private fun isDisplayMathStart(line: String): Boolean {
    val trimmed = line.trim()
    return trimmed.startsWith("$$") || trimmed.startsWith("\\[")
}

private fun findInlineMath(text: String): InlineMathMatch? {
    var i = 0
    while (i < text.length) {
        if (text[i] == '\\' && i + 1 < text.length && text[i + 1] == '(') {
            val end = findUnescaped(text, "\\)", i + 2)
            if (end >= 0) {
                val content = text.substring(i + 2, end).trim()
                if (content.isNotEmpty()) return InlineMathMatch(i, end + 1, content)
            }
            i += 2
            continue
        }

        if (text[i] == '$' && !isEscaped(text, i) && !isDoubleDollar(text, i)) {
            val end = findClosingDollar(text, i + 1)
            if (end >= 0) {
                val rawContent = text.substring(i + 1, end)
                val content = rawContent.trim()
                if (content.isNotEmpty() &&
                    rawContent.firstOrNull()?.isWhitespace() != true &&
                    rawContent.lastOrNull()?.isWhitespace() != true
                ) {
                    return InlineMathMatch(i, end, content)
                }
            }
        }
        i++
    }
    return null
}

private fun findClosingDollar(text: String, start: Int): Int {
    var i = start
    while (i < text.length) {
        if (text[i] == '$' && !isEscaped(text, i) && !isDoubleDollar(text, i)) return i
        i++
    }
    return -1
}

private fun findUnescaped(text: String, needle: String, start: Int): Int {
    var i = start
    while (i <= text.length - needle.length) {
        if (text.startsWith(needle, i) && !isEscaped(text, i)) return i
        i++
    }
    return -1
}

private fun isDoubleDollar(text: String, index: Int): Boolean {
    return (index > 0 && text[index - 1] == '$') || (index + 1 < text.length && text[index + 1] == '$')
}

private fun isEscaped(text: String, index: Int): Boolean {
    var slashCount = 0
    var i = index - 1
    while (i >= 0 && text[i] == '\\') {
        slashCount++
        i--
    }
    return slashCount % 2 == 1
}
