package com.muse.gadget.portal

import android.app.Application
import com.muse.gadget.session.AddressedReplies
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AddressedDeliveryTest {
    private fun envelope() = JSONObject().put("version",1).put("device_id","device")
        .put("conversation_id","existing-main").put("generation","g").put("request_id","r")
        .put("input_message_id","i").put("delivery_id","d")
        .put("content",JSONArray().put(JSONObject().put("type","text").put("text","reply")))
    private fun rejected(block: ()->Unit) {
        try { block(); fail("Must reject") } catch (_: AddressedReplies.Rejected) { }
    }
    @Test fun parsesOnlyVersionedKnownFieldsAndNeverRemoteSpeechFlag() {
        assertEquals("reply",AddressedDeliveryJson.envelope(envelope()).text)
        rejected { AddressedDeliveryJson.envelope(envelope().put("speak",true)) }
        rejected { AddressedDeliveryJson.envelope(envelope().put("version","1")) }
        rejected { AddressedDeliveryJson.envelope(envelope().put("request_id",123)) }
    }
    @Test fun rejectsDuplicateUnsupportedAndScriptContent() {
        val text=JSONObject().put("type","text").put("text","reply")
        rejected { AddressedDeliveryJson.envelope(envelope().put("content",JSONArray().put(text).put(text))) }
        rejected { AddressedDeliveryJson.envelope(envelope().put("content",JSONArray().put(JSONObject().put("type","html").put("html","script")))) }
        rejected { AddressedDeliveryJson.envelope(envelope().put("content",JSONArray().put(text.put("callback","https://example.com")))) }
    }
    @Test fun optionsKeepExactStringsAndNoCallback() {
        val options=JSONObject().put("type","options").put("options",JSONArray().put("Yes").put("No"))
        assertEquals(listOf("Yes","No"),AddressedDeliveryJson.envelope(envelope().put("content",JSONArray().put(options))).options)
        rejected { AddressedDeliveryJson.envelope(envelope().put("content",JSONArray().put(options.put("callback","run")))) }
        rejected { AddressedDeliveryJson.envelope(envelope().put("content",JSONArray().put(JSONObject().put("type","options").put("options",JSONArray().put(1))))) }
    }
    @Test fun readHasNoListOrLatestOrApproximateSelector() {
        assertEquals("same text",AddressedDeliveryJson.selector(JSONObject().put("exact_text","same text")).first)
        for (value in listOf(JSONObject(),JSONObject().put("latest",true),JSONObject().put("exact_text","x").put("upload_sha256","a")))
            rejected { AddressedDeliveryJson.selector(value) }
    }

    private fun withBridge(test: (Int,String,MutableList<String>)->Unit) {
        val context=RuntimeEnvironment.getApplication() as Application
        val invoked=mutableListOf<String>()
        val bridge=LocalPortalBridge(context,{ fail("No camera in delivery fixtures") },{path,args ->
            invoked.add(path);JSONObject().put("ok",true).put("received",args.has("exact_text"))
        })
        try {
            // This is a newly generated test-fixture credential in Robolectric's temp sandbox.
            val config=JSONObject(File(context.noBackupFilesDir,"portal-bridge.json").readText())
            test(config.getInt("port"),config.getString("token"),invoked)
        } finally { bridge.close() }
    }
    private fun post(port:Int,token:String,body:String="{\"exact_text\":\"fixture\"}",extra:String="",length:Int=body.toByteArray().size):String =
        Socket("127.0.0.1",port).use { socket ->
            socket.soTimeout=3000
            socket.getOutputStream().write(("POST /requests/read HTTP/1.1\r\nAuthorization: Bearer $token\r\nContent-Length: $length\r\n$extra\r\n$body").toByteArray())
            socket.getOutputStream().flush()
            // HTTP framing uses Content-Length, not reading past a Connection: close body.
            val input=socket.getInputStream()
            val header=StringBuilder()
            while (!header.endsWith("\r\n\r\n")) { val b=input.read();check(b>=0);header.append(b.toChar()) }
            val size=header.toString().split("\r\n").single { it.startsWith("Content-Length:") }.substringAfter(':').trim().toInt()
            val response=ByteArray(size);var offset=0
            while(offset<size) { val n=input.read(response,offset,size-offset);check(n>0);offset+=n }
            header.toString()+String(response)
        }
    @Test fun localHttpRequiresAuthenticatedBoundedJson() = withBridge {port,token,called ->
        assertTrue(post(port,"wrong").startsWith("HTTP/1.1 403"));assertTrue(called.isEmpty())
        assertTrue(post(port,token).startsWith("HTTP/1.1 200"));assertEquals(listOf("/requests/read"),called)
    }
    @Test fun ambiguousLengthChunkingAndOversizeNeverReachHandler() = withBridge {port,token,called ->
        assertTrue(post(port,token,extra="Content-Length: 5\r\n").startsWith("HTTP/1.1 400"))
        assertTrue(post(port,token,extra="Transfer-Encoding: chunked\r\n").startsWith("HTTP/1.1 400"))
        assertTrue(post(port,token,length=65537).startsWith("HTTP/1.1 400"))
        assertTrue(called.isEmpty())
    }
}
