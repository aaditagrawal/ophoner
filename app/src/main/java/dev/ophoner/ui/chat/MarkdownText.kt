package dev.ophoner.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ophoner.ui.theme.AccentGreen
import dev.ophoner.ui.theme.GeistMono

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }

    Column(modifier = modifier) {
        for ((i, block) in blocks.withIndex()) {
            when (block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        else -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        text = parseInlineMarkdown(block.text),
                        style = style,
                        color = color,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                is MdBlock.CodeBlock -> {
                    Text(
                        text = block.code,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = GeistMono,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        ),
                        color = AccentGreen.copy(alpha = 0.85f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1A1A1A))
                            .padding(10.dp)
                            .horizontalScroll(rememberScrollState()),
                    )
                    Spacer(Modifier.height(4.dp))
                }
                is MdBlock.Table -> {
                    MdTable(block.headers, block.rows, color)
                    Spacer(Modifier.height(4.dp))
                }
                is MdBlock.Divider -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 6.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    )
                }
                is MdBlock.Paragraph -> {
                    Text(
                        text = parseInlineMarkdown(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = color,
                    )
                    if (i < blocks.lastIndex) Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun MdTable(
    headers: List<String>,
    rows: List<List<String>>,
    color: Color,
) {
    val borderColor = color.copy(alpha = 0.15f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF141414))
            .horizontalScroll(rememberScrollState()),
    ) {
        // Header row
        Row(
            modifier = Modifier
                .drawBehind {
                    drawLine(borderColor, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            for (header in headers) {
                Text(
                    text = parseInlineMarkdown(header),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = color,
                    modifier = Modifier.width(IntrinsicSize.Max).padding(horizontal = 8.dp),
                )
            }
        }

        // Data rows
        for ((rowIndex, row) in rows.withIndex()) {
            Row(
                modifier = Modifier
                    .then(
                        if (rowIndex % 2 == 1) Modifier.background(Color(0xFF1A1A1A))
                        else Modifier
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                for ((colIndex, cell) in row.withIndex()) {
                    Text(
                        text = parseInlineMarkdown(cell),
                        style = MaterialTheme.typography.labelSmall,
                        color = color.copy(alpha = 0.8f),
                        modifier = Modifier.width(IntrinsicSize.Max).padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class CodeBlock(val lang: String, val code: String) : MdBlock
    data object Divider : MdBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock
    data class Paragraph(val text: String) : MdBlock
}

private fun parseMarkdownBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = text.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // Code block
        if (line.trimStart().startsWith("```")) {
            val lang = line.trimStart().removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            if (i < lines.size) i++ // skip closing ```
            blocks.add(MdBlock.CodeBlock(lang, codeLines.joinToString("\n")))
            continue
        }

        // Divider
        if (line.trim().matches(Regex("^-{3,}$|^\\*{3,}$|^_{3,}$"))) {
            blocks.add(MdBlock.Divider)
            i++
            continue
        }

        // Heading
        val headingMatch = Regex("^(#{1,6})\\s+(.+)$").find(line.trim())
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            val content = headingMatch.groupValues[2]
            blocks.add(MdBlock.Heading(level, content))
            i++
            continue
        }

        // Table — detect lines starting with |
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
                // Check if second row is a separator (---|---)
                val hasSeparator = parsedRows.size >= 2 && parsedRows[1].all { it.matches(Regex("^[:\\-]+$")) }
                if (hasSeparator) {
                    val headers = parsedRows[0]
                    val dataRows = parsedRows.drop(2) // skip header + separator
                    blocks.add(MdBlock.Table(headers, dataRows))
                } else {
                    // No separator — treat all rows as data, first as header
                    blocks.add(MdBlock.Table(parsedRows[0], parsedRows.drop(1)))
                }
            } else {
                // Single pipe line — just a paragraph
                blocks.add(MdBlock.Paragraph(tableLines.joinToString("\n")))
            }
            continue
        }

        // Empty line
        if (line.isBlank()) {
            i++
            continue
        }

        // Paragraph — collect consecutive non-empty, non-special lines
        val paraLines = mutableListOf<String>()
        while (i < lines.size) {
            val l = lines[i]
            if (l.isBlank() ||
                l.trimStart().startsWith("```") ||
                l.trim().matches(Regex("^-{3,}$|^\\*{3,}$|^_{3,}$")) ||
                Regex("^#{1,6}\\s+").containsMatchIn(l.trim()) ||
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

private fun parseInlineMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        var remaining = text
        while (remaining.isNotEmpty()) {
            // Bold + italic ***text***
            val boldItalic = Regex("\\*\\*\\*(.+?)\\*\\*\\*").find(remaining)
            // Bold **text**
            val bold = Regex("\\*\\*(.+?)\\*\\*").find(remaining)
            // Italic *text*
            val italic = Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)").find(remaining)
            // Inline code `text`
            val code = Regex("`([^`]+)`").find(remaining)

            // Find earliest match
            val matches = listOfNotNull(
                boldItalic?.let { it.range.first to "bolditalic" },
                bold?.let { it.range.first to "bold" },
                italic?.let { it.range.first to "italic" },
                code?.let { it.range.first to "code" },
            ).sortedBy { it.first }

            if (matches.isEmpty()) {
                append(remaining)
                break
            }

            val (pos, type) = matches.first()
            val match = when (type) {
                "bolditalic" -> boldItalic!!
                "bold" -> bold!!
                "italic" -> italic!!
                "code" -> code!!
                else -> break
            }

            // Append text before match
            if (pos > 0) append(remaining.substring(0, pos))

            val content = match.groupValues[1]
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
                    fontFamily = GeistMono,
                    background = Color(0xFF1A1A1A),
                    color = AccentGreen.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                )) {
                    append(content)
                }
            }

            remaining = remaining.substring(match.range.last + 1)
        }
    }
}
