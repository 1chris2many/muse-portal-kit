package com.muse.gadget.session

import com.muse.gadget.options.ReplyOptionsPolicy
import java.security.MessageDigest
import java.util.UUID

/** Memory-only exact-request bridge. All methods are called on the Android UI thread.
 * Authentication belongs to the outer adapter. Nothing here creates a user input or performs IO.
 */
class AddressedReplies(
    val deviceId: String,
    val conversationId: String = "existing-main",
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val nonce: () -> String = { UUID.randomUUID().toString() },
) {
    class Rejected(val code: String) : IllegalArgumentException(code)
    enum class Kind { TEXT, VOICE, PHOTO }
    data class Canonical(val deviceId: String, val conversationId: String, val generation: String,
        val requestId: String, val inputMessageId: String, val kind: Kind, val text: String,
        val uploadSha256: String?, val expiresAtMs: Long)
    data class Envelope(val version: Int, val deliveryId: String, val deviceId: String,
        val conversationId: String, val generation: String, val requestId: String,
        val inputMessageId: String, val text: String?, val options: List<String>?)
    data class Accepted(val duplicate: Boolean, val speak: Boolean, val requestId: String,
        val text: String?, val options: List<String>?, val expiresAtMs: Long)
    data class Receipt(val state: String, val speech: String)
    data class NativeGrant(val speak: Boolean)
    private data class Input(val kind: Kind, var text: String?, val hash: String?, val expires: Long,
        var messageId: String? = null, var read: Boolean = false, var speechAllowed: Boolean = true)
    private data class Seen(val envelope: Envelope, var receipt: Receipt)
    private val inputs = LinkedHashMap<String, Input>()
    private val used = HashSet<String>()
    // Keep ACK ownership after consumption/expiry so a reused native ID cannot
    // authorize another input in this generation.
    private val nativeOwners = HashMap<String, String>()
    private val receipts = LinkedHashMap<String, Seen>()
    var generation: String = nonce()
        private set
    private var foreground = false

    /** Only an actual local submit opens a request. Clear before a late ACK must prevent resurrection. */
    fun begin(id: String, kind: Kind, text: String? = null, uploadSha256: String? = null) {
        prune()
        if (!foreground || !validId(id) || id in used || used.size >= 100) reject("local_request_unavailable")
        if (kind == Kind.TEXT && (text.isNullOrBlank() || text.toByteArray().size > 16_000)) reject("invalid_local_text")
        if (uploadSha256 != null && (kind != Kind.VOICE || !uploadSha256.matches(Regex("[a-f0-9]{64}")))) reject("invalid_upload_hash")
        used.add(id)
        inputs[id] = Input(kind, text, uploadSha256, now() + 120_000)
        while (inputs.size > 20) inputs.remove(inputs.keys.first())
    }

    fun acknowledge(id: String, nativeId: String) {
        prune()
        val input = inputs[id] ?: return
        if (!validId(nativeId)) { inputs.remove(id); return }
        nativeOwners[nativeId]?.takeIf { it != id }?.let { previous ->
            inputs.remove(previous); inputs.remove(id); return
        }
        if (input.messageId != null && input.messageId != nativeId) { inputs.remove(id); return }
        nativeOwners[nativeId] = id
        input.messageId = nativeId
    }

    fun transcript(id: String, text: String) {
        prune()
        val input = inputs[id] ?: return
        if (input.kind != Kind.VOICE || input.messageId == null || text.isBlank() || text.toByteArray().size > 16_000) return
        // A lease cannot silently change the canonical input after the composer read it.
        if (input.read && input.text != text) { inputs.remove(id); return }
        input.text = text
    }

    /** Exactly one selector, no pending-list/latest-request API and no fuzzy or timing matching. */
    fun read(exactText: String? = null, uploadSha256: String? = null): Canonical {
        prune()
        if (!foreground) reject("background")
        if ((exactText == null) == (uploadSha256 == null)) reject("one_exact_selector_required")
        if (exactText != null && (exactText.isBlank() || exactText.toByteArray().size > 16_000)) reject("invalid_exact_text")
        if (uploadSha256 != null && !uploadSha256.matches(Regex("[a-f0-9]{64}"))) reject("invalid_upload_hash")
        val matches = inputs.filterValues {
            it.messageId != null && it.kind != Kind.PHOTO && !it.text.isNullOrBlank() &&
                (if (exactText != null) it.text == exactText else it.kind == Kind.VOICE && it.hash == uploadSha256)
        }
        if (matches.size != 1) reject("no_unique_canonical_request")
        val (id, input) = matches.entries.single()
        input.read = true
        return Canonical(deviceId, conversationId, generation, id, input.messageId!!,
            input.kind, input.text!!, input.hash, input.expires)
    }

    fun accept(raw: Envelope, recording: Boolean): Accepted {
        prune()
        if (!foreground) reject("background")
        if (raw.version != 1 || !validId(raw.deliveryId) || !validId(raw.requestId) || !validId(raw.inputMessageId)) reject("invalid_envelope")
        if (raw.deviceId != deviceId || raw.conversationId != conversationId || raw.generation != generation) reject("wrong_destination")
        if (raw.text == null && raw.options == null) reject("empty_content")
        if (raw.text != null && (raw.text.isBlank() || raw.text.toByteArray().size > 16_000)) reject("invalid_text")
        if (raw.options != null && ReplyOptionsPolicy.checkLabels(raw.options) !is ReplyOptionsPolicy.Parse.Ok) reject("invalid_options")
        val envelope = raw.copy(options = raw.options?.toList())
        receipts[raw.deliveryId]?.let {
            if (it.envelope != envelope) reject("delivery_conflict")
            return Accepted(true, false, raw.requestId, null, null, 0)
        }
        val input = inputs[raw.requestId] ?: reject("unknown_request")
        if (input.messageId != raw.inputMessageId || !input.read) reject("canonical_read_required")
        if (recording) reject("recording_retry_same_delivery")
        if (receipts.size >= 100) reject("receipt_capacity_clear_required")
        inputs.remove(raw.requestId)
        val speak = input.kind == Kind.VOICE && input.speechAllowed && raw.text != null
        receipts[raw.deliveryId] = Seen(envelope, Receipt("accepted", if (speak) "eligible" else "not_authorized"))
        return Accepted(false, speak, raw.requestId, raw.text, raw.options?.toList(), input.expires)
    }

    /** Native and addressed final replies compete for the same acknowledged local request. */
    fun acceptNative(nativeId: String?, final: Boolean): NativeGrant? {
        prune()
        if (!foreground || nativeId == null) return null
        val matches = inputs.filterValues { it.messageId == nativeId }
        if (matches.size != 1) return null
        val input = matches.values.single()
        if (final) inputs.remove(matches.keys.single())
        return NativeGrant(input.kind == Kind.VOICE && input.speechAllowed)
    }

    fun receipt(id: String): Receipt = receipts[id]?.receipt ?: reject("unknown_delivery")
    fun rendered(id: String) { receipts[id]?.let { it.receipt = it.receipt.copy(state = "rendered") } }
    fun speech(id: String, state: String) {
        require(state in setOf("queued", "started", "completed", "cancelled", "unavailable", "failed"))
        receipts[id]?.let { it.receipt = it.receipt.copy(speech = state) }
    }
    fun stopAudio() {
        inputs.values.forEach { it.speechAllowed = false }
        receipts.values.filter { it.receipt.speech in setOf("eligible", "queued", "started") }
            .forEach { it.receipt = it.receipt.copy(speech = "cancelled") }
    }
    fun setForeground(value: Boolean) { foreground = value }
    fun revoke(isForeground: Boolean) {
        generation = nonce(); inputs.clear(); used.clear(); nativeOwners.clear(); receipts.clear(); foreground = isForeground
    }
    private fun prune() { inputs.entries.removeAll { now() >= it.value.expires } }
    private fun validId(s: String): Boolean = s.length in 1..128 && s.all { it.isLetterOrDigit() || it in "-_.:" }
    private fun reject(code: String): Nothing = throw Rejected(code)
    companion object {
        fun uploadHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
