package com.muse.gadget.portal.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.muse.gadget.audio.VoiceNoteEncoder
import com.muse.gadget.audio.isEnglishVoiceLanguage
import com.muse.gadget.portal.DeviceStore
import com.muse.gadget.portal.LocalPortalBridge
import com.muse.gadget.portal.MuseConnection
import com.muse.gadget.portal.PortalCamera
import com.muse.gadget.portal.ble.NativePairingPeripheral
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Foreground, explicit push-to-talk only. No conversation text is persisted locally. */
class PortalActivity : Activity() {
    private lateinit var store: DeviceStore
    private lateinit var connection: MuseConnection
    private lateinit var ui: PortalDashboard
    private val voiceBubbles = LinkedHashMap<String, TextView>()
    private var pairing: NativePairingPeripheral? = null
    private var tts: TextToSpeech? = null
    private var offlineSpeech = false
    @Volatile private var recording = false
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var foreground = false
    @Volatile private var cancelled = false
    private var capture: Thread? = null
    private val camera = PortalCamera()
    private var cameraBusy = false
    private var localBridge: LocalPortalBridge? = null
    private val privacyHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val clearVisible = Runnable { clearConversation() }
    private var connected = false

    private fun clearConversation() {
        if (::ui.isInitialized) {
            ui.clearConversation()
            ui.messageInput.text.clear()
        }
        voiceBubbles.clear()
        tts?.stop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
            statusBarColor = PortalPalette.BACKGROUND
            navigationBarColor = PortalPalette.BACKGROUND
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        volumeControlStream = AudioManager.STREAM_MUSIC
        store = DeviceStore(this)
        java.io.File(noBackupFilesDir, "identity-public.json").writeText(
            JSONObject().put("mac", store.mac).put("node_id", store.nodeId).toString(),
        )

        ui = PortalDashboard(this)
        setContentView(ui)
        bindUiActions()

        connection = MuseConnection(
            store,
            ::status,
            { text -> runOnUiThread { if (foreground) appendMessage("Muse", text) } },
            { ready -> runOnUiThread {
                connected = ready
                ui.setConnectionState(ready)
                ui.deviceStatus.show(
                    if (ready) "READY" else "CONNECTING",
                    if (ready) "Hold to talk or type a message. Your history stays off this screen." else "Opening the secure connection",
                )
            } },
            { id, text -> runOnUiThread {
                voiceBubbles[id]?.text = text
                android.util.Log.i("MusePortalState", "Voice transcript received and displayed.")
            } },
            { done -> runOnUiThread { takePhoto("Muse", done) } },
            { text -> runOnUiThread { if (foreground && !recording) speak(text) } },
        )
        localBridge = LocalPortalBridge(this) { done -> runOnUiThread { takePhoto("Muse", done) } }
        initializeTextToSpeech()
        diagnostics()
        importProvisioningIfPresent()
    }

