package com.muse.gadget.portal

import com.muse.gadget.session.AddressedReplies
import com.muse.gadget.session.InlineMedia
import com.muse.gadget.display.DisplayImagePolicy
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject

/** Strict bounded inline exports; no URL, remote speak flag, widget code or caller-selected identity. */
object AddressedDeliveryJson {
    data class Prepared(val envelope: AddressedReplies.Envelope, val image: Bitmap?, val audio: ByteArray?) {
        fun discard() { image?.recycle(); audio?.fill(0) }
    }
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
    fun envelope(args: JSONObject): AddressedReplies.Envelope = prepare(args).let { it.discard(); it.envelope }
    /** Call off the UI thread; rejected decoding never modifies the prior valid screen. */
    fun prepare(args: JSONObject): Prepared {
        if (fields(args) != setOf("version", "delivery_id", "device_id", "conversation_id", "generation",
                "request_id", "input_message_id", "content") || args.opt("version") != 1) reject()
        val content = args.opt("content") as? JSONArray ?: reject()
        if (content.length() !in 1..4) reject()
        var text: String? = null
        var options: List<String>? = null
        var image: Bitmap? = null
        var audio: ByteArray? = null
        var imageId: InlineMedia.Identity? = null
        var audioId: InlineMedia.Identity? = null
        try {
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
                "image", "audio" -> {
                    if (fields(part) != setOf("type", "mime_type", "data_base64")) reject()
                    val isAudio = part.opt("type") == "audio"
                    if ((isAudio && audioId != null) || (!isAudio && imageId != null)) reject()
                    val mime = string(part, "mime_type")
                    val bytes = InlineMedia.decode(string(part, "data_base64"))
                    val identity = InlineMedia.identity(mime, bytes)
                    if (!InlineMedia.valid(identity, isAudio)) { bytes.fill(0); reject() }
                    if (isAudio) {
                        if (!InlineMedia.looksLikeMp3(bytes)) { bytes.fill(0); throw AddressedReplies.Rejected("invalid_mp3") }
                        audio = bytes; audioId = identity
                    } else {
                        try {
                            val format = DisplayImagePolicy.sniff(bytes)
                            if (format == null || mime != "image/$format") throw AddressedReplies.Rejected("image_mime_mismatch")
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            val sample = DisplayImagePolicy.sampleSize(bounds.outWidth, bounds.outHeight)
                            if (sample == 0) throw AddressedReplies.Rejected("invalid_image_dimensions")
                            image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                                BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 })
                                ?: throw AddressedReplies.Rejected("image_decode_failed")
                            imageId = identity
                        } finally { bytes.fill(0) }
                    }
                }
                else -> throw AddressedReplies.Rejected("unsupported_content_type")
            }
        }
        val envelope = AddressedReplies.Envelope(1, string(args,"delivery_id"), string(args,"device_id"),
            string(args,"conversation_id"), string(args,"generation"), string(args,"request_id"),
            string(args,"input_message_id"), text, options, imageId, audioId)
        return Prepared(envelope, image, audio)
        } catch (e: Exception) { image?.recycle(); audio?.fill(0); throw e }
    }
}
