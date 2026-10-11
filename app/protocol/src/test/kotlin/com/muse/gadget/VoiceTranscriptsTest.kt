package com.muse.gadget

import com.muse.gadget.session.SubscribeParser
import com.muse.gadget.session.VoiceTranscripts
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class VoiceTranscriptsTest {
    @Test fun `server text retained but file annotation excluded`() {
        val p=SubscribeParser()
        assertEquals(listOf(SubscribeParser.Event.UserTranscript("v1","What did I say?")),p.feedLine(
            """{"type":"event","seq":1,"event":"message.user","payload":{"id":"v1","content":"What did I say?\n[file:audio/wav note]"}}"""))
    }
    @Test fun `placeholder is not a transcript`() {
        val p=SubscribeParser()
        assertTrue(p.feedLine("""{"type":"event","seq":1,"event":"message.user","payload":{"id":"v1","text":"[Voice note]"}}""").isEmpty())
    }
    @Test fun `transcript before ack waits for matching device message`() {
        val t=VoiceTranscripts()
        assertNull(t.receive("other","Private unrelated message"))
        assertNull(t.receive("mine","Hello"))
        assertEquals("local" to "Hello",t.acknowledge("local","mine"))
    }
    @Test fun `ack before transcript and parent alias supported`() {
        val t=VoiceTranscripts()
        assertNull(t.acknowledge("local","ack","parent"))
        assertEquals("local" to "Hello",t.receive("parent","Hello"))
        assertEquals("local" to "Hello again",t.receive("parent","Hello again"))
    }
}
