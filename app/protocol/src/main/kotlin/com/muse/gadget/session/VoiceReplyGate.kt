package com.muse.gadget.session

/** Only explicitly parented replies to locally submitted voice notes may speak.
 * Shared subscription traffic never grants permission by timing or arrival order.
 * All calls are serialized on the connection executor; state is memory-only.
 */
class VoiceReplyGate(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val voiceIds = LinkedHashMap<String, Long>()
    private val pending = LinkedHashMap<String, Pair<SubscribeParser.Event.MessageDone, Long>>()
    private val spoken = LinkedHashSet<String>()
    private val lifetimeMs = 5 * 60 * 1000L

    fun acknowledge(messageId: String, parentId: String? = null): List<SubscribeParser.Event.MessageDone> {
        prune()
        listOfNotNull(messageId.takeIf { it.isNotBlank() }, parentId?.takeIf { it.isNotBlank() })
            .forEach { voiceIds[it] = now() }
        while (voiceIds.size > 100) voiceIds.remove(voiceIds.keys.first())
        return pending.values.map { it.first }.filter { eligible(it) }.onEach { markSpoken(it.id) }
    }

    fun receive(event: SubscribeParser.Event.MessageDone): Boolean {
        prune()
        if (event.id in spoken || event.fullText.isBlank()) return false
        if (eligible(event)) { markSpoken(event.id); return true }
        // Supports reply-before-ACK and later persisted parent metadata, bounded in memory.
        pending[event.id] = event to now()
        while (pending.size > 50) pending.remove(pending.keys.first())
        return false
    }

    fun reset() { voiceIds.clear(); pending.clear(); spoken.clear() }

    private fun eligible(event: SubscribeParser.Event.MessageDone): Boolean =
        event.id !in spoken && event.fullText.isNotBlank() && event.replyTo != null && event.replyTo in voiceIds

    private fun markSpoken(id: String) {
        pending.remove(id)
        spoken.add(id)
        while (spoken.size > 500) spoken.remove(spoken.first())
    }

    private fun prune() {
        val cutoff = now() - lifetimeMs
        voiceIds.entries.removeAll { it.value <= cutoff }
        pending.entries.removeAll { it.value.second <= cutoff }
    }
}
