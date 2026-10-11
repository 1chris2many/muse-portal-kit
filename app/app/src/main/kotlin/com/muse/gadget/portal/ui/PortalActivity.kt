package com.muse.gadget.portal.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.*
import com.muse.gadget.audio.VoiceNoteEncoder
import com.muse.gadget.audio.isEnglishVoiceLanguage
import com.muse.gadget.portal.*
import com.muse.gadget.portal.ble.NativePairingPeripheral
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Foreground, explicit push-to-talk only. No conversation text is persisted locally. */
class PortalActivity: Activity() {
    private lateinit var store: DeviceStore
    private lateinit var connection: MuseConnection
    private lateinit var statusView: TextView
    private lateinit var transcript: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var audioState: TextView
    private lateinit var micState: TextView
    private val voiceBubbles=LinkedHashMap<String,TextView>()
    private lateinit var talk: Button
    private lateinit var send: Button
    private var pairing: NativePairingPeripheral?=null
    private var tts: TextToSpeech?=null
    private var offlineSpeech=false
    @Volatile private var recording=false
    @Volatile private var recorder: AudioRecord?=null
    @Volatile private var foreground=false
    @Volatile private var cancelled=false
    private var capture: Thread?=null
    private val camera=PortalCamera()
    private lateinit var cameraState:TextView
    private var cameraBusy=false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
        store=DeviceStore(this)
        java.io.File(noBackupFilesDir,"identity-public.json").writeText(
            JSONObject().put("mac",store.mac).put("node_id",store.nodeId).toString())
        volumeControlStream=AudioManager.STREAM_MUSIC
        val layout=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(36,24,36,24);setBackgroundColor(Color.rgb(12,17,27))}
        val header=LinearLayout(this).apply{gravity=android.view.Gravity.CENTER_VERTICAL}
        val heading=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        heading.addView(label("Muse",34f).apply{typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)})
        statusView=label("Connecting…",15f);heading.addView(statusView)
        header.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
        header.addView(button("Settings"){showSettings()})
        layout.addView(header)
        audioState=label("Checking speaker…",13f).apply{setTextColor(Color.rgb(153,171,194));setPadding(0,8,0,12)}
        layout.addView(audioState)
        val cameraRow=LinearLayout(this)
        cameraState=label("Camera idle · Nova can take photos while this app is open",13f)
        cameraRow.addView(cameraState,LinearLayout.LayoutParams(0,-2,1f))
        cameraRow.addView(button("Take & share photo"){
            takePhoto("You"){bytes,error ->if(bytes!=null){connection.sendPhoto(bytes)}else status(error?:"Camera unavailable")}
        })
        layout.addView(cameraRow)
        transcript=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,8,0,16)}
        scroll=ScrollView(this).apply{addView(transcript);isFillViewport=true}
        layout.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val inputRow=LinearLayout(this)
        val input=EditText(this).apply{hint="Or type a message…";textSize=18f;setPadding(20,12,20,12);background=panel(Color.rgb(28,38,54));setTextColor(Color.WHITE);setHintTextColor(Color.LTGRAY);inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE;maxLines=3}
        inputRow.addView(input,LinearLayout.LayoutParams(0,-2,1f))
        send=button("Send"){
            val text=input.text.toString().trim()
            if(text.isNotEmpty()){append("You",text);connection.sendText(text);input.text.clear()}
        }.apply{isEnabled=false}
        inputRow.addView(send);layout.addView(inputRow)
        talk=button("Hold to talk"){}.apply{textSize=22f;setTextColor(Color.rgb(12,28,35));background=panel(Color.rgb(118,222,199));isEnabled=false;minimumHeight=72
            setOnTouchListener{_,event -> when(event.actionMasked){
                MotionEvent.ACTION_DOWN -> {startRecording();true}
                MotionEvent.ACTION_UP -> {recording=false;performClick();true}
                MotionEvent.ACTION_CANCEL -> {cancelled=true;recording=false;true}
                else -> true
            }}
        };layout.addView(talk,LinearLayout.LayoutParams(-1,76).apply{topMargin=14})
        micState=label("Microphone off · Hold to speak, release to send",13f).apply{gravity=android.view.Gravity.CENTER;setPadding(0,10,0,0);setTextColor(Color.rgb(153,171,194))}
        layout.addView(micState)
        setContentView(layout)
        connection=MuseConnection(store,::status,{text ->runOnUiThread{
            if(foreground){append("Muse",text);if(!recording)speak(text)}
        }},{ready ->runOnUiThread{talk.isEnabled=ready;send.isEnabled=ready}}, {id,text ->runOnUiThread{
            voiceBubbles[id]?.text=text
            android.util.Log.i("MusePortalState","Voice transcript received and displayed.")
        }}, {done ->runOnUiThread{takePhoto("Muse",done)}})
        tts=TextToSpeech(this){result ->runOnUiThread{
            if(result==TextToSpeech.SUCCESS){
                tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                val voice=tts?.defaultVoice?.takeIf{!it.isNetworkConnectionRequired && isEnglishVoiceLanguage(it.locale.language)}
                    ?:tts?.voices?.sortedBy{it.name}?.firstOrNull{!it.isNetworkConnectionRequired && isEnglishVoiceLanguage(it.locale.language)}
                if(voice!=null){tts?.voice=voice;offlineSpeech=true}
                android.util.Log.i("MusePortalHardware","TTS initialized; voices=${tts?.voices?.size?:0}; offlineEnglish=$offlineSpeech; defaultNetwork=${tts?.defaultVoice?.isNetworkConnectionRequired}; languageResult=${tts?.isLanguageAvailable(Locale.US)}")
                android.util.Log.i("MusePortalHardware","TTS locale tags: ${tts?.voices?.map{it.locale.toLanguageTag()}?.distinct()?.sorted()}; default=${tts?.defaultVoice?.locale?.toLanguageTag()}")
            }
            audioState.text=if(offlineSpeech)"Speaker ready · On-device voice" else "Audio unavailable · No offline English voice found"
            tts?.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
                override fun onStart(id:String?){runOnUiThread{audioState.text="Speaking · Tap Stop in Settings to interrupt"};android.util.Log.i("MusePortalState","Speech playback started.")}
                override fun onDone(id:String?){runOnUiThread{audioState.text="Speaker ready · On-device voice"};android.util.Log.i("MusePortalState","Speech playback completed.")}
                @Deprecated("Android legacy callback") override fun onError(id:String?){runOnUiThread{audioState.text="Speech failed · Check audio settings"};android.util.Log.i("MusePortalState","Speech synthesis failed.")}
            })
        }}
        diagnostics()
        // USB installer may stage a token in this app's private no-backup directory.
        // This is not a network endpoint, exported receiver or credential log.
        val staged=java.io.File(noBackupFilesDir,"sdk-import")
        if(staged.isFile){
            try {
                require(staged.length() in 12..512)
                val token=staged.readText().trim()
                require(token.startsWith("mgst_"))
                store.save("sdk",JSONObject().put("token",token))
                check(staged.delete())
                startPairing(token)
            }catch(_:Exception){staged.delete();status("USB token setup failed; use Pair device.")}
        }
        val pairRequest=java.io.File(noBackupFilesDir,"pair-request")
        if(pairRequest.isFile && pairRequest.delete())store.read("sdk")?.optString("token")?.let(::startPairing)
        val imported=java.io.File(noBackupFilesDir,"pairing-import")
        if(imported.isFile) {
            Thread {
                try {
                    require(imported.length() in 2..16384)
                    val credentials=JSONObject(imported.readText())
                    connection.validate(credentials)
                    store.save("pairing",credentials)
                    check(imported.delete())
                    status("USB-assisted pairing imported securely.")
                    connection.connect()
                }catch(_:Exception){imported.delete();status("USB-assisted pairing validation failed; no new pairing saved.")}
            }.start()
        } else if(store.read("pairing")!=null && !staged.exists())connection.connect()
    }
    override fun onStart(){super.onStart();foreground=true}
    private fun takePhoto(who:String,done:(ByteArray?,String?)->Unit) {
        if(!foreground){done(null,"Open the Portal app before taking a photo.");return}
        if(cameraBusy){done(null,"Camera is already taking a photo.");return}
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(arrayOf(Manifest.permission.CAMERA),11)
            done(null,"Camera permission is required on the Portal. Retry after granting it.");return
        }
        cameraState.text="CAMERA ON · $who is taking a photo"
        cameraState.setTextColor(Color.rgb(255,179,104))
        cameraBusy=true
        camera.capture {bytes,error ->
            cameraBusy=false
            cameraState.text=if(bytes!=null)"Camera idle · Photo taken by $who" else "Camera idle · ${error?:"Capture failed"}"
            cameraState.setTextColor(Color.rgb(234,240,248))
            if(bytes!=null){
                append(who,"Portal photo")
                val bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                transcript.addView(ImageView(this).apply{setImageBitmap(bitmap);adjustViewBounds=true;maxHeight=300},LinearLayout.LayoutParams(-1,300))
                android.util.Log.i("MusePortalState","Camera photo captured; bytes=${bytes.size}; initiatedByUser=${who=="You"}")
            }
            done(bytes,error)
        }
    }
    private fun status(text:String){
        // Status callbacks never contain bearer/SDK tokens or conversation text.
        android.util.Log.i("MusePortalState",text)
        runOnUiThread{statusView.text=text}
    }
    private fun label(text:String,size:Float)=TextView(this).apply{this.text=text;textSize=size;setTextColor(Color.rgb(234,240,248))}
    private fun panel(color:Int)=GradientDrawable().apply{setColor(color);cornerRadius=20f}
    private fun button(text:String, action:()->Unit)=Button(this).apply{this.text=text;isAllCaps=false;textSize=16f;setTextColor(Color.WHITE);setOnClickListener{action()}}
    private fun append(who:String,text:String):TextView {
        val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(22,14,22,18);background=panel(if(who=="You")Color.rgb(26,49,60)else Color.rgb(26,34,48))}
        card.addView(label(who.uppercase(Locale.ROOT),12f).apply{setTextColor(Color.rgb(125,208,191));letterSpacing=.12f})
        val body=label(text,21f).apply{setPadding(0,7,0,0);setTextIsSelectable(true);setLineSpacing(3f,1.08f)}
        card.addView(body)
        transcript.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=12;if(who=="You")leftMargin=80 else rightMargin=80})
        while(transcript.childCount>60)transcript.removeViewAt(0)
        scroll.post{scroll.fullScroll(android.view.View.FOCUS_DOWN)}
        return body
    }
    private fun speak(text:String){
        if(!offlineSpeech){audioState.text="Audio unavailable · No offline English voice found";return}
        text.chunked(3500).forEach {part ->
            if(tts?.speak(part,TextToSpeech.QUEUE_ADD,null,java.util.UUID.randomUUID().toString())==TextToSpeech.ERROR)
                audioState.text="Speech could not start · Check audio settings"
        }
    }
    private fun showSettings(){
        AlertDialog.Builder(this).setTitle("Portal settings").setItems(arrayOf("Test speaker","Stop audio","Reconnect","Pair device","Diagnostics")){_,which ->when(which){
            0->speak("Your Portal speaker is ready.")
            1->{cancelled=true;recording=false;tts?.stop()}
            2->connection.connect()
            3->showPairing()
            4->diagnostics()
        }}.setNegativeButton("Close",null).show()
    }
    private fun diagnostics(){
        val adapter=(getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val line="Android ${android.os.Build.VERSION.RELEASE} · Bluetooth ${adapter?.isEnabled} · BLE advertising ${adapter?.isMultipleAdvertisementSupported} · offline voice $offlineSpeech"
        status(line)
        // Only hardware capability metadata; never token, transcript, identity or account data.
        android.util.Log.i("MusePortalHardware",line)
    }
    private fun showPairing(){
        val saved=store.read("sdk")?.optString("token")
        if(!saved.isNullOrBlank()) {startPairing(saved);return}
        val field=EditText(this).apply{hint="Muse SDK token (mgst_…)";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        AlertDialog.Builder(this).setTitle("Pair with your Muse account")
            .setMessage("Enter the SDK token from gadgets.muse.ai. It stays encrypted on this Portal. Then pair in the Muse phone app. Pairing closes in 10 minutes.")
            .setView(field).setNegativeButton("Cancel",null).setPositiveButton("Start pairing"){_,_ ->
                val token=field.text.toString().trim();field.text.clear()
                if(!token.startsWith("mgst_")||token.length<12){status("Enter a valid Muse SDK token.");return@setPositiveButton}
                startPairing(token)
            }.show()
    }
    private fun startPairing(token:String){
        try{
            pairing?.stop()
            store.save("sdk",JSONObject().put("token",token))
            pairing=NativePairingPeripheral(this,store,token,connection::validate,::status,{connection.connect()})
            pairing!!.start()
        }catch(e:Exception){pairing?.stop();pairing=null;status("Pairing unavailable: "+(e.message?:e.javaClass.simpleName))}
    }
    private fun startRecording(){
        if(capture?.isAlive==true)return
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),10);return
        }
        tts?.stop();cancelled=false;recording=true;talk.text="Listening… release to send"
        micState.text="Microphone on · Up to 15 seconds"
        capture=Thread{
            var local:AudioRecord?=null
            try{
                val minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
                require(minimum>0)
                local=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(minimum,4096))
                check(local.state==AudioRecord.STATE_INITIALIZED)
                recorder=local;local.startRecording()
                val pcm=ByteArrayOutputStream();val block=ByteArray(2048)
                while(recording&&foreground&&pcm.size()<480000){
                    val n=local.read(block,0,minOf(block.size,480000-pcm.size()))
                    check(n>=0);if(n>0)pcm.write(block,0,n)
                }
                if(!cancelled&&foreground&&pcm.size()>=1600){
                    val data=pcm.toByteArray();val header=VoiceNoteEncoder.wavHeader()
                    java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(4,data.size+36).putInt(40,data.size)
                    val id=java.util.UUID.randomUUID().toString()
                    runOnUiThread{
                        voiceBubbles[id]=append("You","Transcribing your voice note…")
                        while(voiceBubbles.size>50)voiceBubbles.remove(voiceBubbles.keys.first())
                        connection.sendVoice(header+data,id)
                        scroll.postDelayed({
                            voiceBubbles[id]?.takeIf{it.text.toString()=="Transcribing your voice note…"}
                                ?.text="No transcript received yet. Check the connection status."
                        },45000)
                    }
                }
            }catch(_:Exception){status("Microphone capture failed; no recording sent.")}
            finally{recording=false;try{local?.stop()}catch(_:Exception){};local?.release();recorder=null
                runOnUiThread{talk.text="Hold to talk";micState.text="Microphone off · Hold to speak, release to send"}}
        }.also{it.start()}
    }
    override fun onStop(){foreground=false;cancelled=true;recording=false;camera.cancel();tts?.stop();super.onStop()}
    override fun onDestroy(){pairing?.stop();connection.close();tts?.shutdown();super.onDestroy()}
}
