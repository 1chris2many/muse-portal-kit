package com.muse.gadget

import com.muse.gadget.session.AddressedReplies
import com.muse.gadget.session.InlineMedia
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.*

class InlineMediaTest {
    @Test fun `base64 is canonical bounded and nonempty`() {
        assertContentEquals(byteArrayOf(1,2,3), InlineMedia.decode("AQID"))
        for (bad in listOf("", "AQID\n", "AQI", "@@==", "AR==", "data:audio/mpeg;base64,AQID", "A".repeat(((InlineMedia.MAX_BYTES+2)/3)*4+4)))
            assertFailsWith<AddressedReplies.Rejected> { InlineMedia.decode(bad) }
        val tooLarge = Base64.getEncoder().encodeToString(ByteArray(InlineMedia.MAX_BYTES + 1))
        assertFailsWith<AddressedReplies.Rejected> { InlineMedia.decode(tooLarge) }
    }
    @Test fun `identity is computed from bytes and strict media type`() {
        val a = InlineMedia.identity("image/png", byteArrayOf(1,2,3))
        val b = InlineMedia.identity("image/png", byteArrayOf(1,2,4))
        assertNotEquals(a.sha256, b.sha256)
        assertTrue(InlineMedia.valid(a, false)); assertFalse(InlineMedia.valid(a, true))
        assertFalse(InlineMedia.valid(a.copy(mime = "image/svg+xml"), false))
        assertFalse(InlineMedia.valid(a.copy(bytes = 0), false))
    }
    @Test fun `mp3 signature is only a prerequisite to actual player preparation`() {
        assertTrue(InlineMedia.looksLikeMp3(byteArrayOf(73,68,51,4)))
        assertTrue(InlineMedia.looksLikeMp3(byteArrayOf(-1,-5,-112,0)))
        assertFalse(InlineMedia.looksLikeMp3("<html>".toByteArray()))
        assertFalse(InlineMedia.looksLikeMp3(byteArrayOf(-1,-1,0,0)))
        assertFalse(InlineMedia.looksLikeMp3(byteArrayOf(73,68,51)))
    }
}
