package com.muse.gadget.display

import java.net.URI

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
