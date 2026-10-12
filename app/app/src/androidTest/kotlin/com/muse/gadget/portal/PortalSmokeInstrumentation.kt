package com.muse.gadget.portal

import android.app.Instrumentation
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
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
            val activity=startActivitySync(Intent(targetContext,PortalActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            // Verify the delivered native dashboard without tapping Talk, enabling wake,
            // reading conversations, capturing the camera, or injecting a Muse prompt.
            runOnMainSync {
                val matches=ArrayList<View>()
                activity.window.decorView.findViewsWithText(matches,"Wake off",View.FIND_VIEWS_WITH_TEXT)
                val wake=matches.filterIsInstance<TextView>().single { it.text.toString()=="Wake off" }
                check(wake.isShown && wake.width>0 && wake.height>0) { "Wake control not visible" }
                check(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0) {
                    "Privacy screenshot protection absent"
                }
                report.putBoolean("wake_off",true)
                report.putBoolean("dashboard_visible",true)
                report.putBoolean("privacy_flag",true)
            }
            if(options.getString("wake_engine")=="true") {
                // Loading the installed ABI and checking trusted assets never opens a microphone.
                System.loadLibrary("vosk")
                targetContext.assets.open("wake-vosk/archive-sha256.txt").use { input ->
                    check(input.bufferedReader().readText().trim()==
                        "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498")
                }
                report.putBoolean("wake_native_library_loaded",true)
                report.putBoolean("wake_model_packaged",true)
            }
            if(options.getString("media_decode")=="true") {
                // Generated fixture bytes only. Exercise actual Android decoders,
                // without presenting an image, playing audio or invoking Muse.
                val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
                val encoded = java.io.ByteArrayOutputStream()
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, encoded))
                bitmap.recycle()
                val payload = org.json.JSONObject().put("version",1).put("device_id","fixture")
                    .put("conversation_id","existing-main").put("generation","fixture").put("request_id","fixture")
                    .put("input_message_id","fixture").put("delivery_id","fixture")
                    .put("content",org.json.JSONArray().put(org.json.JSONObject().put("type","image").put("mime_type","image/png")
                        .put("data_base64",android.util.Base64.encodeToString(encoded.toByteArray(),android.util.Base64.NO_WRAP))))
                val prepared = AddressedDeliveryJson.prepare(payload)
                check(prepared.image?.width == 2 && prepared.image?.height == 2)
                prepared.discard()
                report.putBoolean("actual_bitmap_decoder",true)
                // 0.15s silent MP3 generated locally with ffmpeg, not a person/voice recording.
                val fixture = "//MgxAAAAANIAAAAAExBTUU0LjBVVVVVVVVVVVVVVVVVTEFNRTQuMFVVVVVVVVVVVVVVVf/zIsQnAAADSAAAAABVVVVVVVVVVVVVVVVVVVVVVVVVVUxBTUU0LjBVVVVVVVVVVVVVVVVV//MgxE8AAANIAAAAAFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVf/zIMR2AAADSAAAAABVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/8yDEnQAAA0gAAAAAVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//MixMQAAANIAAAAAFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/8yDE2AAAA0gAAAAAVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//MgxNgAAANIAAAAAFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVQ=="
                val source = PortalAudioPlayer.MemorySource(android.util.Base64.decode(fixture,android.util.Base64.NO_WRAP))
                val latch = CountDownLatch(1)
                var player: android.media.MediaPlayer? = null
                var duration = 0
                try {
                    runOnMainSync {
                        player = android.media.MediaPlayer().apply {
                            setOnPreparedListener { duration = it.duration; latch.countDown() }
                            setOnErrorListener { _,_,_ -> latch.countDown(); true }
                            setDataSource(source)
                            prepareAsync() // Deliberately never call start().
                        }
                    }
                    check(latch.await(8,TimeUnit.SECONDS) && duration in 1..3000) { "Actual MP3 decoder did not prepare fixture" }
                    report.putInt("mp3_fixture_duration_ms",duration)
                    report.putBoolean("audio_played",false)
                } finally { runOnMainSync { player?.release(); source.close() } }
            }
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
