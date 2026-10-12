package com.muse.gadget.session

/** Shared subscription data is not a device inbox. Only explicit server parent
 * links to locally acknowledged requests can authorize display or speech.
 * This is origin isolation, not content moderation or a separate Muse session.
 */
class PortalReplyRouter(now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    data class Delivery(val text: String, val display: Boolean, val speak: Boolean)
    private val display = VoiceReplyGate(now)
    private val speech = VoiceReplyGate(now)

    fun acknowledge(messageId: String, voice: Boolean): List<Delivery> {
        val visible = display.acknowledge(messageId)
        val audible = if (voice) speech.acknowledge(messageId) else emptyList()
        return (visible + audible).distinctBy { it.id }.map { event ->
            Delivery(event.fullText, visible.any { it.id == event.id }, audible.any { it.id == event.id })
        }
    }

    fun receive(event: SubscribeParser.Event.MessageDone): Delivery =
        Delivery(event.fullText, display.receive(event), speech.receive(event))

    fun reset() { display.reset(); speech.reset() }
}
