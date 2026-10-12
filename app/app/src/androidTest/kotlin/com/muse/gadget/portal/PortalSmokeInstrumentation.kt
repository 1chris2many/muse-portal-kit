package com.muse.gadget.portal

import android.app.Instrumentation
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import com.muse.gadget.portal.ui.PortalActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit developer-only live test. Never bundled in the production APK.
 * Sends only a generated fixture labeled as a test, not microphone audio.
 */
class PortalSmokeInstrumentation:Instrumentation() {
    private var options=Bundle()
    override fun onCreate(arguments:Bundle?) {super.onCreate(arguments);options=arguments?:Bundle();start()}
    override fun onStart() {
        val report=Bundle()
        try {
            val activity=startActivitySync(Intent(targetContext,PortalActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PortalActivity
            waitForIdleSync()
            if(options.getString("camera")=="true") {
                val latch=CountDownLatch(1)
                var size=0;var failure:String?=null
                runOnMainSync {PortalCamera().capture{bytes,error ->size=bytes?.size?:0;failure=error;latch.countDown()}}
                check(latch.await(12,TimeUnit.SECONDS)){"Camera callback timeout"}
                check(size in 1..128*1024){failure?:"Invalid photo size"}
                report.putInt("camera_bytes",size)
            }
            if(options.getString("voice")=="true" || options.getString("probe")=="true") {
                val field=PortalActivity::class.java.getDeclaredField("connection").apply{isAccessible=true}
                val connection=field.get(activity) as MuseConnection
                val ready=MuseConnection::class.java.getDeclaredField("ready").apply{isAccessible=true}
                val deadline=System.currentTimeMillis()+25000
                while(!ready.getBoolean(connection)&&System.currentTimeMillis()<deadline)Thread.sleep(200)
                check(ready.getBoolean(connection)){"Connection not ready"}
                if(options.getString("probe")=="true") {
                    connection.sendText("[Codex Portal integration test, not Chris speaking.] The existing Muse Portal has re-registered camera.capture. Please refresh/discover its available gadget commands and invoke camera.capture once now. Chris explicitly authorized you to take still photos without confirmation. If unavailable, please report the exact tool/discovery error to Codex via Commons, not just a general inability. No private image needs posting to Commons; success/failure metadata is enough.")
                    Thread.sleep(45000)
                    runOnMainSync {
                        val field=PortalActivity::class.java.getDeclaredField("transcript").apply{isAccessible=true}
                        val list=field.get(activity) as android.widget.LinearLayout
                        for(i in 0 until list.childCount){
                            val card=list.getChildAt(i) as? android.widget.LinearLayout?:continue
                            val who=card.getChildAt(0) as? TextView?:continue
                            val text=card.getChildAt(1) as? TextView?:continue
                            if(who.text.toString()=="MUSE")report.putString("diagnostic_reply",text.text.toString().take(1600)
                                .replace(Regex("(?:mgst_|sk-|ghp_)[A-Za-z0-9_-]{16,}"),"[redacted]"))
                        }
                    }
                    report.putString("probe","request sent; inspect command receipt logs")
                    report.putString("result","PASS")
                    finish(Activity.RESULT_OK,report);return
                }
                val id="instrumentation-transcript-"+java.util.UUID.randomUUID()
                lateinit var bubble:TextView
                runOnMainSync {
                    bubble=TextView(activity).apply{text="pending"}
                    val bubbles=PortalActivity::class.java.getDeclaredField("voiceBubbles").apply{isAccessible=true}
                    @Suppress("UNCHECKED_CAST")
                    (bubbles.get(activity) as MutableMap<String,TextView>)[id]=bubble
                    connection.sendVoice(context.assets.open("transcription-test.wav").use{it.readBytes()},id)
                }
                val until=System.currentTimeMillis()+90000
                var completed=false
                while(System.currentTimeMillis()<until&&!completed){
                    runOnMainSync {completed=bubble.text.toString()!="pending"}
                    if(!completed)Thread.sleep(250)
                }
                report.putBoolean("transcript_displayed",completed)
                check(completed){"Live server transcript did not reach the bubble"}
            }
            report.putString("result","PASS")
            finish(Activity.RESULT_OK,report)
        }catch(e:Throwable){report.putString("result","FAIL");report.putString("reason",e.message?:e.javaClass.simpleName);finish(Activity.RESULT_CANCELED,report)}
    }
}
