package com.muse.gadget.session

/** Correlate server transcripts with this device's voice ACKs, in either order. */
class VoiceTranscripts {
    private val owners = LinkedHashMap<String,String>()
    private val early = LinkedHashMap<String,String>()
    fun acknowledge(localId: String, messageId: String, parentId: String? = null): Pair<String,String>? {
        val ids = listOfNotNull(messageId.takeIf { it.isNotBlank() }, parentId?.takeIf { it.isNotBlank() })
        ids.forEach { owners[it] = localId }
        while(owners.size > 100) owners.remove(owners.keys.first())
        return ids.firstNotNullOfOrNull { early.remove(it)?.let { text -> localId to text } }
    }
    fun receive(id: String, text: String): Pair<String,String>? {
        owners[id]?.let { return it to text }
        early[id] = text
        while(early.size > 50) early.remove(early.keys.first())
        return null
    }
}
