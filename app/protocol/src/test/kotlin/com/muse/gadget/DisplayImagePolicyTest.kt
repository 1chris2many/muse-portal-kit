package com.muse.gadget

import com.muse.gadget.display.DisplayImagePolicy
import com.muse.gadget.display.DisplayImagePolicy.Check
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
}
