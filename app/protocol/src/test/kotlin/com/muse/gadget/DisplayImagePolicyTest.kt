package com.muse.gadget

import com.muse.gadget.display.DisplayGuard
import com.muse.gadget.display.DisplayImagePolicy
import com.muse.gadget.display.DisplayImagePolicy.Check
import java.net.InetAddress
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DisplayImagePolicyTest {
    private fun rejected(url: String?): String {
        val check = DisplayImagePolicy.checkUrl(url)
        assertTrue(check is Check.Rejected, "expected rejection for $url, got $check")
        return (check as Check.Rejected).code
    }

    @Test fun `only https urls to public host names are fetched`() {
        assertEquals(Check.Ok("https://cdn.example.com/a/b.jpg?x=1"), DisplayImagePolicy.checkUrl(" https://cdn.example.com/a/b.jpg?x=1 "))
        assertTrue(DisplayImagePolicy.checkUrl("HTTPS://Example.ORG/pic.png") is Check.Ok)
        assertEquals("missing_param", rejected(null))
        assertEquals("missing_param", rejected("   "))
        assertEquals("bad_url", rejected("http://example.com/a.jpg"))
        assertEquals("bad_url", rejected("file:///sdcard/x.jpg"))
        assertEquals("bad_url", rejected("content://media/1"))
        assertEquals("bad_url", rejected("https://user:pw@example.com/a.jpg"))
        assertEquals("bad_url", rejected("https://example.com/sp ace.jpg"))
        assertEquals("bad_url", rejected("https://exämple.com/a.jpg"))
        assertEquals("bad_url", rejected("https://" + "a".repeat(2100) + ".com/x"))
        assertEquals("bad_url", rejected("not a url"))
    }

    @Test fun `addresses and local names are never fetched`() {
        for (host in listOf("127.0.0.1", "10.0.0.5", "192.168.1.25", "[::1]", "[fe80::1]", "localhost",
            "portal.localhost", "printer.local", "nas.lan", "svc.internal", "router.home.arpa", "mini")) {
            assertEquals("bad_url", rejected("https://$host/a.jpg"), host)
            assertFalse(DisplayImagePolicy.isPublicHostName(host), host)
        }
        assertTrue(DisplayImagePolicy.isPublicHostName("images.example.com"))
        assertTrue(DisplayImagePolicy.isPublicHostName("example.com."))
    }

    @Test fun `redirects are bounded and re-checked`() {
        assertTrue(DisplayImagePolicy.redirectAllowed(0, "https://b.example.com/x.png") is Check.Ok)
        assertEquals("bad_url", (DisplayImagePolicy.redirectAllowed(1, "http://b.example.com/x.png") as Check.Rejected).code)
        assertEquals("download_failed", (DisplayImagePolicy.redirectAllowed(DisplayImagePolicy.MAX_REDIRECTS, "https://b.example.com/x.png") as Check.Rejected).code)
        assertTrue(DisplayImagePolicy.lengthAllowed(-1))
        assertTrue(DisplayImagePolicy.lengthAllowed(DisplayImagePolicy.MAX_BYTES.toLong()))
        assertFalse(DisplayImagePolicy.lengthAllowed(DisplayImagePolicy.MAX_BYTES + 1L))
    }

    @Test fun `format is taken from the bytes not the headers`() {
        assertEquals("jpeg", DisplayImagePolicy.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("png", DisplayImagePolicy.sniff(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)))
        assertEquals("webp", DisplayImagePolicy.sniff("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.US_ASCII)))
        assertNull(DisplayImagePolicy.sniff("GIF89a".toByteArray(Charsets.US_ASCII)))
        assertNull(DisplayImagePolicy.sniff("<svg xmlns".toByteArray(Charsets.US_ASCII)))
        assertNull(DisplayImagePolicy.sniff("<!doctype html>".toByteArray(Charsets.US_ASCII)))
        assertNull(DisplayImagePolicy.sniff(byteArrayOf()))
    }

    @Test fun `decode is shrunk to the screen and refused above the source limit`() {
        assertEquals(1, DisplayImagePolicy.sampleSize(800, 600))
        assertEquals(1, DisplayImagePolicy.sampleSize(1280, 720))
        assertEquals(2, DisplayImagePolicy.sampleSize(2000, 1500))
        assertEquals(4, DisplayImagePolicy.sampleSize(4096, 4096))
        assertEquals(0, DisplayImagePolicy.sampleSize(4097, 100))
        assertEquals(0, DisplayImagePolicy.sampleSize(0, 0))
        assertEquals(0, DisplayImagePolicy.sampleSize(-1, 50))
    }

    private fun addr(text: String): InetAddress = InetAddress.getByName(text) // literals only: no DNS

    @Test fun `resolved addresses must be public, including those embedded in IPv6`() {
        val private = listOf("10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.1", "127.0.0.1", "127.8.8.8",
            "0.0.0.0", "169.254.1.1", "100.64.0.1", "100.127.255.255", "192.0.0.1", "192.0.2.1", "198.18.0.1",
            "198.51.100.7", "203.0.113.9", "224.0.0.1", "240.0.0.1", "255.255.255.255",
            "::1", "::", "fe80::1", "fec0::1", "fc00::1", "fd12:3456::1", "ff02::1", "2001:db8::1",
            "::ffff:10.0.0.1", "::ffff:127.0.0.1", "::ffff:192.168.1.1", "64:ff9b::a00:1", "2002:c0a8:101::1")
        for (text in private) assertFalse(DisplayImagePolicy.isPublicAddress(addr(text)), text)
        val public = listOf("8.8.8.8", "93.184.216.34", "172.32.0.1", "100.128.0.1", "100.63.255.255", "1.1.1.1",
            "2606:4700::1111", "2a00:1450:4001::8a", "::ffff:8.8.8.8", "64:ff9b::808:808", "2002:808:808::1")
        for (text in public) assertTrue(DisplayImagePolicy.isPublicAddress(addr(text)), text)
    }

    @Test fun `a resolver answer with any private address is refused whole`() {
        val good = listOf(addr("93.184.216.34"), addr("2606:4700::1111"))
        assertEquals(good, DisplayImagePolicy.vetAddresses(good))
        assertNull(DisplayImagePolicy.vetAddresses(listOf(addr("93.184.216.34"), addr("10.0.0.5"))))
        assertNull(DisplayImagePolicy.vetAddresses(listOf(addr("2606:4700::1111"), addr("::ffff:192.168.1.1"))))
        assertNull(DisplayImagePolicy.vetAddresses(listOf(addr("127.0.0.1"))))
        assertNull(DisplayImagePolicy.vetAddresses(emptyList()))
    }

    @Test fun `the deadline is one budget for the whole request`() {
        assertEquals(DisplayImagePolicy.DEADLINE_MS, DisplayImagePolicy.remainingBudgetMs(0))
        assertEquals(1_000L, DisplayImagePolicy.remainingBudgetMs(DisplayImagePolicy.DEADLINE_MS - 1_000))
        assertTrue(DisplayImagePolicy.remainingBudgetMs(DisplayImagePolicy.DEADLINE_MS) <= 0)
        assertTrue(DisplayImagePolicy.remainingBudgetMs(DisplayImagePolicy.DEADLINE_MS + 5) <= 0)
    }

    @Test fun `an image completing after any clear path is refused`() {
        for (path in listOf("Clear", "privacy expiry", "backgrounding", "reconnect", "display.show_animation")) {
            val guard = DisplayGuard()
            val token = guard.begin()
            guard.revoke()
            assertFalse(guard.valid(token), "fetch finished after $path")
        }
    }

    @Test fun `only requests begun after the last clear are shown`() {
        val guard = DisplayGuard()
        val untouched = guard.begin()
        assertTrue(guard.valid(untouched))
        val older = guard.begin()
        guard.revoke()
        val newer = guard.begin()
        assertFalse(guard.valid(older))
        assertTrue(guard.valid(newer))
        guard.revoke(); guard.revoke()
        assertFalse(guard.valid(newer))
        assertTrue(guard.valid(guard.begin()))
    }
}
