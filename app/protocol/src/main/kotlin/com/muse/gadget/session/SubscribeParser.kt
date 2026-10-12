package com.muse.gadget.session

import com.muse.gadget.util.Json
import com.muse.gadget.util.JsonValue
import com.muse.gadget.util.boolField
import com.muse.gadget.util.field
import com.muse.gadget.util.strField

/**
 * NDJSON parser for `POST /chat/subscribe` events (spec §3.3).
 *
 * Rules (mirroring `muse_chat_session.cpp` `on_event`):
 * - lines without `type == "event"` are ignored (e.g. the `ack` line);
 * - live deltas require increasing `seq`; persisted rows use message dedup;
 * - `delta.message_start` opens a slot, `delta.text_append` appends,
 *   `delta.message_done` closes it (full text wins over the accumulation);
 * - `display_text_ready == false` defers the done event until a later
 *   `message_done` (or an explicit [settleDeferred]);
 * - `message.assistant` delivers a whole message at once;
 * - `delta.presentation` (undocumented upstream; shape per SDK issue 186) is
 *   reported by kind only, since a gadget cannot fetch its files;
 * - `agent.status` / `task.status` drive the busy indicator.
 */
class SubscribeParser(private val diagnostic:(String)->Unit = {}) {
    sealed interface Event {
        data class MessageStart(val id: String) : Event
        data class TextAppend(val id: String, val text: String) : Event
        data class MessageDone(val id: String, val fullText: String, val replyTo: String? = null) : Event
        data class UserTranscript(val id: String, val text: String) : Event
        /** Rich content Muse attached to a reply: kinds among image, audio, video, html. */
        data class Presentation(val id: String, val kinds: List<String>, val replyTo: String? = null) : Event
        data class Busy(val busy: Boolean) : Event
    }

    private data class Slot(
        val id: String,
        val text: StringBuilder = StringBuilder(),
        var pendingDone: String? = null, // display_text_ready=false full text, awaiting confirm
        var replyTo: String? = null,
        var conflictingParent: Boolean = false,
    )

    private var lastSeq: Long = -1
    private val userTexts = LinkedHashMap<String,String>()
    private val assistantRows = LinkedHashMap<String, Event.MessageDone>()
    private val slots = LinkedHashMap<String, Slot>()
    private var busy: Boolean = false

    /** Feed one NDJSON line; returns the events it produced. */
    fun feedLine(line: String): List<Event> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return emptyList()
        val root = try {
            Json.parseObj(trimmed)
        } catch (e: Exception) {
            return emptyList() // malformed line: skip like feed_lines does
        }
        val isUser = root.strField("event") == "message.user"
        if (root.strField("type") != "event") {if(isUser)diagnostic("User row ignored: not event type");return emptyList()}
        val seq = (root.field("seq") as? JsonValue.Num)?.raw?.toLongOrNull() ?: return emptyList()
        // Persisted user rows have a separate sequence domain from live deltas.
        // Deduplicate these by message ID + text, not the live stream watermark.
        if (!isUser && root.strField("event") != "message.assistant") {
            if (seq <= lastSeq) return emptyList()
            lastSeq = seq
        }
        val name = root.strField("event") ?: return emptyList()
        val payload = root.field("payload") as? JsonValue.Obj ?: return emptyList()
        return when (name) {
            "delta.message_start" -> onMessageStart(payload)
            "delta.text_append" -> onTextAppend(payload)
            "delta.message_done" -> onMessageDone(payload)
            "message.assistant" -> onWholeMessage(payload)
            "delta.presentation" -> onPresentation(payload)
            "message.user" -> {
                val id = payload.strField("message_id") ?: payload.strField("id")
                val text = (payload.strField("display_text") ?: payload.strField("content") ?: payload.strField("text") ?: "")
                    .substringBefore("\n[file:").trim()
                if (id.isNullOrBlank() || text.isBlank() || text == "[Voice note]" || text.startsWith("[file:")) {
                    diagnostic("User row has no displayable transcript: missingId=${id.isNullOrBlank()}, blank=${text.isBlank()}, placeholder=${text=="[Voice note]"}, fileOnly=${text.startsWith("[file:")}")
                    emptyList()
                }
                else if (userTexts[id] == text) emptyList()
                else {
                    userTexts[id]=text
                    if(userTexts.size>100)userTexts.remove(userTexts.keys.first())
                    listOf(Event.UserTranscript(id, text))
                }
            }
            "agent.status" -> onAgentStatus(payload)
            "task.status" -> onTaskStatus(payload)
            else -> emptyList()
        }
    }

