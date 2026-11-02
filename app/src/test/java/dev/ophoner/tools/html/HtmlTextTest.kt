package dev.ophoner.tools.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTextTest {
    @Test fun stripsTagsScriptsStylesAndEntities() {
        assertEquals("Example", htmlToText("<p>Example</p>"))
        assertEquals("Hi", htmlToText("<script>secret</script><style>hidden</style>Hi"))
        assertEquals("a&b <c>", htmlToText("a&amp;b&nbsp;&lt;c&gt;"))
        assertEquals("a b", htmlToText("a   \nb"))
        assertEquals("a<b", htmlToText("a<b"))
    }

    @Test fun unclosedScriptDoesNotScanQuadratically() {
        val started = System.nanoTime()
        val text = htmlToText("<script>" + "x".repeat(80_000))
        assertTrue(text.contains("x"))
        assertTrue(System.nanoTime() - started < 1_000_000_000L)
    }

    @Test fun searchStopsAtTheRequestedCountAndDecodesTargets() {
        val html = buildString {
            append(hit(1))
            append(hit(2))
            append("x".repeat(50_000))
        }
        val scanner = SearchResultScanner(1)
        scanner.append(html)
        assertTrue(scanner.satisfied)
        val hits = scanner.finish()
        assertEquals(1, hits.size)
        assertEquals("Title 1", hits[0].title)
        assertEquals("https://example.com/1", hits[0].url)
        assertEquals("Snippet 1", hits[0].snippet)
    }

    @Test fun malformedSearchHtmlYieldsNothing() {
        val scanner = SearchResultScanner(5)
        scanner.append("<<<<not html result__a without a tag")
        assertTrue(scanner.finish().isEmpty())
        assertFalse(scanner.satisfied)
    }

    @Test fun resultSplitAcrossChunksIsStillFound() {
        val html = hit(3)
        val scanner = SearchResultScanner(1)
        scanner.append(html.take(12))
        assertFalse(scanner.satisfied)
        scanner.append(html.drop(12))
        val hits = scanner.finish()
        assertEquals("Title 3", hits.single().title)
        assertEquals("https://example.com/3", hits.single().url)
    }

    private fun hit(i: Int) =
        """<a class="result__a" href="/l/?uddg=https%3A%2F%2Fexample.com%2F$i&rut=1">Title $i</a>""" +
            """<a class="result__snippet" href="https://example.com/$i">Snippet $i</a>"""
}
