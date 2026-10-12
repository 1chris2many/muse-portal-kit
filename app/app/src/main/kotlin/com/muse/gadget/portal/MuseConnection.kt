package com.muse.gadget.portal

import com.muse.gadget.api.MuseAccountApi
import com.muse.gadget.api.VmInfo
import com.muse.gadget.display.DisplayCommands
import com.muse.gadget.display.DisplayImagePolicy
import com.muse.gadget.identity.Identity
import com.muse.gadget.noise.*
import com.muse.gadget.session.ChatStreams
import com.muse.gadget.session.SubscribeParser
import com.muse.gadget.session.VoiceTranscripts
import com.muse.gadget.session.PortalReplyRouter
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Direct SDK connection. No system.run, file access, OTA or new model instance. */
class MuseConnection(private val store: DeviceStore, private val status: (String)->Unit,
    private val reply: (String)->Unit, private val readyChanged: (Boolean)->Unit,
    private val transcript: (String,String)->Unit = {_,_->},
    private val photo: (((ByteArray?,String?)->Unit)->Unit)? = null,
    private val voiceReply: (String)->Unit = {},
    /** display.draw_url: shows one policy-checked https image; answers (payload, error). */
    private val image: ((String,(JSONObject?,JSONObject?)->Unit)->Unit)? = null,
    /** display.show_animation: clears images shown by display.draw_url. */
    private val clearImage: (()->Unit)? = null) {
    private val http=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS)
        .readTimeout(30,TimeUnit.SECONDS).pingInterval(20,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val work=Executors.newSingleThreadScheduledExecutor()
    private var socket: WebSocket?=null
    private var transport: NoiseTransport?=null
    private var handshake: NoiseXXInitiator?=null
    private var controlId=0L
    private var subscribeId=0L
    private var registration=""
    private var controlBytes=ByteArray(0)
    private var lines=ByteArray(0)
    private fun newParser()=SubscribeParser{android.util.Log.i("MusePortalState",it)}
    private var parser=newParser()
    private val invocations=LinkedHashSet<String>()
    private val sends=mutableSetOf<Long>()
    private val voiceSends=mutableMapOf<Long,String>()
    private val ackBodies=mutableMapOf<Long,ByteArray>()
    private val transcripts=VoiceTranscripts()
    private val replies=PortalReplyRouter()
    @Volatile private var ready=false
    @Volatile private var closed=false
    private var epoch=0
    private var retries=0

    private fun root(c: JSONObject): String {
        val value=c.optString("api_url_v2").ifBlank {Identity.API_BASE}.trimEnd('/')
        require(value==Identity.API_BASE) {"Unexpected API host; review before transmitting credentials."}
        val host=c.optString("noise_host").ifBlank {Identity.DEFAULT_NOISE_HOST}
        require(host==Identity.DEFAULT_NOISE_HOST) {"Unexpected transport host."}
        return value
    }
    private fun fetch(c: JSONObject): Pair<Int,List<VmInfo>> {
        val req=Request.Builder().url(root(c)+Identity.FETCH_VMS_PATH)
            .header("Authorization","Bearer "+c.getString("access_token"))
            .header("X-API-Version","1.0.0").header("User-Agent","muse-portal/0.3.0").build()
        http.newCall(req).execute().use { r ->
            return r.code to if(r.isSuccessful) MuseAccountApi.parseVmList(r.body?.string()?:"") else emptyList()
        }
    }
    private fun select(vms: List<VmInfo>): VmInfo =
        vms.singleOrNull {it.isDefault} ?: vms.singleOrNull()
        ?: throw IllegalStateException("Cannot identify a single existing/default Muse VM.")
    fun validate(c: JSONObject) { val (code,vms)=fetch(c);check(code==200);select(vms) }
    fun connect() { if(!closed) work.execute { open() } }
    private fun open() {
        if(closed)return
        ready=false;readyChanged(false)
        val mine=++epoch
        socket?.cancel();transport=null;handshake=null;controlBytes=ByteArray(0);lines=ByteArray(0)
        sends.clear();voiceSends.clear();ackBodies.clear();parser=newParser();replies.reset()
        try {
            val c=store.read("pairing") ?: throw IllegalStateException("Pair this device first.")
            var result=fetch(c)
            if(result.first==401) {
                val sdk=store.read("sdk")?.getString("token")
                val body=MuseAccountApi.refreshBody(store.nodeId,sdk)
                val req=Request.Builder().url(root(c)+Identity.REFRESH_PATH)
                    .header("Authorization",MuseAccountApi.refreshAuthHeader(c.getString("refresh_token")))
                    .post(body.toRequestBody("application/json".toMediaType())).build()
                http.newCall(req).execute().use {r ->
                    check(r.isSuccessful) {"Device authorization expired. Pair again."}
                    val pair=MuseAccountApi.parseRefreshResponse(r.body?.string()?:"") ?: error("Invalid refresh response.")
                    c.put("access_token",pair.first).put("refresh_token",pair.second)
                    store.save("pairing",c)
                }
                result=fetch(c)
            }
            check(result.first==200) {"Muse account lookup failed (${result.first})."}
            val vm=select(result.second)
            val url=HttpUrl.Builder().scheme("https").host(Identity.DEFAULT_NOISE_HOST)
                .addPathSegments("v1/noise").addQueryParameter("vm_id",vm.vmId).build()
            status("Connecting securely to existing Muse…")
            socket=http.newWebSocket(Request.Builder().url(url).header("Authorization","Bearer "+vm.authToken).build(),
                object: WebSocketListener() {
                    override fun onOpen(ws: WebSocket,r: Response) {dispatch(mine){
                        handshake=NoiseXXInitiator().apply {initialize()}
                        check(ws.send(handshake!!.writeMessage1().toByteString()))
                    }}
                    override fun onMessage(ws: WebSocket,bytes: ByteString) {dispatch(mine){
                        require(bytes.size<=1048576)
                        val h=handshake
                        if(h!=null) {
                            h.readMessage2(bytes.toByteArray());check(ws.send(h.writeMessage3().toByteString()))
                            val (send,recv)=h.split();transport=NoiseTransport(send,recv);handshake=null
                            register()
                        } else transport!!.decryptFrame(bytes.toByteArray())?.let(::receive)
                    }}
                    override fun onFailure(ws: WebSocket,t: Throwable,response: Response?) {dispatch(mine){
                        fail("Connection interrupted"+(response?.code?.let {" (HTTP $it)"}?:""))
                    }}
                    override fun onClosed(ws: WebSocket,code: Int,reason: String) {dispatch(mine){fail("Connection closed ($code)")}}
                })
        } catch(e: Exception) {status(e.message?.take(150)?:"Unable to connect.");readyChanged(false)}
    }
    private fun dispatch(mine: Int, f: ()->Unit) {
        if(closed)return
        work.execute {if(!closed&&mine==epoch) try{f()}catch(_:Exception){fail("Protocol error; disconnected safely")}}
    }
    private fun send(frames: List<ByteArray>) {frames.forEach {check(socket?.send(it.toByteString())==true)}}
    private fun register() {
        val t=transport!!
        val request=t.startStreamRequest("POST","/link-control")
        controlId=request.streamId;send(request.frames)
        registration=UUID.randomUUID().toString()
        val params=JSONObject().put("node_id",store.nodeId).put("display_name","Muse Portal")
            .put("platform","android").put("model_id","portal").put("version","0.3.0")
            .put("device_family","homehub").put("is_wakeup_supported",false).put("commands_v2",JSONObject().apply {
                if(photo!=null)put("camera.capture",JSONObject()
                    .put("description","Take one RGB still photo on this Portal now. Owner has enabled capture without confirmation. Requires app foreground and camera permission; physical shutter applies. Returns JPEG data_base64 (max 128 KiB). No video or saved files.")
                    .put("required",JSONObject()).put("optional",JSONObject()).put("timeout_ms",15000))
                if(image!=null){
                    put(DisplayCommands.DRAW_URL,JSONObject().put("description",DisplayCommands.DRAW_URL_DESCRIPTION)
                        .put("required",JSONObject().put("url",JSONObject().put("type","string").put("description",DisplayCommands.DRAW_URL_PARAM)))
                        .put("optional",JSONObject()).put("timeout_ms",DisplayCommands.DRAW_URL_TIMEOUT_MS))
                    put(DisplayCommands.CLEAR,JSONObject().put("description",DisplayCommands.CLEAR_DESCRIPTION)
                        .put("required",JSONObject()).put("optional",JSONObject()))
                }
            })
        control(JSONObject().put("type","req").put("id",registration).put("method","link.register").put("params",params))
    }
    private fun control(obj: JSONObject) {
        val b=obj.toString().toByteArray()
        val size=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(b.size).array()
        val framed=size+b
        for(offset in framed.indices step 6144)send(transport!!.encryptBodyChunk(controlId,framed.copyOfRange(offset,minOf(offset+6144,framed.size))))
    }
    private fun receive(f: DecryptedFrame) {
        val data: ByteArray
        val end: Boolean
        when(val v=f.value) {
            is ResponseValue -> {
                if(v.r.status !in 200..299) {fail("Muse rejected request (${v.r.status})");return}
                data=v.r.body;end=v.r.endBody
                if(f.streamId==subscribeId) {ready=true;retries=0;readyChanged(true);status("Connected to Muse. Hold Talk to speak.")}
                if(f.streamId in sends) status("Message accepted; waiting for Muse…")
            }
            is BodyChunkValue -> {data=v.c.data;end=v.c.endBody}
            else -> {fail("Muse reset a stream");return}
        }
        if(f.streamId in sends) {
            val body=(ackBodies[f.streamId]?:ByteArray(0))+data
            require(body.size<=65536)
            ackBodies[f.streamId]=body
            if(end) {
                val root=JSONObject(String(body))
                val ack=root.optJSONObject("result")?:root
                val messageId=(ack.opt("message_id") as? String).orEmpty()
                val parentId=ack.opt("reply_to_message_id") as? String
                android.util.Log.i("MusePortalState","Voice ACK: message ID present=${messageId.isNotBlank()}, parent ID present=${!parentId.isNullOrBlank()}")
                val localId=voiceSends.remove(f.streamId)
                if(localId!=null)transcripts.acknowledge(localId,messageId,parentId)
                    ?.let {transcript(it.first,it.second)}
                replies.acknowledge(messageId,voice=localId!=null).forEach(::deliverReply)
                ackBodies.remove(f.streamId)
            }
        }
        when(f.streamId) {
            controlId -> {
                controlBytes+=data;check(controlBytes.size<=1048576)
                while(controlBytes.size>=4) {
                    val n=ByteBuffer.wrap(controlBytes,0,4).order(ByteOrder.LITTLE_ENDIAN).int
                    require(n in 1..262144)
                    if(controlBytes.size<n+4)break
                    val obj=JSONObject(String(controlBytes.copyOfRange(4,n+4)))
                    controlBytes=controlBytes.copyOfRange(n+4,controlBytes.size)
                    if(obj.optString("id")==registration && !obj.has("method")) {
                        check(!obj.has("error"))
                        android.util.Log.i("MusePortalState","Muse accepted device registration; camera advertised=${photo!=null}; display advertised=${image!=null}")
                        val req=transport!!.encryptHttpRequest("POST",Identity.CHAT_SUBSCRIBE_PATH,ChatStreams.subscribeBody(),headers=ChatStreams.subscribeHeaders())
                        subscribeId=req.streamId;send(req.frames)
                    }
                    if(obj.optString("event") in listOf("link.unpaired","node.unpaired")) {closed=true;readyChanged(false);socket?.cancel();status("Device unpaired in Muse.");return}
                    if(obj.optString("method")=="link.invoke") {
                        val command=obj.optString("command")
                        android.util.Log.i("MusePortalState","Device command received; camera=${command=="camera.capture"}; display=${command.startsWith("display.")}")
                        val id=obj.optString("id")
                        if(id.isNotBlank() && !invocations.add(id)) {
                            control(JSONObject().put("method","link.result").put("id",id).put("ok",false)
                                .put("error","This request was already processed; send a new one with a new ID."))
                            continue
                        }
                        if(invocations.size>100)invocations.remove(invocations.first())
                        if(id.isNotBlank() && command==DisplayCommands.DRAW_URL && image!=null) {
                            // The URL is policy-checked here and never logged: it may carry a private token.
                            when(val check=DisplayImagePolicy.checkUrl(obj.optJSONObject("params")?.optString("url"))) {
                                is DisplayImagePolicy.Check.Rejected -> control(JSONObject().put("method","link.result").put("id",id).put("ok",false)
                                    .put("error",JSONObject().put("code",check.code).put("message",check.message)))
                                is DisplayImagePolicy.Check.Ok -> {
                                    val mine=epoch
                                    image.invoke(check.url){payload,error ->dispatch(mine){
                                        android.util.Log.i("MusePortalState","Display command result; success=${payload!=null}; code=${error?.optString("code")}")
                                        val result=JSONObject().put("method","link.result").put("id",id).put("ok",payload!=null)
                                        if(payload!=null)result.put("payload",payload) else result.put("error",error?:JSONObject().put("code","display_failed").put("message","Image could not be shown."))
                                        control(result)
                                    }}
                                }
                            }
                        } else if(id.isNotBlank() && command==DisplayCommands.CLEAR && clearImage!=null) {
                            clearImage.invoke()
                            control(JSONObject().put("method","link.result").put("id",id).put("ok",true).put("payload",JSONObject()))
                        } else if(id.isNotBlank() && command=="camera.capture" && photo!=null) {
                            val mine=epoch
                            photo.invoke {bytes,error ->dispatch(mine){
                                android.util.Log.i("MusePortalState","Camera command result; success=${bytes!=null}")
                                val result=JSONObject().put("method","link.result").put("id",id).put("ok",bytes!=null)
                                if(bytes!=null)result.put("payload",JSONObject().put("format","jpeg-base64").put("mime_type","image/jpeg")
                                    .put("data_base64",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)))
                                else result.put("error",error?:"Camera unavailable")
                                control(result)
                            }}
                        } else control(JSONObject().put("method","link.result").put("id",id)
                            .put("ok",false).put("error","Unsupported Portal command."))
                    }
                }
            }
            subscribeId -> {
                lines+=data;check(lines.size<=1048576)
                while(true) {
                    val i=lines.indexOf(10.toByte());if(i<0)break
                    val line=String(lines.copyOfRange(0,i));lines=lines.copyOfRange(i+1,lines.size)
                    // Schema-only diagnostics: never log values or conversation content.
                    try {
                        val event=JSONObject(line)
                        if(event.optString("event")=="delta.presentation") {
                            val payload=event.optJSONObject("payload")
                            val keys=payload?.keys()?.asSequence()?.filter{it.matches(Regex("[a-z_]{1,40}"))}?.sorted()?.toList()
                            val dataKeys=payload?.optJSONObject("data")?.keys()?.asSequence()?.filter{it.matches(Regex("[a-z_]{1,40}"))}?.sorted()?.toList()
                            android.util.Log.i("MusePortalState","Presentation event schema: $keys; data=$dataKeys")
                        }
                        if(event.optString("event")=="message.user") {
                            val payload=event.optJSONObject("payload")
                            val keys=payload?.keys()?.asSequence()?.filter{it.matches(Regex("[a-z_]{1,40}"))}?.sorted()?.toList()
                            android.util.Log.i("MusePortalState","User event schema: $keys; seqType=${event.opt("seq")?.javaClass?.simpleName}")
                            val nested=payload?.optJSONObject("payload")
                            for ((label,obj) in listOf("envelope" to payload,"nested" to nested)) {
                                val shape=listOf("message_id","display_text","content","text","transcript").joinToString { key ->
                                    val value=obj?.opt(key)
                                    "$key:${value?.javaClass?.simpleName}:${(value as? String)?.length?:0}"
                                }
                                val nestedKeys=obj?.keys()?.asSequence()?.filter{it.matches(Regex("[a-z_]{1,40}"))}?.sorted()?.toList()
                                android.util.Log.i("MusePortalState","User $label shape: $shape; keys=$nestedKeys; ready=${obj?.optBoolean("display_text_ready",true)}")
                                val text=obj?.optString("display_text").orEmpty()
                                android.util.Log.i("MusePortalState","User $label flags: eventType=${event.optString("type")=="event"}; filePrefix=${text.trim().startsWith("[file:")}; voicePlaceholder=${text.trim()=="[Voice note]"}; visibleLength=${text.substringBefore("\n[file:").trim().length}")
                            }
                        }
                    } catch(_:Exception) {}
                    parser.feedLine(line).forEach {event ->
                        if(event is SubscribeParser.Event.UserTranscript) {
                            val match=transcripts.receive(event.id,event.text)
                            android.util.Log.i("MusePortalState","Transcript event: matched device ACK=${match!=null}")
                            match?.let {transcript(it.first,it.second)}
                        }
                        if(event is SubscribeParser.Event.MessageDone) {
                            val delivery=replies.receive(event)
                            android.util.Log.i("MusePortalState","Reply origin gate: parentPresent=${event.replyTo!=null}, display=${delivery.display}, speak=${delivery.speak}")
                            deliverReply(delivery)
                        }
                        if(event is SubscribeParser.Event.Presentation) {
                            val delivery=replies.receivePresentation(event)
                            android.util.Log.i("MusePortalState","Presentation origin gate: parentPresent=${event.replyTo!=null}, display=${delivery.display}")
                            deliverReply(delivery)
                        }
                    }
                }
            }
        }
        if(end && f.streamId in listOf(controlId,subscribeId)) fail("Muse stream ended")
        if(end)sends.remove(f.streamId)
    }
    private fun deliverReply(delivery:PortalReplyRouter.Delivery) {
        if(delivery.display)reply(delivery.text)
        if(delivery.speak)voiceReply(delivery.text)
    }
    fun sendText(text: String) {
        if(!ready){status("Not connected. Nothing sent.");return}
        work.execute {try{
            require(text.length<=16000)
            val request=transport!!.encryptHttpRequest("POST",Identity.CHAT_STREAM_PATH,
                ChatStreams.textBody(text,store.nodeId),headers=ChatStreams.chatHeaders(UUID.randomUUID().toString()))
            sends.add(request.streamId);send(request.frames)
        }catch(_:Exception){fail("Send interrupted; not automatically resent")}}
    }
    fun sendVoice(wav: ByteArray, localId: String = UUID.randomUUID().toString()) {
        if(!ready){status("Not connected. Recording was not sent.");return}
        work.execute {try{
            val b=JSONObject().put("message","").put("device_id",store.nodeId).put("output_modality","text")
                .put("items",org.json.JSONArray().put(JSONObject().put("type","file").put("mime_type","audio/wav")
                    .put("filename","voice_note.wav").put("data_base64",android.util.Base64.encodeToString(wav,android.util.Base64.NO_WRAP))))
                .toString().toByteArray()
            val request=transport!!.startStreamRequest("POST",Identity.CHAT_STREAM_PATH,headers=ChatStreams.chatHeaders(UUID.randomUUID().toString()))
            voiceSends[request.streamId]=localId
            sends.add(request.streamId);send(request.frames)
            b.asList().chunked(6144).forEachIndexed {i,chunk ->
                send(transport!!.encryptBodyChunk(request.streamId,chunk.toByteArray(),endBody=(i+1)*6144>=b.size))
            }
        }catch(_:Exception){fail("Voice send interrupted; not automatically resent")}}
    }
    fun sendPhoto(jpeg:ByteArray) {
        if(!ready){status("Not connected. Photo was not sent.");return}
        work.execute {try{
            require(jpeg.size<=128*1024)
            val body=JSONObject().put("message","Photo from the Portal camera.").put("device_id",store.nodeId).put("output_modality","text")
                .put("items",org.json.JSONArray().put(JSONObject().put("type","file").put("mime_type","image/jpeg")
                    .put("filename","portal_photo.jpg").put("data_base64",android.util.Base64.encodeToString(jpeg,android.util.Base64.NO_WRAP))))
                .toString().toByteArray()
            val request=transport!!.startStreamRequest("POST",Identity.CHAT_STREAM_PATH,headers=ChatStreams.chatHeaders(UUID.randomUUID().toString()))
            sends.add(request.streamId);send(request.frames)
            for(offset in body.indices step 6144)send(transport!!.encryptBodyChunk(request.streamId,
                body.copyOfRange(offset,minOf(offset+6144,body.size)),endBody=offset+6144>=body.size))
        }catch(_:Exception){fail("Photo send interrupted; not automatically resent")}}
    }
    private fun fail(message: String) {
        ready=false;readyChanged(false);status("$message. Pending messages will not be resent.")
        ++epoch;socket?.cancel();socket=null
        if(!closed && retries++<3)work.schedule({open()},10,TimeUnit.SECONDS)
    }
    fun close() {closed=true;ready=false;++epoch;socket?.cancel();work.shutdownNow();http.dispatcher.executorService.shutdown()}
}
