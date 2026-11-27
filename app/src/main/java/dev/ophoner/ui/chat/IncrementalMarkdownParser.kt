package dev.ophoner.ui.chat

/** Only blank lines outside fenced code/display math commit a prefix. */
internal class IncrementalMarkdownParser {
    private enum class Scan { Text, Fence, MathDollar, MathBracket }

    private var previous = ""
    private var committedEnd = 0
    private var scannedEnd = 0
    private var scan = Scan.Text
    private val committed = mutableListOf<MdBlock>()
    internal var parsedCharacters = 0L
        private set

    fun parse(text: String): List<MdBlock> {
        if (!text.startsWith(previous)) {
            committedEnd = 0
            committed.clear()
            scannedEnd = 0
            scan = Scan.Text
        }
        previous = text
        var cursor = scannedEnd.coerceAtMost(text.length)
        var boundary = committedEnd
        var inCode = scan == Scan.Fence
        var mathCloser: String? = when (scan) {
            Scan.MathDollar -> "$$"
            Scan.MathBracket -> "\\]"
            else -> null
        }
        while (cursor < text.length) {
            val end = text.indexOf('\n', cursor)
            if (end < 0) break // An incomplete line can still change its block type.
            val line = text.substring(cursor, end)
            val trimmed = line.trim()
            when {
                inCode -> if (line.trimStart().startsWith("```")) inCode = false
                mathCloser != null -> if (line.contains(mathCloser)) mathCloser = null
                line.trimStart().startsWith("```") -> inCode = true
                trimmed.startsWith("$$") -> if (!trimmed.drop(2).contains("$$")) mathCloser = "$$"
                trimmed.startsWith("\\[") -> if (!trimmed.drop(2).contains("\\]")) mathCloser = "\\]"
                line.isBlank() -> boundary = end + 1
            }
            cursor = end + 1
        }
        scannedEnd = cursor
        scan = when {
            inCode -> Scan.Fence
            mathCloser == "$$" -> Scan.MathDollar
            mathCloser == "\\]" -> Scan.MathBracket
            else -> Scan.Text
        }
        if (boundary > committedEnd) {
            val prefix = text.substring(committedEnd, boundary)
            parsedCharacters += prefix.length
            committed.addAll(parseMarkdownBlocks(prefix))
            committedEnd = boundary
        }
        val tail = text.substring(committedEnd)
        parsedCharacters += tail.length
        return committed + parseMarkdownBlocks(tail)
    }
}
