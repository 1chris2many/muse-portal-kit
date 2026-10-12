package com.muse.gadget.portal

import android.app.Instrumentation
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.muse.gadget.portal.ui.PortalActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Local hardware smoke test only. Never sends chat messages or voice notes.
 * Protocol, transcript and speech-routing tests use offline JVM fixtures.
 */
class PortalSmokeInstrumentation:Instrumentation() {
    private var options=Bundle()
    override fun onCreate(arguments:Bundle?) {super.onCreate(arguments);options=arguments?:Bundle();start()}
    override fun onStart() {
        val report=Bundle()
        try {
            check(listOf("voice","audio","probe").none{options.getString(it)=="true"}) {
                "Network chat tests are disabled. Use offline protocol fixtures; coordinate through the agreed channel."
            }
            startActivitySync(Intent(targetContext,PortalActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            if(options.getString("camera")=="true") {
                val latch=CountDownLatch(1)
                var size=0;var failure:String?=null
                runOnMainSync {PortalCamera().capture{bytes,error ->size=bytes?.size?:0;failure=error;latch.countDown()}}
                check(latch.await(12,TimeUnit.SECONDS)){"Camera callback timeout"}
                check(size in 1..128*1024){failure?:"Invalid photo size"}
                report.putInt("camera_bytes",size)
            }
            report.putString("result","PASS")
            finish(Activity.RESULT_OK,report)
        }catch(e:Throwable){report.putString("result","FAIL");report.putString("reason",e.message?:e.javaClass.simpleName);finish(Activity.RESULT_CANCELED,report)}
    }
}
