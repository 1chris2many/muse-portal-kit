package com.muse.gadget.portal

import android.content.Context
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Developer USB fallback for platforms which do not dispatch gadget commands.
 * Loopback only; random bearer lives only in app-private no-backup storage.
 * Exposes no filesystem, shell, microphone, or general intent interface.
 */
class LocalPortalBridge(context:Context,private val capture:((ByteArray?,String?)->Unit)->Unit) {
    private val token=ByteArray(32).also{SecureRandom().nextBytes(it)}.joinToString(""){"%02x".format(it)}
    private val server=ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))
    private val config=java.io.File(context.noBackupFilesDir,"portal-bridge.json")
    @Volatile private var closed=false
    init {
        config.writeText(JSONObject().put("port",server.localPort).put("token",token).toString())
        config.setReadable(false,false);config.setWritable(false,false)
        config.setReadable(true,true);config.setWritable(true,true)
        Thread({
            while(!closed)try{server.accept().use {socket ->
                socket.soTimeout=15000
                val input=socket.getInputStream()
                val header=StringBuilder()
                while(!header.endsWith("\r\n\r\n")&&header.length<4096){val b=input.read();if(b<0)break;header.append(b.toChar())}
                val lines=header.toString().split("\r\n")
                val auth=lines.firstOrNull{it.startsWith("Authorization:",true)}?.substringAfter(':')?.trim().orEmpty()
                val allowed=MessageDigest.isEqual(auth.toByteArray(),("Bearer "+token).toByteArray())
                var code=200
                val result=when {
                    !allowed -> {code=403;JSONObject().put("error","Forbidden")}
                    lines.firstOrNull()=="GET /status HTTP/1.1" -> JSONObject().put("ok",true).put("camera",true)
                    lines.firstOrNull()=="POST /camera HTTP/1.1" -> {
                        val done=CountDownLatch(1);var bytes:ByteArray?=null;var error:String?=null
                        capture {photo,reason ->bytes=photo;error=reason;done.countDown()}
                        if(!done.await(12,TimeUnit.SECONDS)){code=504;JSONObject().put("error","Capture timed out")}
                        else if(bytes==null){code=409;JSONObject().put("error",error?:"Camera unavailable")}
                        else JSONObject().put("ok",true).put("format","jpeg-base64").put("mime_type","image/jpeg")
                            .put("data_base64",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP))
                    }
                    else -> {code=404;JSONObject().put("error","Unknown endpoint")}
                }
                val body=result.toString().toByteArray()
                socket.getOutputStream().apply{
                    write("HTTP/1.1 $code Result\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray());write(body);flush()
                }
            }}catch(_:Exception){if(!closed)android.util.Log.i("MusePortalState","Local Portal bridge request failed.")}
        },"portal-local-bridge").also{it.isDaemon=true;it.start()}
    }
    fun close(){closed=true;server.close();config.delete()}
}
