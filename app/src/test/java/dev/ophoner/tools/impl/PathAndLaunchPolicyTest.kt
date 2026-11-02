package dev.ophoner.tools.impl

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class PathAndLaunchPolicyTest {
    @Test
    fun ordinaryRelativePathsPass() {
        assertNull(validatePath("readme.md"))
        assertNull(validatePath("notes/todo.md"))
        assertNull(validatePath("./notes/todo.md"))
        assertNull(validatePath("/data/data/dev.ophoner/files/note.txt"))
    }

    @Test
    fun traversalAndSensitivePathsFail() {
        val blocked = listOf(
            "../etc/passwd",
            "foo/../../etc",
            "foo\\..\\etc",
            "/proc/self",
            "/system/bin",
            "/dev/null",
            "/data/data/com.other/files",
            "/data/data/dev.ophoner/../com.other/files",
            "",
            "a\u0000b",
        )
        for (path in blocked) {
            assertNotNull(path, validatePath(path))
        }
    }

    @Test
    fun randomPathsDoNotThrowAndKeepDotDotBlocked() {
        val random = Random(20261102)
        val alphabet = "abc./\\_\u0000"
        repeat(1500) {
            val path = buildString {
                repeat(random.nextInt(40)) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            val reason = validatePath(path)
            if (path.split('/', '\\').any { it == ".." } || path.contains('\u0000') || path.isBlank()) {
                assertNotNull(path, reason)
            }
        }
    }

    @Test
    fun launchSchemesAreAllowlisted() {
        val allowed = listOf(
            "https://example.com/a",
            "HTTP://example.com",
            "mailto:a@b.c",
            "tel:+15551212",
            "sms:+15551212",
            "geo:0,0?q=cafe",
            "market://details?id=dev.ophoner",
        )
        for (url in allowed) assertNull(url, rejectedLaunchUrl(url))

        val blocked = listOf(
            "intent://scan/#Intent;scheme=https;end",
            "file:///etc/passwd",
            "content://settings/system",
            "javascript:alert(1)",
            "data:text/html,hi",
            "https://example.com\njavascript:alert(1)",
            "no-scheme",
            "",
        )
        for (url in blocked) assertNotNull(url, rejectedLaunchUrl(url))
    }

    @Test
    fun randomLaunchUrlsDoNotThrow() {
        val random = Random(20261102)
        val alphabet = "abcdefghijklmnopqrstuvwxyz:/?#.\n\r\u0000"
        repeat(800) {
            val url = buildString {
                repeat(random.nextInt(40)) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            val reason = rejectedLaunchUrl(url)
            val scheme = url.trim().substringBefore(':').lowercase()
            if (url.any { it == '\n' || it == '\r' || it == '\u0000' } || scheme !in ALLOWED_LAUNCH_SCHEMES) {
                assertNotNull(url, reason)
            }
        }
    }
}
