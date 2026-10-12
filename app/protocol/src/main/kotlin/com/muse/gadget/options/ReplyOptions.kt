package com.muse.gadget.options

import com.muse.gadget.util.JsonValue

/**
 * Bounds and shape for Muse-authored reply options (plain `create_options`
 * labels, per the addressed-delivery design and issue #2).
 *
 * Labels are opaque text for native Portal buttons. A tap turns the selected
 * label into a new, user-authored local input through the existing send path —
 * exact text only. A label is never a URL to fetch, a command to run, or code
 * to render; nothing in this module executes, fetches, or displays web content.
 */
object ReplyOptionsPolicy {
    const val CONTENT_TYPE = "options"
    const val MAX_OPTIONS = 6
    const val MAX_LABEL_CHARS = 80

    /** Shown options follow the screen's two-minute privacy clear. */
    const val CARD_LIFETIME_MS = 120_000L

    /** A local request stops accepting option cards after this long. */
    const val REQUEST_LIFETIME_MS = 5 * 60 * 1000L

    sealed interface Parse {
        data class Ok(val labels: List<String>) : Parse
        data class Rejected(val code: String, val message: String) : Parse
    }

    /** `{"type":"options","options":["…"]}` with string labels only, bounds checked first. */
    fun parseContent(part: JsonValue): Parse {
        if (part !is JsonValue.Obj) return Parse.Rejected("bad_payload", "options content must be an object")
        val type = (part.fields["type"] as? JsonValue.Str)?.value
        if (type != CONTENT_TYPE) return Parse.Rejected("bad_payload", "content type is not $CONTENT_TYPE")
        val arr = part.fields["options"]
            ?: return Parse.Rejected("bad_payload", "options is required")
        if (arr !is JsonValue.Arr) return Parse.Rejected("bad_payload", "options must be an array")
        if (arr.items.size > MAX_OPTIONS) return Parse.Rejected("too_many_options", "at most $MAX_OPTIONS options")
        return checkLabels(arr.items.map { (it as? JsonValue.Str)?.value })
    }

    /** 1..[MAX_OPTIONS] single-line labels: trimmed, bounded, no control characters, no duplicates. */
    fun checkLabels(raw: List<String?>): Parse {
        if (raw.isEmpty()) return Parse.Rejected("no_options", "at least one option is required")
        if (raw.size > MAX_OPTIONS) return Parse.Rejected("too_many_options", "at most $MAX_OPTIONS options")
        val labels = ArrayList<String>(raw.size)
        for (entry in raw) {
            val label = entry?.trim()
                ?: return Parse.Rejected("bad_label", "every option must be a string")
            if (label.isEmpty()) return Parse.Rejected("bad_label", "option labels must not be blank")
            if (label.length > MAX_LABEL_CHARS) {
                return Parse.Rejected("bad_label", "option labels are capped at $MAX_LABEL_CHARS characters")
            }
            if (label.any { it.isISOControl() }) {
                return Parse.Rejected("bad_label", "option labels must be single-line printable text")
            }
            // Two identical buttons would send indistinguishable exact text.
            if (labels.any { it == label }) {
                return Parse.Rejected("duplicate_option", "option labels must be distinct")
            }
            labels.add(label)
        }
        return Parse.Ok(labels)
    }
}

/**
 * One Portal, one conversation, one shown card. A card may appear only for a
 * live request handle created by a real local send on this device in this
 * conversation; remote callers cannot mint handles. Selection is one-shot per
 * request and returns the exact shown label for the app to submit as a new
 * user-authored input — nothing here auto-selects, synthesizes a user message,
 * or touches a network. Identical delivery retries get a receipt instead of a
 * second render; a changed retry under the same delivery id is a conflict.
 * All calls are serialized on the connection executor; state is memory-only.
 */
