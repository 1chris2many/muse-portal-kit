package com.muse.gadget

import com.muse.gadget.session.SubscribeParser
import com.muse.gadget.session.SubscribeParser.Event.MessageDone
import com.muse.gadget.session.VoiceReplyGate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VoiceReplyGateTest {
    @Test fun `live server empty reply link and self parent fail closed`() {
        val parser = SubscribeParser()
        val gate = VoiceReplyGate()
        gate.acknowledge("voice-input")
        parser.feedLine("""{"type":"event","seq":100,"event":"delta.message_start","payload":{"message_id":"assistant-msg-unrelated","reply_to_message_id":""}}""")
        parser.feedLine("""{"type":"event","seq":101,"event":"delta.text_append","payload":{"message_id":"assistant-msg-unrelated","parent_message_id":"assistant-msg-unrelated","reply_to_message_id":"","text":"Reply"}}""")
        val done = parser.feedLine("""{"type":"event","seq":102,"event":"delta.message_done","payload":{"message_id":"assistant-msg-unrelated","reply_to_message_id":""}}""").last() as MessageDone
        assertNull(done.replyTo)
        assertFalse(gate.receive(done))
    }

    @Test fun `mobile home mini typed and photo replies remain silent during portal voice turn`() {
        val gate = VoiceReplyGate()
        gate.acknowledge("portal-voice")
        for (parent in listOf("mobile", "home-mini", "portal-typed", "portal-photo", null)) {
            assertFalse(gate.receive(MessageDone("reply-$parent", "Unrelated reply", parent)))
        }
        assertTrue(gate.receive(MessageDone("voice-reply", "Voice reply", "portal-voice")))
    }

    @Test fun `no locally acknowledged voice means no speech`() {
        val gate = VoiceReplyGate()
        assertFalse(gate.receive(MessageDone("reply", "Hello", "any-parent")))
    }

    @Test fun `ack aliases support server voice note representations`() {
        val gate = VoiceReplyGate()
        gate.acknowledge("note", "audio-parent")
        assertTrue(gate.receive(MessageDone("reply", "Hello", "audio-parent")))
        assertFalse(gate.receive(MessageDone("note", "ID alone is not evidence")))
    }

    @Test fun `late ack releases only explicitly matching buffered reply once`() {
        val gate = VoiceReplyGate()
        val reply = MessageDone("reply", "Hello", "voice")
        assertFalse(gate.receive(reply))
        assertFalse(gate.receive(MessageDone("other", "Mobile reply", "mobile")))
        assertEquals(listOf(reply), gate.acknowledge("voice"))
        assertEquals(emptyList<MessageDone>(), gate.acknowledge("voice"))
        assertFalse(gate.receive(reply))
    }

    @Test fun `late parent metadata allows speech without repeating display`() {
        val gate = VoiceReplyGate()
        gate.acknowledge("voice")
        assertFalse(gate.receive(MessageDone("reply", "Hello")))
        assertTrue(gate.receive(MessageDone("reply", "Hello", "voice")))
        assertFalse(gate.receive(MessageDone("reply", "Hello", "voice")))
    }

    @Test fun `expired and reset voice turns never authorize playback`() {
        var time = 0L
        val gate = VoiceReplyGate { time }
        gate.acknowledge("old")
        time = 300_000
        assertFalse(gate.receive(MessageDone("reply", "Hello", "old")))
        gate.acknowledge("new")
        gate.reset()
        assertFalse(gate.receive(MessageDone("reply-new", "Hello", "new")))
    }

    @Test fun `blank identifiers or text cannot authorize speech`() {
        val gate = VoiceReplyGate()
        gate.acknowledge("", "")
        assertFalse(gate.receive(MessageDone("reply", "Hello", "")))
        gate.acknowledge("voice")
        assertFalse(gate.receive(MessageDone("blank", " ", "voice")))
    }

    @Test fun `parser preserves delta parent and accepts separately sequenced persisted metadata`() {
        val parser = SubscribeParser()
        parser.feedLine("""{"type":"event","seq":100,"event":"delta.message_start","payload":{"message_id":"reply","parent_message_id":"voice"}}""")
        val done = parser.feedLine("""{"type":"event","seq":101,"event":"delta.message_done","payload":{"message_id":"reply","display_text":"Hello"}}""").last()
        assertEquals(MessageDone("reply", "Hello", "voice"), done)
        val persisted = """{"type":"event","seq":2,"event":"message.assistant","payload":{"message_id":"reply","reply_to_message_id":"voice","display_text":"Hello"}}"""
        assertEquals(done, parser.feedLine(persisted).last())
        assertTrue(parser.feedLine(persisted).isEmpty())
    }

    @Test fun `deferred message carries parent and preferred reply field wins`() {
        val parser = SubscribeParser()
        parser.feedLine("""{"type":"event","seq":1,"event":"delta.message_done","payload":{"message_id":"reply","reply_to_message_id":"voice","parent_message_id":"wrong","display_text":"Hello","display_text_ready":false}}""")
        assertEquals(listOf(MessageDone("reply", "Hello", "voice")), parser.settleDeferred())
    }
}
