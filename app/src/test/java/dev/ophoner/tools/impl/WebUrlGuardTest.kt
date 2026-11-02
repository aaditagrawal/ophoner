package dev.ophoner.tools.impl

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.random.Random

class WebUrlGuardTest {
    @Test
    fun blocksPrivateLiteralRanges() {
        val blocked = listOf(
            "http://127.0.0.1/",
            "http://127.1/",
            "http://0.0.0.1/",
            "http://10.1.2.3/",
            "http://100.64.0.1/",
            "http://169.254.169.254/",
            "http://192.168.0.1/",
            "http://[::1]/",
            "http://[::ffff:127.0.0.1]/",
            "http://[::ffff:10.1.2.3]/",
            "http://[fc00::1]/",
            "http://[fe80::1]/",
            "http://localhost/",
            "http://printer.local/",
            "http://metadata.google.internal/",
            "http://app.localhost/",
            "file:///etc/passwd",
            "http://user:pass@8.8.8.8/",
        )
        for (url in blocked) {
            assertNotNull(url, WebUrlGuard.rejectionReason(url) { error("unexpected DNS for $url") })
        }
        assertNull(WebUrlGuard.rejectionReason("https://8.8.8.8/example") { error("unexpected DNS") })
    }

    @Test
    fun classifiesAddressesTheJdkDoesNotFlag() {
        assertNotNull(WebUrlGuard.addressBlockReason(InetAddress.getByName("0.0.0.1")))
        assertNotNull(WebUrlGuard.addressBlockReason(InetAddress.getByName("100.64.0.1")))
        assertNotNull(WebUrlGuard.addressBlockReason(InetAddress.getByName("240.0.0.1")))
        assertNotNull(WebUrlGuard.addressBlockReason(InetAddress.getByName("::ffff:10.1.2.3")))
        assertNotNull(WebUrlGuard.addressBlockReason(InetAddress.getByName("::ffff:127.0.0.1")))
        assertNull(WebUrlGuard.addressBlockReason(InetAddress.getByName("8.8.8.8")))
    }

    @Test
    fun hostnameResolverResultIsChecked() {
        val loopback = WebUrlGuard.rejectionReason("https://example.test/a") {
            listOf(InetAddress.getByName("127.0.0.1"))
        }
        assertNotNull(loopback)
        val public = WebUrlGuard.rejectionReason("https://example.test/a") {
            listOf(InetAddress.getByName("8.8.8.8"))
        }
        assertNull(public)
        val unresolved = WebUrlGuard.rejectionReason("https://example.test/a") {
            throw UnknownHostException("missing")
        }
        assertNotNull(unresolved)
    }

    @Test
    fun connectTimeLookupRejectsLoopback() {
        assertThrows(UnknownHostException::class.java) {
            FetchGuardingDns.lookup("localhost")
        }
    }

    @Test
    fun randomStringsDoNotThrow() {
        val random = Random(20261102)
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789:/?#[]@%. "
        repeat(400) {
            val raw = buildString {
                repeat(random.nextInt(40)) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            WebUrlGuard.rejectionReason(raw) { listOf(InetAddress.getByName("8.8.8.8")) }
        }
    }
}