class ReplyOptionsGate(
    private val deviceId: String,
    private val conversationId: String,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** Addressing fields come from the (future) delivery envelope; options stay unparsed until vetted. */
    data class Delivery(
        val deliveryId: String,
        val deviceId: String,
        val conversationId: String,
        val requestId: String,
        val options: List<String?>,
    )

    /** What the UI may render: [token] names it for [select]; [labels] are the button texts. */
    data class Card(val token: Long, val requestId: String, val labels: List<String>)

    sealed interface Presentation {
        data class Presented(val card: Card) : Presentation

        /** Identical retry of a delivery already seen; acknowledge without rendering again. */
        data object Receipt : Presentation
        data class Rejected(val code: String, val message: String) : Presentation
    }

    sealed interface Selection {
        /** The exact label the owner tapped; the app sends it as a new local input. */
        data class Selected(val requestId: String, val text: String) : Selection
        data class Rejected(val code: String, val message: String) : Selection
    }

    private val pending = LinkedHashMap<String, Long>()
    private val consumed = LinkedHashSet<String>()
    private val seen = LinkedHashMap<String, List<String>>()
    private var card: Card? = null
    private var cardShownAt = 0L
    private var nextToken = 1L

    /** A real local send on this Portal opens a handle; nothing else may call this. */
    fun openRequest(requestId: String) {
        prune()
        if (requestId.isBlank() || requestId in consumed) return
        pending[requestId] = now()
        while (pending.size > 20) pending.remove(pending.keys.first())
    }

    fun present(delivery: Delivery): Presentation {
        prune()
        if (delivery.deviceId != deviceId) {
            return Presentation.Rejected("wrong_device", "delivery is addressed to another device")
        }
        if (delivery.conversationId != conversationId) {
            return Presentation.Rejected("wrong_conversation", "delivery is for another conversation")
        }
        if (delivery.deliveryId.isBlank()) return Presentation.Rejected("bad_delivery", "delivery id is required")
        val parsed = when (val p = ReplyOptionsPolicy.checkLabels(delivery.options)) {
            is ReplyOptionsPolicy.Parse.Ok -> p.labels
            is ReplyOptionsPolicy.Parse.Rejected -> return Presentation.Rejected(p.code, p.message)
        }
        val earlier = seen[delivery.deliveryId]
        if (earlier != null) {
            return if (earlier == parsed) Presentation.Receipt
            else Presentation.Rejected("delivery_conflict", "a delivery id cannot change its content")
        }
        if (delivery.requestId in consumed) {
            return Presentation.Rejected("request_consumed", "that request already has its answer")
        }
        if (delivery.requestId !in pending) {
            return Presentation.Rejected("unknown_request", "no live local request matches")
        }
        seen[delivery.deliveryId] = parsed
        while (seen.size > 200) seen.remove(seen.keys.first())
        val presented = Card(nextToken++, delivery.requestId, parsed)
        card = presented
        cardShownAt = now()
        return Presentation.Presented(presented)
    }

    /** Only an owner tap calls this; [shownLabel] must still be the label under the finger. */
    fun select(token: Long, index: Int, shownLabel: String): Selection {
        prune()
        val live = card
        if (live == null || live.token != token) {
            return Selection.Rejected("stale_card", "these options are no longer on screen")
        }
        if (index !in live.labels.indices) return Selection.Rejected("bad_index", "no such option")
        if (live.labels[index] != shownLabel) {
            return Selection.Rejected("label_mismatch", "the tapped label is not the stored label")
        }
        card = null
        pending.remove(live.requestId)
        consumed.add(live.requestId)
        while (consumed.size > 500) consumed.remove(consumed.first())
        return Selection.Selected(live.requestId, live.labels[index])
    }

    /**
     * Clear, backgrounding, the privacy timer, reconnect: the card and every
     * pending handle go away, so a held request cannot repopulate a cleared
     * screen. Replay memory survives, so an old delivery still answers with a
     * receipt instead of rendering again.
     */
    fun clear() {
        card = null
        pending.clear()
    }

    private fun prune() {
        val cutoff = now() - ReplyOptionsPolicy.REQUEST_LIFETIME_MS
        pending.entries.removeAll { it.value <= cutoff }
        if (card != null && now() - cardShownAt > ReplyOptionsPolicy.CARD_LIFETIME_MS) card = null
    }
}
