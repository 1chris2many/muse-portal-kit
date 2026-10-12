package com.muse.gadget.display

import java.net.InetAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

/**
 * The two display commands this Portal advertises in `link.register`, named
 * and shaped like the official firmware's (`esp32/main/noise_control.cpp`),
 * so a Muse that already knows them needs nothing new.
 */
object DisplayCommands {
    const val DRAW_URL = "display.draw_url"
    const val CLEAR = "display.show_animation"

    const val DRAW_URL_DESCRIPTION =
        "Download an image and show it on this Portal's screen while the Portal app is open. " +
            "Takes an https:// URL of a JPEG, PNG or WebP up to 2 MiB; the image is scaled to fit " +
            "the screen. Replies when the image is shown. The image stays until display.show_animation, " +
            "the owner taps Clear, the app leaves the foreground, or the two-minute privacy clear."
    const val DRAW_URL_PARAM = "https:// URL of the image."
    const val DRAW_URL_TIMEOUT_MS = 45_000
    const val CLEAR_DESCRIPTION = "Clear any image shown by display.draw_url and return to the conversation."
}

/**
 * Bounds for images Muse asks this device to show with `display.draw_url`.
 *
 * Pure JVM: the fetch itself lives in the app, but every decision about what
 * may be fetched, how much, and what may be decoded is made here and tested
 * offline. The device sends no cookies or credentials with the fetch.
 */
object DisplayImagePolicy {
    const val MAX_URL_CHARS = 2048
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_SOURCE_EDGE = 4096
    const val MAX_SHOWN_EDGE = 1280
    const val MAX_REDIRECTS = 3
    const val DEADLINE_MS = 30_000L
    const val ACCEPT = "image/jpeg, image/png, image/webp"

    sealed interface Check {
        data class Ok(val url: String) : Check
        data class Rejected(val code: String, val message: String) : Check
    }

    /** https only, a public host name, no credentials, no literal addresses or local names. */
    fun checkUrl(raw: String?): Check {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Check.Rejected("missing_param", "url is required")
        if (text.length > MAX_URL_CHARS) return Check.Rejected("bad_url", "url is too long")
        if (text.any { it.code < 0x21 || it.code > 0x7E }) {
            return Check.Rejected("bad_url", "url must be printable ASCII; percent-encode other characters")
        }
        val uri = try {
            URI(text)
        } catch (e: Exception) {
            return Check.Rejected("bad_url", "url is not valid")
        }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return Check.Rejected("bad_url", "url must use https://")
        if (uri.rawUserInfo != null) return Check.Rejected("bad_url", "url must not carry credentials")
        val host = uri.host ?: return Check.Rejected("bad_url", "url has no host")
        if (!isPublicHostName(host)) {
            return Check.Rejected("bad_url", "url must name a public host, not an address or a local name")
        }
        return Check.Ok(uri.toString())
    }

    /** A DNS name with a dot, not an IP literal and not a local-only suffix. */
    fun isPublicHostName(host: String): Boolean {
        val h = host.trim('[', ']').lowercase().trimEnd('.')
        if (h.isEmpty() || h.contains(':')) return false
        if (h == "localhost" || LOCAL_SUFFIXES.any { h.endsWith(it) }) return false
        if (h.all { it.isDigit() || it == '.' }) return false
        if (!h.contains('.')) return false
        return h.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' }
    }

    /** A redirect may only lead to another acceptable URL, [MAX_REDIRECTS] times at most. */
    fun redirectAllowed(hop: Int, location: String?): Check {
        if (hop >= MAX_REDIRECTS) return Check.Rejected("download_failed", "too many redirects")
        return checkUrl(location)
    }

