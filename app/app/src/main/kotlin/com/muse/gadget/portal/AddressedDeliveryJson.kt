package com.muse.gadget.portal

import com.muse.gadget.session.AddressedReplies
import org.json.JSONArray
import org.json.JSONObject

/** Strict bounded JSON boundary; no URL, remote speak flag, widget code, or caller-selected identity. */
object AddressedDeliveryJson {
    private fun reject(): Nothing = throw AddressedReplies.Rejected("invalid_payload")
    private fun fields(obj: JSONObject) = obj.keys().asSequence().toSet()
    private fun string(obj: JSONObject, key: String): String = obj.opt(key) as? String ?: reject()
    fun selector(args: JSONObject): Pair<String?, String?> = when (fields(args)) {
        setOf("exact_text") -> string(args, "exact_text") to null
        setOf("upload_sha256") -> null to string(args, "upload_sha256")
        else -> reject()
    }
    fun deliveryId(args: JSONObject): String {
        if (fields(args) != setOf("delivery_id")) reject()
        return string(args, "delivery_id")
    }
    fun canonical(c: AddressedReplies.Canonical): JSONObject = JSONObject()
        .put("version", 1).put("device_id", c.deviceId).put("conversation_id", c.conversationId)
        .put("generation", c.generation).put("request_id", c.requestId).put("input_message_id", c.inputMessageId)
        .put("canonical_input", JSONObject().put("kind", c.kind.name.lowercase()).put("text", c.text)
            .put("upload_sha256", c.uploadSha256 ?: JSONObject.NULL))
        .put("expires_in_ms", maxOf(0, c.expiresAtMs - System.nanoTime() / 1_000_000))
    fun receipt(r: AddressedReplies.Receipt) = JSONObject().put("state", r.state).put("speech", r.speech)
    fun envelope(args: JSONObject): AddressedReplies.Envelope {
        if (fields(args) != setOf("version", "delivery_id", "device_id", "conversation_id", "generation",
                "request_id", "input_message_id", "content") || args.opt("version") != 1) reject()
        val content = args.opt("content") as? JSONArray ?: reject()
        if (content.length() !in 1..2) reject()
        var text: String? = null
        var options: List<String>? = null
        for (i in 0 until content.length()) {
            val part = content.opt(i) as? JSONObject ?: reject()
            when (part.opt("type")) {
                "text" -> {
                    if (text != null || fields(part) != setOf("type", "text")) reject()
                    text = string(part, "text")
                }
                "options" -> {
                    if (options != null || fields(part) != setOf("type", "options")) reject()
                    val labels = part.opt("options") as? JSONArray ?: reject()
                    if (labels.length() !in 1..6) reject()
                    options = (0 until labels.length()).map { labels.opt(it) as? String ?: reject() }
                }
                else -> throw AddressedReplies.Rejected("unsupported_content_type")
            }
        }
        return AddressedReplies.Envelope(1, string(args,"delivery_id"), string(args,"device_id"),
            string(args,"conversation_id"), string(args,"generation"), string(args,"request_id"),
            string(args,"input_message_id"), text, options)
    }
}
