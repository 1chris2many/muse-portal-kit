package com.muse.gadget.session

/** Shared subscription data is not a device inbox. Only explicit server parent
 * links to locally acknowledged requests can authorize display or speech.
 * This is origin isolation, not content moderation or a separate Muse session.
 */
class PortalReplyRouter(now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    data class Delivery(val text: String, val display: Boolean, val speak: Boolean,
        val inputMessageId: String? = null, val final: Boolean = true)
    private val display = VoiceReplyGate(now)
    private val speech = VoiceReplyGate(now)

    fun acknowledge(messageId: String, voice: Boolean): List<Delivery> {
        val visible = display.acknowledge(messageId)
        val audible = if (voice) speech.acknowledge(messageId) else emptyList()
        return (visible + audible).distinctBy { it.id }.map { event ->
            Delivery(event.fullText, visible.any { it.id == event.id }, audible.any { it.id == event.id }, event.replyTo,
                !event.id.endsWith("#presentation"))
        }
    }

    fun receive(event: SubscribeParser.Event.MessageDone): Delivery =
        Delivery(event.fullText, display.receive(event), speech.receive(event), event.replyTo)

    /** Rich content is announced in words, under the same display gate; it is never spoken
     * and its files are never fetched (a gadget's token cannot read them: SDK issue 186). */
    fun receivePresentation(event: SubscribeParser.Event.Presentation): Delivery {
        val note = presentationNote(event.kinds)
        val announced = SubscribeParser.Event.MessageDone("${event.id}#presentation", note, event.replyTo)
        return Delivery(note, display.receive(announced), false, event.replyTo, false)
    }

    companion object {
        fun presentationNote(kinds: List<String>): String {
            val names = kinds.map {
                when (it) {
                    "image" -> "an image"; "audio" -> "an audio clip"; "video" -> "a video"; "html" -> "a form"
                    else -> "rich content"
                }
            }.distinct()
            val what = if (names.isEmpty()) "rich content" else names.joinToString(" and ")
            return "Muse sent $what with this reply. This Portal shows text only; open the Muse app to see it."
        }
    }

    fun reset() { display.reset(); speech.reset() }
}
