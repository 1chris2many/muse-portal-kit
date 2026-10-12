package com.muse.gadget.session

import kotlin.test.*
import org.junit.jupiter.api.Test

class PortalReplyRouterTest {
    private fun event(id:String="reply", parent:String?=null)=SubscribeParser.Event.MessageDone(id,"fixture",parent)
    @Test fun unrelatedAndUnlinkedSharedHistoryNeverDisplaysOrSpeaks() {
        val router=PortalReplyRouter()
        router.acknowledge("local",true)
        listOf(event("history"),event("mobile","other-device"),event("self","self")).forEach {
            val result=router.receive(it);assertFalse(result.display);assertFalse(result.speak)
        }
    }
    @Test fun typedAndPhotoRepliesDisplayWithoutSpeech() {
        val router=PortalReplyRouter()
        for(id in listOf("typed","photo")) {
            router.acknowledge(id,false)
            val result=router.receive(event("reply-$id",id))
            assertTrue(result.display);assertFalse(result.speak)
        }
    }
    @Test fun voiceReplyDisplaysAndSpeaksOnce() {
        val router=PortalReplyRouter();router.acknowledge("voice",true)
        val event=event(parent="voice")
        val first=router.receive(event);assertTrue(first.display);assertTrue(first.speak)
        val duplicate=router.receive(event);assertFalse(duplicate.display);assertFalse(duplicate.speak)
    }
    @Test fun lateAckReleasesOnlyExplicitlyLinkedReply() {
        val router=PortalReplyRouter()
        assertFalse(router.receive(event(parent="voice")).display)
        assertFalse(router.receive(event("unrelated","mobile")).display)
        val released=router.acknowledge("voice",true)
        assertEquals(1,released.size);assertTrue(released.single().display);assertTrue(released.single().speak)
    }
    @Test fun reconnectAndExpiredRequestsDoNotAuthorizeHistory() {
        var now=0L;val router=PortalReplyRouter{now}
        router.acknowledge("old",true);router.reset()
        assertFalse(router.receive(event(parent="old")).display)
        router.acknowledge("expired",true);now=300001
        assertFalse(router.receive(event("later","expired")).display)
    }
    @Test fun conflictingParentsFailClosedEvenIfLastOneMatchesDevice() {
        val parser=SubscribeParser();val router=PortalReplyRouter()
        router.acknowledge("local",true)
        parser.feedLine("""{"type":"event","seq":1,"event":"delta.message_start","payload":{"message_id":"reply","reply_to_message_id":"mobile"}}""")
        parser.feedLine("""{"type":"event","seq":2,"event":"delta.text_append","payload":{"message_id":"reply","reply_to_message_id":"local","text":"fixture"}}""")
        val done=parser.feedLine("""{"type":"event","seq":3,"event":"delta.message_done","payload":{"message_id":"reply","reply_to_message_id":"local"}}""").last() as SubscribeParser.Event.MessageDone
        assertNull(done.replyTo);assertFalse(router.receive(done).display)
    }
    @Test fun presentationIsAnnouncedOnlyWhenLinkedAndNeverSpoken() {
        val router=PortalReplyRouter();router.acknowledge("voice",true)
        val linked=router.receivePresentation(SubscribeParser.Event.Presentation("m1",listOf("image"),"voice"))
        assertTrue(linked.display);assertFalse(linked.speak)
        assertEquals("Muse sent an image with this reply. Open the Muse app to view this attachment.",linked.text)
        val again=router.receivePresentation(SubscribeParser.Event.Presentation("m1",listOf("image"),"voice"))
        assertFalse(again.display)
        for(event in listOf(SubscribeParser.Event.Presentation("h1",listOf("video")),SubscribeParser.Event.Presentation("h2",listOf("html"),"other-device"))) {
            val result=router.receivePresentation(event);assertFalse(result.display);assertFalse(result.speak)
        }
        val text=router.receive(SubscribeParser.Event.MessageDone("m1","The chart text","voice"))
        assertTrue(text.display);assertTrue(text.speak)
    }
    @Test fun lateAckReleasesPresentationNoteWithoutSpeech() {
        val router=PortalReplyRouter()
        assertFalse(router.receivePresentation(SubscribeParser.Event.Presentation("m2",listOf("audio","html"),"typed")).display)
        val released=router.acknowledge("typed",true)
        assertEquals(1,released.size);assertTrue(released.single().display);assertFalse(released.single().speak)
        assertEquals(PortalReplyRouter.presentationNote(listOf("audio","html")),released.single().text)
        assertEquals("Muse sent rich content with this reply. Open the Muse app to view this attachment.",PortalReplyRouter.presentationNote(emptyList()))
    }
}
