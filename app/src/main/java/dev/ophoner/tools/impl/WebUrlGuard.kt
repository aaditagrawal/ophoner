package dev.ophoner.tools.impl

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException

/**
 * SSRF checks for web_fetch. The shared OkHttp client still follows redirects
 * for provider APIs, so this guard is applied only by the fetch client.
 *
 * Returns null when the URL may be fetched.
 */
internal object WebUrlGuard {
    fun rejectionReason(
        rawUrl: String,
        resolveHost: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
    ): String? {
        if (rawUrl.isBlank()) return "URL is blank"
        if (rawUrl.any { it.isISOControl() || it == '\\' || it == ' ' }) {
            return "URL contains a forbidden character"
        }
        val httpUrl = rawUrl.toHttpUrlOrNull() ?: return "malformed URL"
        if (httpUrl.username.isNotEmpty() || httpUrl.password.isNotEmpty()) {
            return "URL userinfo is not allowed"
        }
        val scheme = httpUrl.scheme.lowercase()
        if (scheme != "http" && scheme != "https") {
            return "scheme '$scheme' is not allowed (only http/https)"
        }
        val host = httpUrl.host.lowercase()
        if (host.isBlank()) return "URL has no host component"
        ambiguousHost(rawUrl, host)?.let { return it }
        blockedHostName(host)?.let { return it }
        literalAddress(host)?.let { address ->
            return addressBlockReason(address)?.let { "host is $it ($host)" }
        }
        val addresses = try {
            resolveHost(host)
        } catch (e: Exception) {
            return "host could not be resolved: ${e.message}"
        }
        if (addresses.isEmpty()) return "host could not be resolved"
        for (address in addresses) {
            addressBlockReason(address)?.let { return "host resolves to $it (${address.hostAddress})" }
        }
        return null
    }

    fun addressBlockReason(address: InetAddress): String? {
        val mapped = (address as? Inet6Address)?.let(::mappedIpv4)
        if (mapped != null) {
            return addressBlockReason(mapped)?.let { "IPv4-mapped $it" }
        }
        if (address.isLoopbackAddress) return "loopback address"
        if (address.isAnyLocalAddress) return "any-local address"
        if (address.isLinkLocalAddress) return "link-local address"
        if (address.isSiteLocalAddress) return "site-local address"
        if (address.isMulticastAddress) return "multicast address"
        if (address is Inet4Address) {
            val bytes = address.address
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0) return "this-network 0.0.0.0/8"
            if (b0 == 10) return "private range 10.0.0.0/8"
            if (b0 == 100 && b1 in 64..127) return "CGNAT 100.64.0.0/10"
            if (b0 == 127) return "loopback 127.0.0.0/8"
            if (b0 == 169 && b1 == 254) return "link-local 169.254.0.0/16"
            if (b0 == 172 && b1 in 16..31) return "private range 172.16.0.0/12"
            if (b0 == 192 && b1 == 168) return "private range 192.168.0.0/16"
            if (b0 >= 224) return "multicast or reserved address"
        }
        if (address is Inet6Address) {
            val bytes = address.address
            val b0 = bytes[0].toInt() and 0xFF
            if (b0 and 0xFE == 0xFC) return "ULA fc00::/7"
            val firstTwo = ((b0 shl 8) or (bytes[1].toInt() and 0xFF)) and 0xFFC0
            if (firstTwo == 0xFE80) return "IPv6 link-local fe80::/10"
        }
        return null
    }

    private fun ambiguousHost(rawUrl: String, httpHost: String): String? {
        val uri = try {
            URI(rawUrl)
        } catch (_: URISyntaxException) {
            return "malformed URL"
        }
        val uriHost = uri.host ?: return "ambiguous host"
        val left = uriHost.trim().lowercase().removePrefix("[").removeSuffix("]").trimEnd('.')
        val right = httpHost.removePrefix("[").removeSuffix("]").trimEnd('.')
        if (left != right) return "ambiguous host"
        return null
    }

    private fun blockedHostName(host: String): String? {
        val bare = host.trimEnd('.')
        if (bare == "localhost" || bare.endsWith(".localhost")) return "host '$host' is blocked"
        if (bare.endsWith(".local")) return "host ending in .local is blocked"
        if (bare.endsWith(".internal")) return "host ending in .internal is blocked"
        return null
    }

    private fun literalAddress(host: String): InetAddress? {
        val looksNumeric = host.indexOf(':') >= 0 || host.all { it.isDigit() || it == '.' }
        if (!looksNumeric) return null
        return try {
            InetAddress.getByName(host)
        } catch (_: Exception) {
            null
        }
    }

    private fun mappedIpv4(address: Inet6Address): Inet4Address? {
        val bytes = address.address
        for (index in 0..9) {
            if (bytes[index] != 0.toByte()) return null
        }
        if (bytes[10] != 0xff.toByte() || bytes[11] != 0xff.toByte()) return null
        val mapped = InetAddress.getByAddress(bytes.copyOfRange(12, 16))
        return mapped as? Inet4Address
    }
}

/** Re-checks addresses at connect time so a DNS change after the first lookup still fails closed. */
internal object FetchGuardingDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val resolved = Dns.SYSTEM.lookup(hostname)
        for (address in resolved) {
            WebUrlGuard.addressBlockReason(address)?.let { reason ->
                throw UnknownHostException(reason)
            }
        }
        return resolved
    }
}
