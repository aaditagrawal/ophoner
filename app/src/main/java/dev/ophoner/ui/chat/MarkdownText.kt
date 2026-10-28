package dev.ophoner.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ophoner.ui.theme.ChatPalette
import dev.ophoner.ui.theme.chatPalette
import dev.ophoner.ui.theme.monoFamily

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val parser = remember { IncrementalMarkdownParser() }
    val blocks = remember(text) { parser.parse(text) }
    val palette = chatPalette()
    val mono = monoFamily()
    val inlineStyle = palette.inlineStyle(mono)

    Column(modifier = modifier) {
        for ((i, block) in blocks.withIndex()) {
            when (block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    }
                    Text(
                        text = remember(block.text, inlineStyle) { parseInlineMarkdown(block.text, inlineStyle) },
                        style = style,
                        color = color,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                is MdBlock.CodeBlock -> {
                    Text(
                        text = block.code,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = mono,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        ),
                        color = palette.codeText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MdCorner)
                            .background(palette.codeBlockBg)
                            .border(1.dp, palette.toolCardBorder, MdCorner)
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .horizontalScroll(rememberScrollState()),
                    )
                    Spacer(Modifier.height(4.dp))
                }
                is MdBlock.MathBlock -> {
                    Text(
                        text = block.math,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = mono,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                        ),
                        color = palette.mathText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MdCorner)
                            .background(palette.mathBg)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .horizontalScroll(rememberScrollState()),
                    )
                    Spacer(Modifier.height(4.dp))
                }
                is MdBlock.Table -> {
                    MdTable(block.headers, block.rows, color)
                    Spacer(Modifier.height(8.dp))
                }
                is MdBlock.Divider -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        thickness = 0.5.dp,
                        color = palette.toolCardBorder,
                    )
                }
                is MdBlock.Paragraph -> {
                    Text(
                        text = remember(block.text, inlineStyle) { parseInlineMarkdown(block.text, inlineStyle) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = color,
                    )
                    if (i < blocks.lastIndex) Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

private val MdCorner = RoundedCornerShape(14.dp)

@Composable
private fun MdTable(
    headers: List<String>,
    rows: List<List<String>>,
    color: Color,
) {
    val palette = chatPalette()
    val mono = monoFamily()
    val inlineStyle = palette.inlineStyle(mono)
    val borderColor = palette.toolCardBorder
    val naturalWidths = remember(headers, rows) { tableColumnWidths(headers, rows) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MdCorner)
            .border(1.dp, borderColor, MdCorner),
    ) {
        // Row padding is 8 dp per side; stretch columns proportionally to fill the card.
        val available = maxWidth - 16.dp
        val natural = naturalWidths.fold(0.dp) { acc, w -> acc + w }
        val columnWidths = if (natural > 0.dp && natural < available) {
            naturalWidths.map { it * (available / natural) }
        } else {
            naturalWidths
        }
        Column(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier
                    .background(palette.tableHeaderBg)
                    .drawBehind {
                        drawLine(borderColor, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                    }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                for ((colIndex, header) in normalizedTableRow(headers, columnWidths.size).withIndex()) {
                    Text(
                        text = remember(header, inlineStyle) { parseInlineMarkdown(header, inlineStyle) },
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = color,
                        modifier = Modifier
                            .width(columnWidths[colIndex])
                            .padding(horizontal = 8.dp),
                    )
                }
            }

            for ((rowIndex, row) in rows.withIndex()) {
                Row(
                    modifier = Modifier
                        .then(
                            if (rowIndex % 2 == 1) Modifier.background(palette.tableRowAltBg)
                            else Modifier
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    for ((colIndex, cell) in normalizedTableRow(row, columnWidths.size).withIndex()) {
                        Text(
                            text = remember(cell, inlineStyle) { parseInlineMarkdown(cell, inlineStyle) },
                            style = MaterialTheme.typography.labelMedium,
                            color = color.copy(alpha = 0.85f),
                            modifier = Modifier
                                .width(columnWidths[colIndex])
                                .padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun tableColumnWidths(
    headers: List<String>,
    rows: List<List<String>>,
): List<Dp> {
    val columnCount = maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 0)
    return List(columnCount) { colIndex ->
        // Longest cell (capped) so short values like `Download` never wrap mid-word.
        val longest = buildList {
            add(headers.getOrNull(colIndex).orEmpty().tableCellLength())
            rows.forEach { row -> add(row.getOrNull(colIndex).orEmpty().tableCellLength()) }
        }.max()
        val targetChars = longest.coerceIn(MinTableColumnChars, MaxTableColumnChars)

        (targetChars * TableColumnCharWidthDp + TableCellHorizontalPaddingDp).dp
    }
}

private fun normalizedTableRow(row: List<String>, columnCount: Int): List<String> {
    return List(columnCount) { colIndex -> row.getOrNull(colIndex).orEmpty() }
}

private fun String.tableCellLength(): Int {
    return trim()
        .replace(WhitespacePattern, " ")
        .length
}

private const val MinTableColumnChars = 4
private const val MaxTableColumnChars = 28
private const val TableColumnCharWidthDp = 7
private const val TableCellHorizontalPaddingDp = 16

internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class CodeBlock(val lang: String, val code: String) : MdBlock
    data class MathBlock(val math: String) : MdBlock
    data object Divider : MdBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock
    data class Paragraph(val text: String) : MdBlock
}

internal fun parseMarkdownBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = text.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        if (line.trimStart().startsWith("```")) {
            val lang = line.trimStart().removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            if (i < lines.size) i++
            blocks.add(MdBlock.CodeBlock(lang, codeLines.joinToString("\n")))
            continue
        }

        val displayMath = parseDisplayMath(lines, i)
        if (displayMath != null) {
            blocks.add(MdBlock.MathBlock(displayMath.math))
            i = displayMath.nextIndex
            continue
        }

        if (line.trim().matches(DividerPattern)) {
            blocks.add(MdBlock.Divider)
            i++
            continue
        }

        val headingMatch = HeadingPattern.find(line.trim())
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            val content = headingMatch.groupValues[2]
            blocks.add(MdBlock.Heading(level, content))
            i++
            continue
        }

        if (line.trim().startsWith("|") && line.trim().endsWith("|")) {
            val tableLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().let { it.startsWith("|") && it.endsWith("|") }) {
                tableLines.add(lines[i].trim())
                i++
            }
            if (tableLines.size >= 2) {
                val parsedRows = tableLines.map { row ->
                    row.trim('|').split("|").map { it.trim() }
                }
                val hasSeparator = parsedRows.size >= 2 && parsedRows[1].all { it.matches(TableSeparatorPattern) }
                if (hasSeparator) {
                    val headers = parsedRows[0]
                    val dataRows = parsedRows.drop(2)
                    blocks.add(MdBlock.Table(headers, dataRows))
                } else {
                    blocks.add(MdBlock.Table(parsedRows[0], parsedRows.drop(1)))
                }
            } else {
                blocks.add(MdBlock.Paragraph(tableLines.joinToString("\n")))
            }
            continue
        }

        if (line.isBlank()) {
            i++
            continue
        }

        val paraLines = mutableListOf<String>()
        while (i < lines.size) {
            val l = lines[i]
            if (l.isBlank() ||
                l.trimStart().startsWith("```") ||
                isDisplayMathStart(l) ||
                l.trim().matches(DividerPattern) ||
                HeadingPrefixPattern.containsMatchIn(l.trim()) ||
                (l.trim().startsWith("|") && l.trim().endsWith("|"))
            ) break
            paraLines.add(l)
            i++
        }
        if (paraLines.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(paraLines.joinToString("\n")))
        }
    }

    return blocks
}

internal data class InlineStyle(
    val monoFamily: FontFamily,
    val codeBg: Color,
    val codeText: Color,
    val mathBg: Color,
    val mathText: Color,
)

private fun ChatPalette.inlineStyle(monoFamily: FontFamily): InlineStyle =
    InlineStyle(
        monoFamily = monoFamily,
        codeBg = codeBlockBg,
        codeText = codeText,
        mathBg = mathBg,
        mathText = mathText,
    )

internal fun parseInlineMarkdown(
    text: String,
    style: InlineStyle,
): AnnotatedString {
    return buildAnnotatedString {
        var remaining = text
        while (remaining.isNotEmpty()) {
            val boldItalic = BoldItalicPattern.find(remaining)
            val bold = BoldPattern.find(remaining)
            val italic = ItalicPattern.find(remaining)
            val code = CodePattern.find(remaining)
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

// Shared compiled patterns; parsing streamed text must not recompile them.
private val WhitespacePattern = Regex("\\s+")
private val DividerPattern = Regex("^-{3,}$|^\\*{3,}$|^_{3,}$")
private val HeadingPattern = Regex("^(#{1,6})\\s+(.+)$")
private val TableSeparatorPattern = Regex("^[:\\-]+$")
private val HeadingPrefixPattern = Regex("^#{1,6}\\s+")
private val BoldItalicPattern = Regex("\\*\\*\\*(.+?)\\*\\*\\*")
private val BoldPattern = Regex("\\*\\*(.+?)\\*\\*")
private val ItalicPattern = Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)")
private val CodePattern = Regex("`([^`]+)`")