    /**
     * Emits deferred `message_done` events when a turn settles without a
     * confirming done (spec: a turn settles after a few seconds of silence).
     */
    fun settleDeferred(): List<Event> {
        val out = ArrayList<Event>()
        for (slot in slots.values) {
            val pending = slot.pendingDone
            if (pending != null) {
                slot.pendingDone = null
                out.add(Event.MessageDone(slot.id, pending, slot.replyTo))
            }
        }
        return out
    }

    fun reset() {
        lastSeq = -1
        slots.clear()
        userTexts.clear()
        assistantRows.clear()
        busy = false
    }

    private fun slotFor(payload: JsonValue.Obj): Slot {
        val id = payload.strField("message_id") ?: payload.strField("id") ?: "msg"
        val slot = slots.getOrPut(id) { Slot(id) }
        val parent = payload.strField("reply_to_message_id")?.takeIf { it.isNotBlank() }
            ?: payload.strField("parent_message_id")?.takeIf { it.isNotBlank() }
        if (parent != null && parent != id && !slot.conflictingParent) {
            if (slot.replyTo != null && slot.replyTo != parent) {
                slot.replyTo = null
                slot.conflictingParent = true
            } else slot.replyTo = parent
        }
        while (slots.size > 500) slots.remove(slots.keys.first())
        return slot
    }

    private fun onMessageStart(payload: JsonValue.Obj): List<Event> {
        val slot = slotFor(payload)
        return listOf(Event.MessageStart(slot.id))
    }

    private fun onTextAppend(payload: JsonValue.Obj): List<Event> {
        val slot = slotFor(payload)
        val text = payload.strField("text") ?: ""
        slot.text.append(text)
        return listOf(Event.TextAppend(slot.id, text))
    }

    private fun onMessageDone(payload: JsonValue.Obj): List<Event> {
        val slot = slotFor(payload)
        val full = payload.strField("display_text") ?: payload.strField("content") ?: slot.text.toString()
        val ready = payload.boolField("display_text_ready")
        if (ready == false) {
            // Not final yet: stash; a later message_done (or turn settle) completes it.
            slot.pendingDone = full
            return emptyList()
        }
        slot.pendingDone = null
        return listOf(Event.MessageDone(slot.id, full, slot.replyTo))
    }

    private fun onWholeMessage(payload: JsonValue.Obj): List<Event> {
        val slot = slotFor(payload)
        val full = payload.strField("display_text") ?: payload.strField("content") ?: ""
        slot.text.clear()
        slot.text.append(full)
        slot.pendingDone = null
        val done = Event.MessageDone(slot.id, full, slot.replyTo)
        if (assistantRows[slot.id] == done) return emptyList()
        assistantRows[slot.id] = done
        while (assistantRows.size > 500) assistantRows.remove(assistantRows.keys.first())
        return listOf(Event.MessageStart(slot.id), done)
    }

    private fun onPresentation(payload: JsonValue.Obj): List<Event> {
        val slot = slotFor(payload)
        val data = payload.field("data") as? JsonValue.Obj
        fun present(key: String): Boolean = when (val v = (data ?: payload).field(key)) {
            null, JsonValue.Null -> false
            is JsonValue.Arr -> v.items.isNotEmpty()
            is JsonValue.Str -> v.value.isNotBlank()
            else -> true
        }
        val kinds = ArrayList<String>()
        if (present("images") || present("image")) kinds += "image"
        if (present("audio")) kinds += "audio"
        if (present("videos") || present("video")) kinds += "video"
        if (present("html") || present("form")) kinds += "html"
        for (key in listOf("kind", "type", "presentation_type")) {
            val named = payload.strField(key)?.lowercase()?.takeIf { it.isNotBlank() } ?: continue
            if (named !in kinds && named != "presentation") kinds += named
        }
        diagnostic("Presentation event: kinds=$kinds; parentPresent=${slot.replyTo != null}")
        return listOf(Event.Presentation(slot.id, kinds, slot.replyTo))
    }

    private fun setBusy(next: Boolean): List<Event> {
        if (next == busy) return emptyList()
        busy = next
        return listOf(Event.Busy(next))
    }

    private fun onAgentStatus(payload: JsonValue.Obj): List<Event> {
        val code = payload.strField("activity_code") ?: return emptyList()
        return setBusy(code != "online" && code != "idle")
    }

    private fun onTaskStatus(payload: JsonValue.Obj): List<Event> {
        val status = payload.strField("status") ?: return emptyList()
        return setBusy(status != "completed" && status != "failed")
    }
}