    /**
     * The resolver's answers for a host, or null when any of them is not a public
     * address. A mixed answer is refused whole: the connection must only ever use
     * addresses vetted here, and this list is what it connects to.
     */
    fun vetAddresses(addresses: List<InetAddress>): List<InetAddress>? =
        if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) null else addresses

    /** True only for a globally routable unicast address, IPv4 or IPv6, including IPv4 embedded in IPv6. */
    fun isPublicAddress(address: InetAddress): Boolean {
        val bytes = address.address
        return when (bytes.size) {
            4 -> isPublicIpv4(bytes)
            16 -> embeddedIpv4(bytes)?.let { isPublicIpv4(it) } ?: isPublicIpv6(bytes)
            else -> false
        }
    }

    /** Milliseconds left of the whole-request budget; zero or less means stop. */
    fun remainingBudgetMs(elapsedMs: Long): Long = DEADLINE_MS - elapsedMs

    private fun isPublicIpv4(b: ByteArray): Boolean {
        val a = b[0].toInt() and 0xFF
        val c = b[1].toInt() and 0xFF
        val d = b[2].toInt() and 0xFF
        return when {
            a == 0 || a == 10 || a == 127 -> false                 // this network, private, loopback
            a == 100 && c in 64..127 -> false                       // carrier-grade NAT (Tailscale lives here)
            a == 169 && c == 254 -> false                           // link-local
            a == 172 && c in 16..31 -> false                        // private
            a == 192 && c == 0 && (d == 0 || d == 2) -> false       // IETF assignments, documentation
            a == 192 && c == 168 -> false                           // private
            a == 198 && c in 18..19 -> false                        // benchmarking
            a == 198 && c == 51 && d == 100 -> false                // documentation
            a == 203 && c == 0 && d == 113 -> false                 // documentation
            a >= 224 -> false                                       // multicast, reserved, broadcast
            else -> true
        }
    }

    /** IPv4 carried inside an IPv6 address: mapped ::ffff:a.b.c.d, NAT64 64:ff9b::/96, 6to4 2002::/16. */
    private fun embeddedIpv4(b: ByteArray): ByteArray? {
        val zeroTo = { n: Int -> (0 until n).all { b[it] == 0.toByte() } }
        val mapped = zeroTo(10) && b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()
        val nat64 = b[0] == 0.toByte() && b[1] == 0x64.toByte() && b[2] == 0xFF.toByte() && b[3] == 0x9B.toByte() &&
            (4 until 12).all { b[it] == 0.toByte() }
        val sixToFour = b[0] == 0x20.toByte() && b[1] == 0x02.toByte()
        return when {
            mapped || nat64 -> b.copyOfRange(12, 16)
            sixToFour -> b.copyOfRange(2, 6)
            else -> null
        }
    }

    private fun isPublicIpv6(b: ByteArray): Boolean {
        val first = b[0].toInt() and 0xFF
        val second = b[1].toInt() and 0xFF
        val unspecifiedOrLoopback = (0 until 15).all { b[it] == 0.toByte() } && (b[15] == 0.toByte() || b[15] == 1.toByte())
        return when {
            unspecifiedOrLoopback -> false
            first == 0xFF -> false                                   // multicast
            first and 0xFE == 0xFC -> false                          // fc00::/7 unique local
            first == 0xFE && second and 0xC0 == 0x80 -> false        // fe80::/10 link-local
            first == 0xFE && second and 0xC0 == 0xC0 -> false        // fec0::/10 site-local
            first == 0x20 && second == 0x01 && b[2] == 0x0D.toByte() && b[3] == 0xB8.toByte() -> false // 2001:db8::/32
            else -> first and 0xE0 == 0x20                           // 2000::/3 global unicast only
        }
    }

    /** A known Content-Length over the cap fails before any body is read. */
    fun lengthAllowed(contentLength: Long): Boolean = contentLength <= MAX_BYTES

    /** "jpeg", "png" or "webp" from the first bytes; null for anything else. */
    fun sniff(head: ByteArray): String? = when {
        head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> "jpeg"
        head.size >= 8 && head.copyOfRange(0, 8).contentEquals(PNG_MAGIC) -> "png"
        head.size >= 12 && head.copyOfRange(0, 4).contentEquals(RIFF) && head.copyOfRange(8, 12).contentEquals(WEBP) -> "webp"
        else -> null
    }

    /**
     * Power-of-two shrink so the longest edge fits [MAX_SHOWN_EDGE], or 0 when
     * the source is larger than [MAX_SOURCE_EDGE] on a side and is not decoded at all.
     */
    fun sampleSize(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0 || width > MAX_SOURCE_EDGE || height > MAX_SOURCE_EDGE) return 0
        var sample = 1
        while (maxOf(width, height) / sample > MAX_SHOWN_EDGE) sample *= 2
        return sample
    }

    private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".internal", ".lan", ".home", ".home.arpa", ".arpa")
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)
}

/**
 * Generation token for images in flight. Every clear path (Clear, the privacy
 * timer, backgrounding, reconnect, `display.show_animation`) revokes requests
 * begun before it, so a slow fetch that completes later cannot put an image back
 * on a screen the owner has cleared. Safe to use from any thread.
 */
class DisplayGuard {
    private val generation = AtomicLong(0)

    /** Token for a request starting now. */
    fun begin(): Long = generation.get()

    /** Invalidates every token handed out so far; returns the new generation. */
    fun revoke(): Long = generation.incrementAndGet()

    /** True only when nothing has been cleared since [begin] handed out [token]. */
    fun valid(token: Long): Boolean = token == generation.get()
}
