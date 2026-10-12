package com.muse.gadget

import com.muse.gadget.session.AddressedReplies
import org.junit.jupiter.api.Test
import kotlin.test.*

class AddressedRepliesTest {
    private var time = 1000L
    private var serial = 0
    private val gate = AddressedReplies("device", now = { time }, nonce = { "generation-${++serial}" }).apply { setForeground(true) }
    private fun input(id: String = "req", kind: AddressedReplies.Kind = AddressedReplies.Kind.VOICE, text: String = "actual input") {
        gate.begin(id, kind, if (kind == AddressedReplies.Kind.TEXT) text else null,
            if (kind == AddressedReplies.Kind.VOICE) "a".repeat(64) else null)
        gate.acknowledge(id, "native-$id")
        if (kind == AddressedReplies.Kind.VOICE) gate.transcript(id, text)
    }
    private fun envelope(id: String = "req", delivery: String = "delivery") =
        AddressedReplies.Envelope(1, delivery, "device", "existing-main", gate.generation, id, "native-$id", "answer", listOf("Yes", "No"))
    private fun reject(code: String, action: () -> Unit) { assertEquals(code, assertFailsWith<AddressedReplies.Rejected>(block = action).code) }

    @Test fun `only exact acknowledged canonical input can be read`() {
        gate.begin("req", AddressedReplies.Kind.TEXT, "actual input")
        reject("no_unique_canonical_request") { gate.read(exactText = "actual input") }
        gate.acknowledge("req", "native-req")
        reject("no_unique_canonical_request") { gate.read(exactText = "Actual input") }
        assertEquals("actual input", gate.read(exactText = "actual input").text)
    }
    @Test fun `no pending list or newest selector exists`() {
        input(); reject("one_exact_selector_required") { gate.read() }
        reject("one_exact_selector_required") { gate.read("actual input", "a".repeat(64)) }
    }
    @Test fun `ambiguous same inputs and hashes fail closed`() {
        input(); input("req2")
        reject("no_unique_canonical_request") { gate.read(exactText = "actual input") }
        reject("no_unique_canonical_request") { gate.read(uploadSha256 = "a".repeat(64)) }
    }
    @Test fun `voice hash read waits for actual canonical transcript`() {
        gate.begin("req", AddressedReplies.Kind.VOICE, uploadSha256 = "a".repeat(64)); gate.acknowledge("req", "native-req")
        reject("no_unique_canonical_request") { gate.read(uploadSha256 = "a".repeat(64)) }
        gate.transcript("req", "real transcript")
        assertEquals("real transcript", gate.read(uploadSha256 = "a".repeat(64)).text)
    }
    @Test fun `reading a wrong input cannot lease another request`() {
        input(); input("req2", text = "second input")
        gate.read(exactText = "second input")
        reject("canonical_read_required") { gate.accept(envelope(), false) }
    }
    @Test fun `delivery binds device conversation generation ack and request`() {
        input(); gate.read(exactText = "actual input")
        for (e in listOf(envelope().copy(deviceId = "phone"), envelope().copy(conversationId = "another"), envelope().copy(generation = "old")))
            reject("wrong_destination") { gate.accept(e, false) }
        reject("canonical_read_required") { gate.accept(envelope().copy(inputMessageId = "different"), false) }
        reject("unknown_request") { gate.accept(envelope().copy(requestId = "unknown"), false) }
    }
    @Test fun `voice final answer may speak but retry never replays`() {
        input(); gate.read(exactText = "actual input")
        assertTrue(gate.accept(envelope(), false).speak)
        gate.rendered("delivery"); gate.speech("delivery", "completed")
        val retry = gate.accept(envelope(), false)
        assertTrue(retry.duplicate); assertFalse(retry.speak); assertNull(retry.text)
        assertEquals(AddressedReplies.Receipt("rendered", "completed"), gate.receipt("delivery"))
    }
    @Test fun `changed retry and second delivery cannot replace a final`() {
        input(); gate.read(exactText = "actual input"); gate.accept(envelope(), false)
        reject("delivery_conflict") { gate.accept(envelope().copy(text = "changed"), false) }
        reject("unknown_request") { gate.accept(envelope(delivery = "other-delivery"), false) }
    }
    @Test fun `typed input and options only cannot authorize speech`() {
        input(kind = AddressedReplies.Kind.TEXT); gate.read(exactText = "actual input")
        assertFalse(gate.accept(envelope(), false).speak)
        input("req2"); gate.read(exactText = "actual input")
        assertFalse(gate.accept(envelope("req2", "d2").copy(text = null), false).speak)
    }
    @Test fun `stop audio denies pending speech without preventing display`() {
        input(); gate.read(exactText = "actual input"); gate.stopAudio()
        val result = gate.accept(envelope(), false)
        assertFalse(result.speak)
        assertEquals("answer", result.text)
    }
    @Test fun `recording rejects without consuming and same envelope can retry`() {
        input(); gate.read(exactText = "actual input")
        reject("recording_retry_same_delivery") { gate.accept(envelope(), true) }
        assertFalse(gate.accept(envelope(), false).duplicate)
    }
    @Test fun `clear background reconnect revoke leases and late acks`() {
        input(); gate.read(exactText = "actual input"); val old = envelope()
        gate.revoke(false); gate.acknowledge("req", "native-req"); gate.transcript("req", "actual input")
        reject("background") { gate.accept(old, false) }
        gate.setForeground(true)
        reject("wrong_destination") { gate.accept(old, false) }
        reject("no_unique_canonical_request") { gate.read(exactText = "actual input") }
    }
    @Test fun `request expiration is exact and cannot be renewed`() {
        input(); gate.read(exactText = "actual input"); time += 120_000
        reject("unknown_request") { gate.accept(envelope(), false) }
        reject("local_request_unavailable") { gate.begin("req", AddressedReplies.Kind.TEXT, "actual input") }
    }
    @Test fun `native final prevents an addressed second reply`() {
        input(); gate.read(exactText = "actual input")
        assertTrue(gate.acceptNative("native-req", true)!!.speak)
        reject("unknown_request") { gate.accept(envelope(), false) }
    }
    @Test fun `addressed final prevents a later native replay`() {
        input(); gate.read(exactText = "actual input"); gate.accept(envelope(), false)
        assertNull(gate.acceptNative("native-req", true))
    }
    @Test fun `native presentation does not consume its final answer`() {
        input(); assertNotNull(gate.acceptNative("native-req", false))
        gate.stopAudio(); assertFalse(gate.acceptNative("native-req", true)!!.speak)
        assertNull(gate.acceptNative("native-req", true))
    }
    @Test fun `mutated caller options cannot change replay identity`() {
        input(); gate.read(exactText = "actual input")
        val labels = mutableListOf("Yes", "No"); gate.accept(envelope().copy(options = labels), false)
        labels[0] = "Forged"
        assertTrue(gate.accept(envelope(), false).duplicate)
    }
    @Test fun `a corrected transcript after canonical read invalidates the lease`() {
        input(); gate.read(exactText = "actual input"); gate.transcript("req", "correction")
        reject("unknown_request") { gate.accept(envelope(), false) }
    }
    @Test fun `remote bad payload stays unconsumed`() {
        input(); gate.read(exactText = "actual input")
        reject("invalid_text") { gate.accept(envelope().copy(text = "a".repeat(16001)), false) }
        reject("empty_content") { gate.accept(envelope().copy(text = null, options = null), false) }
        reject("invalid_options") { gate.accept(envelope().copy(options = listOf(" bad")), false) }
        assertFalse(gate.accept(envelope(), false).duplicate)
    }
    @Test fun `same native ack for two requests invalidates ambiguity`() {
        input(); gate.begin("req2", AddressedReplies.Kind.TEXT, "other")
        gate.acknowledge("req2", "native-req")
        reject("no_unique_canonical_request") { gate.read(exactText = "other") }
        reject("no_unique_canonical_request") { gate.read(exactText = "actual input") }
        assertNull(gate.acceptNative("native-req", true))
    }
    @Test fun `consumed native ack cannot be reused for a new local input`() {
        input(); gate.acceptNative("native-req", true)
        gate.begin("req2", AddressedReplies.Kind.TEXT, "other")
        gate.acknowledge("req2", "native-req")
        reject("no_unique_canonical_request") { gate.read(exactText = "other") }
        assertNull(gate.acceptNative("native-req", true))
    }
    @Test fun `evicted request cannot reappear via delayed ack`() {
        input()
        for (i in 1..20) gate.begin("r$i", AddressedReplies.Kind.TEXT, "input$i")
        gate.acknowledge("req", "native-req")
        reject("no_unique_canonical_request") { gate.read(exactText = "actual input") }
    }
}
