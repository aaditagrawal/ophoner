package dev.ophoner.ui.chat

import org.junit.Assert.*
import org.junit.Test

class IncrementalMarkdownParserTest {
    @Test fun `every streamed prefix matches full parsing including open fences and math`() {
        val text = "# Heading\n\nParagraph **bold**.\n\n```kt\nval x = 1\n\nprintln(x)\n```\n\n" +
            "| A | B |\n| --- | --- |\n| 1 | 2 |\n\n\$\$\na + b\n\nc\n\$\$\n\n" +
            "\\[a\n\nb\\]\n\nLast paragraph\ncontinued.\n\n"
        val parser = IncrementalMarkdownParser()
        for (end in 0..text.length) {
            val prefix = text.take(end)
            assertEquals("prefix $end", parseMarkdownBlocks(prefix), parser.parse(prefix))
        }
        assertEquals(parseMarkdownBlocks("replacement"), parser.parse("replacement"))
        assertEquals(emptyList<MdBlock>(), parser.parse(""))
    }

    @Test fun `completed blocks retain identity and only the tail is reparsed`() {
        val parser = IncrementalMarkdownParser()
        val prefix = "Completed paragraph.\n\n".repeat(100)
        val original = parser.parse(prefix + "tail")
        val before = parser.parsedCharacters
        val updated = parser.parse(prefix + "tail grows")
        assertSame(original.first(), updated.first())
        assertEquals("tail grows".length.toLong(), parser.parsedCharacters - before)
        assertEquals(parseMarkdownBlocks(prefix + "tail grows"), updated)
    }
}
