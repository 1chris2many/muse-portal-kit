package com.muse.gadget.session

import java.util.Base64

/** Inline exports only: no remote URL, sandbox path or shared-history attachment handle. */
object InlineMedia {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_BODY_BYTES = 6 * 1024 * 1024
    const val MAX_AUDIO_MS = 180_000
    data class Identity(val mime: String, val sha256: String, val bytes: Int)
    fun valid(identity: Identity, audio: Boolean): Boolean =
        identity.bytes in 1..MAX_BYTES && identity.sha256.matches(Regex("[a-f0-9]{64}")) &&
            identity.mime in if (audio) setOf("audio/mpeg") else setOf("image/jpeg", "image/png", "image/webp")
    fun decode(base64: String): ByteArray {
        if (base64.isEmpty() || base64.length > ((MAX_BYTES + 2) / 3) * 4 || base64.length % 4 != 0)
            throw AddressedReplies.Rejected("invalid_media_base64")
        val bytes = try { Base64.getDecoder().decode(base64) }
            catch (_: IllegalArgumentException) { throw AddressedReplies.Rejected("invalid_media_base64") }
        if (bytes.size !in 1..MAX_BYTES || Base64.getEncoder().encodeToString(bytes) != base64)
            throw AddressedReplies.Rejected("invalid_media_base64")
        return bytes
    }
    fun identity(mime: String, bytes: ByteArray) = Identity(mime, AddressedReplies.uploadHash(bytes), bytes.size)
    /** Conservative MP3 container signature. Actual Android preparation must still succeed. */
    fun looksLikeMp3(bytes: ByteArray): Boolean = bytes.size >= 4 &&
        ((bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) ||
            (bytes[0] == 0xff.toByte() && bytes[1].toInt() and 0xe0 == 0xe0 &&
                bytes[1].toInt() and 0x06 != 0 && bytes[2].toInt() and 0xf0 !in setOf(0, 0xf0)))
}
