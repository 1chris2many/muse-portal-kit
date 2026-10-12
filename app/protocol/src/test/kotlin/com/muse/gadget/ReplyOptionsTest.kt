package com.muse.gadget

import com.muse.gadget.options.ReplyOptionsGate
import com.muse.gadget.options.ReplyOptionsGate.Presentation
import com.muse.gadget.options.ReplyOptionsGate.Selection
import com.muse.gadget.options.ReplyOptionsPolicy
import com.muse.gadget.options.ReplyOptionsPolicy.Parse
import com.muse.gadget.util.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReplyOptionsTest {
    private var clock = 1_000L
    private fun gate() = ReplyOptionsGate("portal-1", "conv-main") { clock }

    private fun delivery(
        id: String = "d1",
        device: String = "portal-1",
        conv: String = "conv-main",
        request: String = "r1",
        options: List<String?> = listOf("Yes", "No"),
    ) = ReplyOptionsGate.Delivery(id, device, conv, request, options)

    private fun shown(gate: ReplyOptionsGate, delivery: ReplyOptionsGate.Delivery): ReplyOptionsGate.Card {
        val result = gate.present(delivery)
        assertTrue(result is Presentation.Presented, "expected Presented, got $result")
        return (result as Presentation.Presented).card
    }

    private fun rejectedPresent(gate: ReplyOptionsGate, delivery: ReplyOptionsGate.Delivery): String {
        val result = gate.present(delivery)
        assertTrue(result is Presentation.Rejected, "expected Rejected, got $result")
        return (result as Presentation.Rejected).code
    }

    private fun rejectedLabels(raw: List<String?>): String {
        val parse = ReplyOptionsPolicy.checkLabels(raw)
        assertTrue(parse is Parse.Rejected, "expected rejection for $raw, got $parse")
        return (parse as Parse.Rejected).code
    }

    @Test fun `payload parses bounded string labels only`() {
        val ok = ReplyOptionsPolicy.parseContent(Json.parse("""{"type":"options","options":["Yes","No","Maybe later"]}"""))
        assertEquals(Parse.Ok(listOf("Yes", "No", "Maybe later")), ok)

        fun code(json: String): String {
            val parse = ReplyOptionsPolicy.parseContent(Json.parse(json))
            assertTrue(parse is Parse.Rejected, "expected rejection for $json")
            return (parse as Parse.Rejected).code
        }
        assertEquals("bad_payload", code(""""just a string""""))
        assertEquals("bad_payload", code("""{"type":"text","text":"hi"}"""))
        assertEquals("bad_payload", code("""{"type":"options"}"""))
        assertEquals("bad_payload", code("""{"type":"options","options":"Yes"}"""))
        assertEquals("bad_label", code("""{"type":"options","options":["Yes",7]}"""))
        assertEquals("bad_label", code("""{"type":"options","options":[["Yes"]]}"""))
        assertEquals("too_many_options", code("""{"type":"options","options":["1","2","3","4","5","6","7"]}"""))
    }

    @Test fun `labels are single-line, bounded, distinct`() {
        assertEquals("no_options", rejectedLabels(emptyList()))
        assertEquals("bad_label", rejectedLabels(listOf("Yes", null)))
        assertEquals("bad_label", rejectedLabels(listOf("Yes", "   ")))
        assertEquals("bad_label", rejectedLabels(listOf("a".repeat(ReplyOptionsPolicy.MAX_LABEL_CHARS + 1))))
        assertEquals("bad_label", rejectedLabels(listOf("line\nbreak")))
        assertEquals("bad_label", rejectedLabels(listOf("tab\there")))
        assertEquals("duplicate_option", rejectedLabels(listOf("Yes", "Yes")))
        for (label in listOf(" Yes ", "Yes\u2028please", "Yes\u2029please", "Yes\u202eplease"))
            assertEquals("bad_label", rejectedLabels(listOf(label)))
        assertEquals(
            Parse.Ok(listOf("a".repeat(ReplyOptionsPolicy.MAX_LABEL_CHARS), "Yes", "yes")),
            ReplyOptionsPolicy.checkLabels(listOf("a".repeat(ReplyOptionsPolicy.MAX_LABEL_CHARS), "Yes", "yes")),
        )
    }

    @Test fun `wrong origin never shows a card`() {
        val gate = gate()
        gate.openRequest("r1")
        assertEquals("wrong_device", rejectedPresent(gate, delivery(device = "phone-2")))
        assertEquals("wrong_conversation", rejectedPresent(gate, delivery(conv = "conv-side")))
        assertEquals("unknown_request", rejectedPresent(gate, delivery(request = "never-opened")))
        assertEquals("bad_delivery", rejectedPresent(gate, delivery(id = " ")))
        // A remote caller cannot mint a handle: nothing was opened for r9.
        assertEquals("unknown_request", rejectedPresent(gate(), delivery(request = "r9")))
    }

    @Test fun `a tap returns the exact label once and consumes the request`() {
        val gate = gate()
        gate.openRequest("r1")
        val card = shown(gate, delivery(options = listOf("Order the usual", "Show me the menu 📋")))
        val pick = gate.select(card.token, 1, "Show me the menu 📋")
        assertEquals(Selection.Selected("r1", "Show me the menu 📋"), pick)
        // One-shot: the card is gone and the request is consumed.
        assertTrue(gate.select(card.token, 1, "Show me the menu 📋") is Selection.Rejected)
        assertEquals("request_consumed", rejectedPresent(gate, delivery(id = "d2")))
        gate.openRequest("r1")
        assertEquals("request_consumed", rejectedPresent(gate, delivery(id = "d3")))
    }

    @Test fun `duplicate deliveries get a receipt, changed ones a conflict`() {
        val gate = gate()
        gate.openRequest("r1")
        shown(gate, delivery())
        assertEquals(Presentation.Receipt, gate.present(delivery()))
        assertEquals("delivery_conflict", rejectedPresent(gate, delivery(options = listOf("Yes", "Never"))))
        // The replay receipt survives selection and clearing: no second render, ever.
        val card = shown(gate, delivery(id = "d2"))
        gate.select(card.token, 0, "Yes")
        assertEquals(Presentation.Receipt, gate.present(delivery()))
        gate.clear()
        assertEquals(Presentation.Receipt, gate.present(delivery()))
    }

    @Test fun `stale tokens cannot select`() {
        val gate = gate()
        gate.openRequest("r1")
        gate.openRequest("r2")
        val first = shown(gate, delivery())
        val second = shown(gate, delivery(id = "d2", request = "r2", options = listOf("A", "B")))
        // The replaced card's token is dead; the live card still works.
        assertTrue(gate.select(first.token, 0, "Yes") is Selection.Rejected)
        assertEquals("bad_index", (gate.select(second.token, 5, "A") as Selection.Rejected).code)
        assertEquals("label_mismatch", (gate.select(second.token, 0, "B") as Selection.Rejected).code)
        assertEquals(Selection.Selected("r2", "A"), gate.select(second.token, 0, "A"))
    }

    @Test fun `cards and requests expire on their own clocks`() {
        val gate = gate()
        gate.openRequest("r1")
        val card = shown(gate, delivery())
        clock += ReplyOptionsPolicy.CARD_LIFETIME_MS + 1
        assertEquals("stale_card", (gate.select(card.token, 0, "Yes") as Selection.Rejected).code)
        // The request handle itself also ages out for new deliveries.
        val late = gate()
        late.openRequest("r1")
        clock += ReplyOptionsPolicy.REQUEST_LIFETIME_MS + 1
        assertEquals("unknown_request", rejectedPresent(late, delivery(id = "d9")))
    }

    @Test fun `clear drops the card and every pending handle`() {
        val gate = gate()
        gate.openRequest("r1")
        gate.openRequest("r2")
        val card = shown(gate, delivery())
        gate.clear()
        assertEquals("stale_card", (gate.select(card.token, 0, "Yes") as Selection.Rejected).code)
        // A held request cannot repopulate a cleared screen.
        assertEquals("unknown_request", rejectedPresent(gate, delivery(id = "d2", request = "r2")))
        // A fresh local send after Clear works again.
        gate.openRequest("r3")
        shown(gate, delivery(id = "d3", request = "r3"))
    }

    @Test fun `a young card cannot outlive its old request or revive by repeating open`() {
        val gate = gate(); gate.openRequest("r1")
        clock += ReplyOptionsPolicy.REQUEST_LIFETIME_MS - 1
        gate.openRequest("r1") // Retries must not renew a pending request's authority.
        val card = shown(gate, delivery())
        assertEquals(clock + 1, card.expiresAtMs)
        clock++
        assertTrue(gate.select(card.token, 0, "Yes") is Selection.Rejected)
    }

    @Test fun `evicting a request invalidates its visible card`() {
        val gate = gate(); gate.openRequest("r1")
        val card = shown(gate, delivery())
        repeat(20) { gate.openRequest("new-$it") }
        assertTrue(gate.select(card.token, 0, "Yes") is Selection.Rejected)
    }

    @Test fun `delivery identity includes request not just labels`() {
        val gate = gate(); gate.openRequest("r1"); gate.openRequest("r2")
        shown(gate, delivery())
        assertEquals("delivery_conflict", rejectedPresent(gate, delivery(request = "r2")))
        assertEquals("bad_delivery", rejectedPresent(gate, delivery(id = "x".repeat(129))))
    }

    @Test fun `mutating caller or UI lists cannot alter the selected label`() {
        val gate = gate(); gate.openRequest("r1")
        val input = mutableListOf<String?>("Yes", "No")
        val card = shown(gate, delivery(options = input))
        input[0] = "Changed input"
        (card.labels as MutableList<String>)[0] = "Changed UI"
        assertEquals("label_mismatch", (gate.select(card.token, 0, "Changed UI") as Selection.Rejected).code)
        assertEquals(Selection.Selected("r1", "Yes"), gate.select(card.token, 0, "Yes"))
        assertEquals(Presentation.Receipt, gate.present(delivery()))
    }

    @Test fun `card expires exactly at its deadline`() {
        val gate = gate(); gate.openRequest("r1")
        val card = shown(gate, delivery())
        clock = card.expiresAtMs
        assertTrue(gate.select(card.token, 0, "Yes") is Selection.Rejected)
    }
}