    private fun bindUiActions() {
        ui.settingsButton.setOnClickListener { showSettings() }
        ui.clearButton.setOnClickListener { clearConversation() }
        ui.stopAudioButton.setOnClickListener {
            cancelled = true
            recording = false
            tts?.stop()
            ui.setAudioState("AUDIO STOPPED", "Playback stopped", PortalPalette.TEXT_SECONDARY)
        }
        ui.cameraButton.setOnClickListener {
            takePhoto("You") { bytes, error ->
                if (bytes != null) connection.sendPhoto(bytes) else status(error ?: "Camera unavailable")
            }
        }
        ui.sendButton.setOnClickListener { sendTypedMessage() }
        ui.messageInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                sendTypedMessage()
                true
            } else {
                false
            }
        }
        ui.talkButton.setOnTouchListener { _, event ->
            if (!ui.talkButton.isEnabled) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ui.talkButton.setTouchPressed(true)
                    startRecording()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    ui.talkButton.setTouchPressed(false)
                    recording = false
                    ui.talkButton.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    ui.talkButton.setTouchPressed(false)
                    cancelled = true
                    recording = false
                    true
                }
                else -> true
            }
        }
    }

    private fun sendTypedMessage() {
        val text = ui.messageInput.text.toString().trim()
        if (text.isEmpty()) return
        appendMessage("You", text)
        connection.sendText(text)
        ui.messageInput.text.clear()
    }

    private fun initializeTextToSpeech() {
        tts = TextToSpeech(this) { result -> runOnUiThread {
            if (result == TextToSpeech.SUCCESS) {
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                val voice = tts?.defaultVoice?.takeIf {
                    !it.isNetworkConnectionRequired && isEnglishVoiceLanguage(it.locale.language)
                } ?: tts?.voices?.sortedBy { it.name }?.firstOrNull {
                    !it.isNetworkConnectionRequired && isEnglishVoiceLanguage(it.locale.language)
                }
                if (voice != null) {
                    tts?.voice = voice
                    offlineSpeech = true
                }
                android.util.Log.i(
                    "MusePortalHardware",
                    "TTS initialized; voices=${tts?.voices?.size ?: 0}; offlineEnglish=$offlineSpeech; defaultNetwork=${tts?.defaultVoice?.isNetworkConnectionRequired}; languageResult=${tts?.isLanguageAvailable(Locale.US)}",
                )
                android.util.Log.i(
                    "MusePortalHardware",
                    "TTS locale tags: ${tts?.voices?.map { it.locale.toLanguageTag() }?.distinct()?.sorted()}; default=${tts?.defaultVoice?.locale?.toLanguageTag()}",
                )
            }
            if (offlineSpeech) {
                ui.setAudioState("SPEAKER READY", "On-device voice is ready", PortalPalette.LILAC)
            } else {
                ui.setAudioState("AUDIO UNAVAILABLE", "No offline English voice found", PortalPalette.DANGER)
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    runOnUiThread {
                        ui.setAudioState("SPEAKING", "On-device voice · Tap Stop to interrupt", PortalPalette.LILAC)
                        ui.deviceStatus.show("SPEAKING", "On-device voice · Tap Stop to interrupt")
                    }
                    android.util.Log.i("MusePortalState", "Speech playback started.")
                }

                override fun onDone(id: String?) {
                    runOnUiThread {
                        ui.setAudioState("SPEAKER READY", "On-device voice is ready", PortalPalette.LILAC)
                        ui.deviceStatus.show(if (connected) "READY" else "OFFLINE", "Hold to talk")
                    }
                    android.util.Log.i("MusePortalState", "Speech playback completed.")
                }

                override fun onStop(id: String?, interrupted: Boolean) {
                    runOnUiThread {
                        if (!recording) ui.deviceStatus.show(if (connected) "READY" else "OFFLINE", "Audio stopped")
                    }
                }

                @Deprecated("Android legacy callback")
                override fun onError(id: String?) {
                    runOnUiThread {
                        ui.setAudioState("SPEECH FAILED", "Check audio settings", PortalPalette.DANGER)
                    }
                    android.util.Log.i("MusePortalState", "Speech synthesis failed.")
                }
            })
        } }
    }

    private fun importProvisioningIfPresent() {
        // USB installer may stage a token in this app's private no-backup directory.
        // This is not a network endpoint, exported receiver or credential log.
        val staged = java.io.File(noBackupFilesDir, "sdk-import")
        if (staged.isFile) {
            try {
                require(staged.length() in 12..512)
                val token = staged.readText().trim()
                require(token.startsWith("mgst_"))
                store.save("sdk", JSONObject().put("token", token))
                check(staged.delete())
                startPairing(token)
            } catch (_: Exception) {
                staged.delete()
                status("USB token setup failed; use Pair device.")
            }
        }
        val pairRequest = java.io.File(noBackupFilesDir, "pair-request")
        if (pairRequest.isFile && pairRequest.delete()) store.read("sdk")?.optString("token")?.let(::startPairing)
        val imported = java.io.File(noBackupFilesDir, "pairing-import")
        if (imported.isFile) {
            Thread {
                try {
                    require(imported.length() in 2..16384)
                    val credentials = JSONObject(imported.readText())
                    connection.validate(credentials)
                    store.save("pairing", credentials)
                    check(imported.delete())
                    status("USB-assisted pairing imported securely.")
                    connection.connect()
                } catch (_: Exception) {
                    imported.delete()
                    status("USB-assisted pairing validation failed; no new pairing saved.")
                }
            }.start()
        } else if (store.read("pairing") != null && !staged.exists()) {
            connection.connect()
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
    }

    private fun takePhoto(who: String, done: (ByteArray?, String?) -> Unit) {
        if (!foreground) {
            done(null, "Open the Portal app before taking a photo.")
            return
        }
        if (cameraBusy) {
            done(null, "Camera is already taking a photo.")
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 11)
            done(null, "Camera permission is required on the Portal. Retry after granting it.")
            return
        }
        ui.setCameraState("CAPTURE IN PROGRESS", "$who is taking a photo", PortalPalette.WARNING)
        cameraBusy = true
        camera.capture { bytes, error -> runOnUiThread {
            cameraBusy = false
            if (bytes != null) {
                ui.setCameraState("CAMERA READY", "Photo captured by $who", PortalPalette.ACCENT)
                appendPhoto(who, android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                android.util.Log.i("MusePortalState", "Camera photo captured; bytes=${bytes.size}; initiatedByUser=${who == "You"}")
            } else {
                ui.setCameraState("CAMERA", error ?: "Capture failed", PortalPalette.DANGER)
            }
            done(bytes, error)
        } }
    }

    private fun status(text: String) {
        // Status callbacks never contain bearer/SDK tokens or conversation text.
        android.util.Log.i("MusePortalState", text)
        runOnUiThread { ui.connectionDetail.text = text }
    }

    private fun appendMessage(who: String, text: String): TextView {
        privacyHandler.removeCallbacks(clearVisible)
        privacyHandler.postDelayed(clearVisible, 120000)
        return ui.appendMessage(who, text)
    }

    private fun appendPhoto(who: String, bitmap: android.graphics.Bitmap?) {
        privacyHandler.removeCallbacks(clearVisible)
        privacyHandler.postDelayed(clearVisible, 120000)
        ui.appendPhoto(who, bitmap)
    }

    private fun speak(text: String) {
        if (!offlineSpeech) {
            ui.setAudioState("AUDIO UNAVAILABLE", "No offline English voice found", PortalPalette.DANGER)
            return
        }
        text.chunked(3500).forEach { part ->
            if (tts?.speak(part, TextToSpeech.QUEUE_ADD, null, java.util.UUID.randomUUID().toString()) == TextToSpeech.ERROR) {
                ui.setAudioState("SPEECH FAILED", "Check audio settings", PortalPalette.DANGER)
            }
        }
    }

    private fun showSettings() {
        val dialog = Dialog(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(16))
            background = portalSurface(PortalPalette.SURFACE_ELEVATED, 24, PortalPalette.BORDER)
        }
        card.addView(dialogText("Portal settings", 24f, PortalPalette.TEXT_PRIMARY, true))
        card.addView(dialogText("Device controls stay local to this Portal.", 14f, PortalPalette.TEXT_SECONDARY).apply {
            setPadding(0, dp(5), 0, dp(14))
        })
        fun option(title: String, detail: String, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                minimumHeight = dp(64)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                background = portalSurface(PortalPalette.SURFACE, 16, PortalPalette.BORDER)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }
            row.addView(dialogText(title, 16f, PortalPalette.TEXT_PRIMARY, true))
            row.addView(dialogText(detail, 13f, PortalPalette.TEXT_SECONDARY).apply { setPadding(0, dp(3), 0, 0) })
            card.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }
        option("Test speaker", "Play a short sentence using the on-device voice") { speak("Your Portal speaker is ready.") }
        option("Stop audio", "Interrupt any voice playback") {
            cancelled = true
            recording = false
            tts?.stop()
        }
        option("Reconnect", "Open a fresh secure connection to Muse") { connection.connect() }
        option("Pair device", "Pair this Portal with your existing Muse account") { showPairing() }
        option("Diagnostics", "Show Bluetooth and voice capability status") { diagnostics() }
        val close = PortalActionButton(this, "Close", PortalActionTone.QUIET).apply { setOnClickListener { dialog.dismiss() } }
        card.addView(close, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply {
            gravity = Gravity.END
        })
        dialog.setContentView(card)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun dialogText(value: String, sizeSp: Float, color: Int, medium: Boolean = false): TextView = TextView(this).apply {
        text = value
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        typeface = android.graphics.Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", android.graphics.Typeface.NORMAL)
    }

    private fun diagnostics() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val line = "Android ${android.os.Build.VERSION.RELEASE} · Bluetooth ${adapter?.isEnabled} · BLE advertising ${adapter?.isMultipleAdvertisementSupported} · offline voice $offlineSpeech"
        status(line)
        // Only hardware capability metadata; never token, transcript, identity or account data.
        android.util.Log.i("MusePortalHardware", line)
    }

    private fun showPairing() {
        val saved = store.read("sdk")?.optString("token")
        if (!saved.isNullOrBlank()) {
            startPairing(saved)
            return
        }
        val field = EditText(this).apply {
            hint = "Muse SDK token (mgst_…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        AlertDialog.Builder(this)
            .setTitle("Pair with your Muse account")
            .setMessage("Enter the SDK token from gadgets.muse.ai. It stays encrypted on this Portal. Then pair in the Muse phone app. Pairing closes in 10 minutes.")
            .setView(field)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start pairing") { _, _ ->
                val token = field.text.toString().trim()
                field.text.clear()
                if (!token.startsWith("mgst_") || token.length < 12) {
                    status("Enter a valid Muse SDK token.")
                    return@setPositiveButton
                }
                startPairing(token)
            }
            .show()
    }

    private fun startPairing(token: String) {
        if (android.os.Build.VERSION.SDK_INT >= 31 && listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
                .any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE), 12)
            status("Grant Bluetooth permission, then choose Pair device again.")
            return
        }
        try {
            pairing?.stop()
            store.save("sdk", JSONObject().put("token", token))
            pairing = NativePairingPeripheral(this, store, token, connection::validate, ::status) { connection.connect() }
            pairing!!.start()
        } catch (e: Exception) {
            pairing?.stop()
            pairing = null
            status("Pairing unavailable: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun startRecording() {
        if (capture?.isAlive == true) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
            return
        }
        tts?.stop()
        cancelled = false
        recording = true
        ui.talkButton.setListening(true)
        ui.deviceStatus.show("LISTENING", "Release to send · Maximum 15 seconds")
        ui.micState.text = "Microphone on · Release to send · Maximum 15 seconds"
        capture = Thread {
            var local: AudioRecord? = null
            try {
                val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                require(minimum > 0)
                local = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimum, 4096),
                )
                check(local.state == AudioRecord.STATE_INITIALIZED)
                recorder = local
                local.startRecording()
                val pcm = ByteArrayOutputStream()
                val block = ByteArray(2048)
                while (recording && foreground && pcm.size() < 480000) {
                    val count = local.read(block, 0, minOf(block.size, 480000 - pcm.size()))
                    check(count >= 0)
                    if (count > 0) pcm.write(block, 0, count)
                    var peak = 0
                    for (index in 0 until count - 1 step 2) {
                        val sample = ((block[index].toInt() and 255) or (block[index + 1].toInt() shl 8)).toShort().toInt()
                        peak = maxOf(peak, kotlin.math.abs(sample))
                    }
                    val level = peak / 32768f
                    val progress = pcm.size() / 480000f
                    runOnUiThread {
                        if (recording) ui.deviceStatus.show("LISTENING", "Release to send · Maximum 15 seconds", level, progress)
                    }
                }
                if (!cancelled && foreground && pcm.size() >= 1600) {
                    val data = pcm.toByteArray()
                    val header = VoiceNoteEncoder.wavHeader()
                    java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(4, data.size + 36).putInt(40, data.size)
                    val id = java.util.UUID.randomUUID().toString()
                    runOnUiThread {
                        voiceBubbles[id] = appendMessage("You", "Transcribing your voice note…")
                        while (voiceBubbles.size > 50) voiceBubbles.remove(voiceBubbles.keys.first())
                        connection.sendVoice(header + data, id)
                        ui.deviceStatus.show("SENT", "Waiting for a reply linked to this Portal")
                        ui.micState.text = "Voice note sent · Waiting for a linked reply"
                        ui.talkButton.setListening(false)
                        ui.micState.postDelayed({
                            voiceBubbles[id]?.takeIf { it.text.toString() == "Transcribing your voice note…" }
                                ?.text = "No transcript received yet. Check the connection status."
                        }, 45000)
                    }
                }
            } catch (_: Exception) {
                status("Microphone capture failed; no recording sent.")
            } finally {
                recording = false
                try {
                    local?.stop()
                } catch (_: Exception) {
                }
                local?.release()
                recorder = null
                runOnUiThread {
                    ui.talkButton.setListening(false)
                    ui.micState.text = "Microphone off · Hold to speak, then release to send"
                }
            }
        }.also { it.start() }
    }

    override fun onStop() {
        foreground = false
        cancelled = true
        recording = false
        camera.cancel()
        privacyHandler.removeCallbacks(clearVisible)
        clearConversation()
        super.onStop()
    }

    override fun onDestroy() {
        localBridge?.close()
        pairing?.stop()
        connection.close()
        tts?.shutdown()
        super.onDestroy()
    }
}
