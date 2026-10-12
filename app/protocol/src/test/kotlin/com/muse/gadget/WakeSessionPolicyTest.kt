package com.muse.gadget

import com.muse.gadget.audio.WakeSessionPolicy
import com.muse.gadget.audio.WakePhrase
import com.muse.gadget.audio.WakeUtteranceEndpoint
import com.muse.gadget.audio.CaptureGeneration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WakeSessionPolicyTest {
    @Test fun `queued recording cannot survive clear cancel or a newer capture`() {
        val g = CaptureGeneration()
        val first = g.begin(); assertTrue(g.valid(first))
        g.revoke(); assertFalse(g.valid(first))
        val second = g.begin(); assertTrue(g.valid(second)); assertFalse(g.valid(first))
        val third = g.begin(); assertTrue(g.valid(third)); assertFalse(g.valid(second))
    }
    private fun ready(p: WakeSessionPolicy) = p.update(true, true, true, true, false, false)
    @Test fun `default and every missing prerequisite forbid wake`() {
        val p = WakeSessionPolicy()
        assertNull(p.lease(0))
        for (i in 0..5) {
            p.update(i != 0, i != 1, i != 2, i != 3, i == 4, i == 5)
            assertNull(p.lease(10000))
        }
    }
    @Test fun `a detection is one shot with cooldown`() {
        val p = WakeSessionPolicy(); ready(p)
        val lease = p.lease(0)!!
        assertTrue(p.claim(lease, 0.95, 0))
        assertFalse(p.claim(lease, 0.95, 0))
        assertNull(p.lease(2999)); assertNotNull(p.lease(3000))
    }
    @Test fun `stale detection cannot survive stop restart or explicit cancel`() {
        val p = WakeSessionPolicy(); ready(p); val old = p.lease(0)!!
        p.update(false, true, true, true, false, false); ready(p)
        assertFalse(p.claim(old, 1.0, 0))
        val next = p.lease(0)!!; p.revoke(0)
        assertFalse(p.claim(next, 1.0, 4000))
    }
    @Test fun `bad or low scores never authorize recording`() {
        val p = WakeSessionPolicy(); ready(p); val lease = p.lease(0)!!
        for (score in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.89, 1.1))
            assertFalse(p.claim(lease, score, 0))
        assertTrue(p.claim(lease, 0.90, 0))
    }
    @Test fun `exact nova only with plausible confidence and duration`() {
        assertTrue(WakePhrase.accepts("Nova", "nova", 0.99, 0.6))
        for (text in listOf("supernova", "nova scotia", "[unk]", "hey nova", ""))
            assertFalse(WakePhrase.accepts(text, "nova", 1.0, 0.6))
        assertFalse(WakePhrase.accepts("nova", "no", 1.0, 0.6))
        assertFalse(WakePhrase.accepts("nova", "nova", 0.6, 0.6))
        assertFalse(WakePhrase.accepts("nova", "nova", 1.0, 0.01))
    }
    @Test fun `empty wake expires without sendable speech`() {
        val e = WakeUtteranceEndpoint(); val silence = ByteArray(640)
        repeat(249) { assertFalse(e.accept(silence, silence.size)) }
        assertTrue(e.accept(silence, silence.size)); assertFalse(e.hasSpeech)
    }
    @Test fun `speech ends after silence and sustained noise has a hard limit`() {
        val voiced = ByteArray(640) { if (it % 2 == 0) 0 else 8 }
        val e = WakeUtteranceEndpoint()
        repeat(10) { assertFalse(e.accept(voiced, voiced.size)) }
        assertTrue(e.hasSpeech)
        repeat(59) { assertFalse(e.accept(ByteArray(640), 640)) }
        assertTrue(e.accept(ByteArray(640), 640))
        val continuous = WakeUtteranceEndpoint()
        repeat(749) { assertFalse(continuous.accept(voiced, voiced.size)) }
        assertTrue(continuous.accept(voiced, voiced.size))
    }
}
