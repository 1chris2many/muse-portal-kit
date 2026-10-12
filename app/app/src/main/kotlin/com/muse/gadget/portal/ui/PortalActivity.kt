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
import android.widget.ScrollView
import android.widget.TextView
import com.muse.gadget.audio.SpeechScript
import com.muse.gadget.audio.CaptureGeneration
import com.muse.gadget.audio.WakeSessionPolicy
import com.muse.gadget.audio.WakeUtteranceEndpoint
import com.muse.gadget.audio.VoiceNoteEncoder
import com.muse.gadget.audio.isEnglishVoiceLanguage
import com.muse.gadget.portal.DeviceStore
import com.muse.gadget.portal.LocalPortalBridge
import com.muse.gadget.portal.MuseConnection
import com.muse.gadget.portal.AddressedDeliveryJson
import com.muse.gadget.session.AddressedReplies
import com.muse.gadget.options.ReplyOptionsGate
import com.muse.gadget.portal.PortalCamera
import com.muse.gadget.portal.PortalImageFetcher
import com.muse.gadget.portal.wake.VoskWakeWordDetector
import com.muse.gadget.portal.ble.NativePairingPeripheral
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Foreground voice input: push-to-talk, or session-only opt-in local wake. No history is persisted. */
class PortalActivity : Activity() {
    private lateinit var store: DeviceStore
    private lateinit var connection: MuseConnection
    private lateinit var ui: PortalDashboard
    private val voiceBubbles = LinkedHashMap<String, TextView>()
    private var pairing: NativePairingPeripheral? = null
    private var tts: TextToSpeech? = null
    private var offlineSpeech = false
    private var speechSerial = 0
    @Volatile private var speechTail: String? = null
    @Volatile private var recording = false
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var foreground = false
    @Volatile private var cancelled = false
    private var capture: Thread? = null
    private val captureGeneration = CaptureGeneration()
    private val camera = PortalCamera()
    private val imageFetcher = PortalImageFetcher()
    private val displayGuard = com.muse.gadget.display.DisplayGuard()
    @Volatile private var imageBusy = false
    private var cameraBusy = false
    private var localBridge: LocalPortalBridge? = null
    private val privacyHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val clearVisible = Runnable { clearConversation() }
    private var connected = false
    private lateinit var addressed: AddressedReplies
    private lateinit var optionGate: ReplyOptionsGate
    private lateinit var optionView: ReplyOptionsView
    private var addressedSpeech: String? = null
    private lateinit var wakeDetector: VoskWakeWordDetector
    private val wakePolicy = WakeSessionPolicy()
    private var wakeEnabled = false // Session-only explicit consent; never enable on boot/restart.
    private var pttHeld = false
    private var wakeHandoff = false
    private val wakeTick = object : Runnable {
        override fun run() {
            syncWakeListening()
            if (foreground) privacyHandler.postDelayed(this, 500)
        }
    }

    private fun pauseWake() {
        wakePolicy.revoke(android.os.SystemClock.elapsedRealtime())
        if (::wakeDetector.isInitialized) wakeDetector.stop()
    }

    private fun cancelCapture() {
        captureGeneration.revoke()
        wakeHandoff = false
        cancelled = true
        recording = false
        try { recorder?.stop() } catch (_: Exception) { }
        if (::ui.isInitialized) {
            ui.talkButton.setListening(false)
            ui.micState.text = "Microphone off"
        }
    }

    private fun syncWakeListening() {
        if (!::wakeDetector.isInitialized || !::ui.isInitialized) return
        val muted = (getSystemService(AUDIO_SERVICE) as AudioManager).isMicrophoneMute
        if (muted && (recording || wakeHandoff)) cancelCapture()
        wakePolicy.update(wakeEnabled, foreground, connected,
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            muted, recording || capture?.isAlive == true || tts?.isSpeaking == true || speechTail != null || pttHeld || wakeHandoff)
        val lease = wakePolicy.lease(android.os.SystemClock.elapsedRealtime())
        ui.wakeButton.text = if (!wakeEnabled) "Wake off" else if (lease == null) "Wake paused"
            else if (wakeDetector.isListening) "Wake on" else "Wake starting"
        if (lease == null) { wakeDetector.stop(); return }
        wakeDetector.start({ score ->
            // Re-evaluate conditions at delivery, not just when microphone work began.
            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
            wakePolicy.update(wakeEnabled, foreground, connected,
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                audio.isMicrophoneMute, recording || capture?.isAlive == true || tts?.isSpeaking == true || speechTail != null || pttHeld || wakeHandoff)
            if (wakePolicy.claim(lease, score, android.os.SystemClock.elapsedRealtime())) {
                wakeHandoff = true
                startRecording(handsFree = true)
            }
        }, { error ->
            wakeEnabled = false
            ui.wakeButton.text = "Wake off"
            ui.micState.text = error
        })
    }

    private fun toggleWake() {
        if (wakeEnabled) {
            wakeEnabled = false
            cancelCapture()
            pauseWake()
            ui.wakeButton.text = "Wake off"
            ui.micState.text = "Wake listening off · Hold to talk still works"
            return
        }
        if (!wakeDetector.available()) { status("Wake model not packaged; use Hold to talk."); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
            status("Grant microphone permission, then choose Wake again.")
            return
        }
        AlertDialog.Builder(this).setTitle("Enable local Nova wake word?")
            .setMessage("While this app is open, the microphone checks for Nova on this device. Say Nova, pause until the listening indicator appears, then speak. Only the following voice note is sent to Muse. Wake matching does not identify who is speaking. Physical mute and leaving the app stop listening; restart leaves it off. This prototype still needs room-noise and distance testing.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Enable") { _, _ -> wakeEnabled = true; syncWakeListening() }
            .show()
    }

    /** Clearing, leaving or reconnecting revokes images still in flight. */
    private fun revokeImages() {
        displayGuard.revoke()
        imageFetcher.cancel()
    }

    private fun clearConversation() {
        if (::addressed.isInitialized) addressed.revoke(foreground)
        if (::optionGate.isInitialized) optionGate.clear()
        if (::optionView.isInitialized) optionView.clear()
        addressedSpeech = null
        cancelCapture()
        pauseWake()
        revokeImages()
        if (::ui.isInitialized) {
            ui.clearConversation()
            ui.messageInput.text.clear()
        }
        voiceBubbles.clear()
        speechTail = null
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
        addressed = AddressedReplies(store.nodeId)
        optionGate = ReplyOptionsGate(store.nodeId, "existing-main")
        optionView = ReplyOptionsView(this)
        java.io.File(noBackupFilesDir, "identity-public.json").writeText(
            JSONObject().put("mac", store.mac).put("node_id", store.nodeId).toString(),
        )

        ui = PortalDashboard(this)
        wakeDetector = VoskWakeWordDetector(this)
        setContentView(ui)
        bindUiActions()

        connection = MuseConnection(
            store,
            ::status,
            { text -> runOnUiThread { if (foreground) appendMessage("Muse", text) } },
            { ready -> runOnUiThread {
                if (!ready) {
                    revokeImages(); cancelCapture(); pauseWake()
                    addressed.revoke(foreground); optionGate.clear(); optionView.clear()
                    addressedSpeech = null; speechTail = null; tts?.stop()
                }
                connected = ready
                ui.setConnectionState(ready)
                ui.deviceStatus.show(
                    if (ready) "READY" else "CONNECTING",
                    if (ready) "Hold to talk. Your history stays off this screen." else "Opening the secure connection",
                )
            } },
            { id, text -> runOnUiThread {
                voiceBubbles[id]?.text = text
                addressed.transcript(id, text)
                android.util.Log.i("MusePortalState", "Voice transcript received and displayed.")
            } },
            { done -> runOnUiThread { takePhoto("Muse", done) } },
            { text -> runOnUiThread { if (foreground && !recording) speak(text) } },
            { url, done -> runOnUiThread { showImage(url, done) } },
            { runOnUiThread { revokeImages(); ui.clearImages() } },
            { id, nativeId -> runOnUiThread { addressed.acknowledge(id, nativeId) } },
            { delivery -> runOnUiThread {
                if (foreground && connected && (delivery.display || delivery.speak)) {
                    val grant = addressed.acceptNative(delivery.inputMessageId, delivery.final)
                    if (grant != null) {
                        if (delivery.display) appendMessage("Muse", delivery.text)
                        if (delivery.speak && grant.speak && !recording) speak(delivery.text)
                    }
                }
            } },
        )
        localBridge = LocalPortalBridge(this,
            { done -> runOnUiThread { takePhoto("Muse", done) } }, ::bridgeDelivery)
        initializeTextToSpeech()
        diagnostics()
        importProvisioningIfPresent()
    }

    private fun bindUiActions() {
        ui.wakeButton.setOnClickListener { toggleWake() }
        ui.settingsButton.setOnClickListener { showSettings() }
        ui.clearButton.setOnClickListener { clearConversation() }
        ui.stopAudioButton.setOnClickListener {
            addressed.stopAudio()
            addressedSpeech = null
            cancelCapture()
            pauseWake()
            speechTail = null
            tts?.stop()
            ui.setAudioState("AUDIO STOPPED", "Playback stopped", PortalPalette.TEXT_SECONDARY)
        }
        ui.cameraButton.setOnClickListener {
            takePhoto("You") { bytes, error ->
                if (bytes != null) {
                    val id = java.util.UUID.randomUUID().toString()
                    if (beginLocal(id, AddressedReplies.Kind.PHOTO)) connection.sendPhoto(bytes, id)
                } else status(error ?: "Camera unavailable")
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
                    pttHeld = true
                    ui.talkButton.setTouchPressed(true)
                    startRecording()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    pttHeld = false
                    ui.talkButton.setTouchPressed(false)
                    recording = false
                    ui.talkButton.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pttHeld = false
                    ui.talkButton.setTouchPressed(false)
                    cancelCapture()
                    true
                }
                else -> true
            }
        }
    }

    private fun sendTypedMessage() {
        val text = ui.messageInput.text.toString().trim()
        if (text.isEmpty()) return
        if (sendLocalText(text)) ui.messageInput.text.clear()
    }

    private fun beginLocal(id: String, kind: AddressedReplies.Kind, text: String? = null, hash: String? = null): Boolean {
        if (!foreground || !connected) { status("Not connected. Nothing sent."); return false }
        return try { addressed.begin(id, kind, text, hash); true }
        catch (_: AddressedReplies.Rejected) { status("Clear the conversation before sending another input."); false }
    }

    private fun sendLocalText(text: String): Boolean {
        val id = java.util.UUID.randomUUID().toString()
        if (!beginLocal(id, AddressedReplies.Kind.TEXT, text)) return false
        appendMessage("You", text)
        connection.sendText(text, id)
        return true
    }

    /** One UI-thread transaction prevents Clear/background from racing a queued remote render. */
    private fun bridgeDelivery(path: String, args: JSONObject): JSONObject {
        val done = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        var result: JSONObject? = null
        var error: AddressedReplies.Rejected? = null
        val deadline = android.os.SystemClock.elapsedRealtime() + 3000
        runOnUiThread {
            try {
                if (cancelled.get() || android.os.SystemClock.elapsedRealtime() >= deadline) throw AddressedReplies.Rejected("delivery_timeout_query_same_id")
                if (!foreground || !connected) throw AddressedReplies.Rejected("not_foreground_connected")
                result = when (path) {
                    "/requests/read" -> {
                        val selector = AddressedDeliveryJson.selector(args)
                        AddressedDeliveryJson.canonical(addressed.read(selector.first, selector.second)).put("ok", true)
                    }
                    "/delivery/status" -> AddressedDeliveryJson.receipt(addressed.receipt(AddressedDeliveryJson.deliveryId(args))).put("ok", true)
                    "/deliver" -> {
                        val envelope = AddressedDeliveryJson.envelope(args)
                        val accepted = addressed.accept(envelope, recording || capture?.isAlive == true || wakeHandoff)
                        if (!accepted.duplicate) {
                            accepted.text?.let { appendMessage("Muse", it) }
                            accepted.options?.let { labels ->
                                optionGate.openRequest(accepted.requestId)
                                val card = optionGate.present(ReplyOptionsGate.Delivery(envelope.deliveryId,
                                    store.nodeId, "existing-main", accepted.requestId, labels))
                                if (card is ReplyOptionsGate.Presentation.Presented) {
                                    ui.appendOptions(optionView)
                                    optionView.render(card.card.copy(expiresAtMs = minOf(card.card.expiresAtMs, accepted.expiresAtMs))) { token, index, label ->
                                        if (foreground && connected) {
                                            val selected = optionGate.select(token, index, label)
                                            if (selected is ReplyOptionsGate.Selection.Selected) sendLocalText(selected.text)
                                        }
                                    }
                                    privacyHandler.removeCallbacks(clearVisible)
                                    privacyHandler.postDelayed(clearVisible, 120000)
                                }
                            }
                            addressed.rendered(envelope.deliveryId)
                            if (accepted.speak) accepted.text?.let { speak(it, envelope.deliveryId) }
                        }
                        AddressedDeliveryJson.receipt(addressed.receipt(envelope.deliveryId)).put("ok", true).put("duplicate", accepted.duplicate)
                    }
                    else -> throw AddressedReplies.Rejected("unknown_endpoint")
                }
            } catch (e: AddressedReplies.Rejected) { error = e }
              catch (_: Exception) { error = AddressedReplies.Rejected("delivery_failed_query_same_id") }
            finally { done.countDown() }
        }
        if (!done.await(4, java.util.concurrent.TimeUnit.SECONDS)) {
            cancelled.set(true); throw AddressedReplies.Rejected("delivery_timeout_query_same_id")
        }
        error?.let { throw it }
        return result ?: throw AddressedReplies.Rejected("delivery_failed_query_same_id")
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
                    if (id == null || id.endsWith("-pause")) return
                    runOnUiThread {
                        if (!isCurrentSpeech(id)) return@runOnUiThread
                        addressedSpeech?.let { addressed.speech(it, "started") }
                        ui.setAudioState("SPEAKING", "On-device voice · Tap Stop to interrupt", PortalPalette.LILAC)
                        ui.deviceStatus.show("SPEAKING", "On-device voice · Tap Stop to interrupt")
                    }
                    if (id.endsWith("-0")) android.util.Log.i("MusePortalState", "Speech playback started.")
                }

                override fun onDone(id: String?) {
                    runOnUiThread {
                        if (id == null || id != speechTail) return@runOnUiThread
                        addressedSpeech?.let { addressed.speech(it, "completed") }
                        addressedSpeech = null
                        speechTail = null
                        pauseWake() // Let the speaker/room tail settle before rearming.
                        ui.setAudioState("SPEAKER READY", "On-device voice is ready", PortalPalette.LILAC)
                        ui.deviceStatus.show(if (connected) "READY" else "OFFLINE", "Hold to talk")
                    }
                    android.util.Log.i("MusePortalState", "Speech playback completed.")
                }

                override fun onStop(id: String?, interrupted: Boolean) {
                    runOnUiThread {
                        if (!isCurrentSpeech(id)) return@runOnUiThread
                        addressedSpeech?.let { addressed.speech(it, "cancelled") }
                        addressedSpeech = null
                        speechTail = null
                        pauseWake()
                        if (!recording) ui.deviceStatus.show(if (connected) "READY" else "OFFLINE", "Audio stopped")
                    }
                }

                @Deprecated("Android legacy callback")
                override fun onError(id: String?) {
                    runOnUiThread {
                        if (!isCurrentSpeech(id)) return@runOnUiThread
                        addressedSpeech?.let { addressed.speech(it, "failed") }
                        addressedSpeech = null
                        speechTail = null
                        pauseWake()
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
        addressed.setForeground(true)
        privacyHandler.removeCallbacks(wakeTick)
        privacyHandler.post(wakeTick)
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

    /** Checked on the UI thread so late callbacks cannot reset a newer reply. */
    private fun isCurrentSpeech(id: String?): Boolean =
        speechTail != null && id?.startsWith("speech-$speechSerial-") == true

    private fun showImage(url: String, done: (JSONObject?, JSONObject?) -> Unit) {
        fun error(code: String, message: String) = JSONObject().put("code", code).put("message", message)
        if (!foreground) { done(null, error("not_foreground", "Open the Portal app before showing an image.")); return }
        if (imageBusy) { done(null, error("busy", "An image is already being shown; try again in a moment.")); return }
        imageBusy = true
        val token = displayGuard.begin()
        Thread({
            try {
                val shown = imageFetcher.fetch(url)
                runOnUiThread {
                    imageBusy = false
                    if (!foreground || !displayGuard.valid(token)) {
                        shown.bitmap.recycle()
                        done(null, error("cancelled", "The Portal left the foreground or was cleared before the image arrived; nothing was shown."))
                        return@runOnUiThread
                    }
                    privacyHandler.removeCallbacks(clearVisible)
                    privacyHandler.postDelayed(clearVisible, 120000)
                    ui.appendPhoto("Muse", shown.bitmap, "MUSE · IMAGE ${shown.width}×${shown.height}", 520)
                    android.util.Log.i("MusePortalState", "Image shown; format=${shown.format}; bytes=${shown.bytes}; scale=${shown.scale}")
                    done(JSONObject().put("format", shown.format).put("width", shown.width).put("height", shown.height)
                        .put("scale", shown.scale).put("bytes", shown.bytes).put("ms", shown.ms), null)
                }
            } catch (e: PortalImageFetcher.Failure) {
                imageBusy = false
                done(null, error(e.code, e.message ?: "Image could not be shown."))
            } catch (_: Exception) {
                imageBusy = false
                done(null, error("display_failed", "Image could not be shown."))
            }
        }, "portal-image").start()
    }

    /** Only called for an already-authorized reply or the local speaker test. */
    private fun speak(text: String, deliveryId: String? = null) {
        wakeHandoff = false
        pauseWake()
        addressedSpeech?.let { addressed.speech(it, "cancelled") }
        addressedSpeech = deliveryId
        if (!offlineSpeech) {
            deliveryId?.let { addressed.speech(it, "unavailable") }
            addressedSpeech = null
            ui.setAudioState("AUDIO UNAVAILABLE", "No offline English voice found", PortalPalette.DANGER)
            return
        }
        val engine = tts ?: run { deliveryId?.let { addressed.speech(it, "unavailable") }; addressedSpeech = null; return }
        val script = SpeechScript.plan(text, minOf(SpeechScript.MAX_UTTERANCE_CHARS, TextToSpeech.getMaxSpeechInputLength()))
        if (script.isEmpty()) { deliveryId?.let { addressed.speech(it, "unavailable") }; addressedSpeech = null; return }
        engine.stop()
        val serial = ++speechSerial
        speechTail = "speech-$serial-${script.lastIndex}"
        for ((index, utterance) in script.withIndex()) {
            val id = "speech-$serial-$index"
            engine.setSpeechRate(utterance.rate)
            engine.setPitch(utterance.pitch)
            if (engine.speak(utterance.text, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.ERROR) {
                speechTail = null
                deliveryId?.let { addressed.speech(it, "failed") }
                addressedSpeech = null
                ui.setAudioState("SPEECH FAILED", "Check audio settings", PortalPalette.DANGER)
                return
            }
            if (utterance.pauseAfterMs > 0) engine.playSilentUtterance(utterance.pauseAfterMs.toLong(), TextToSpeech.QUEUE_ADD, "$id-pause")
        }
        android.util.Log.i("MusePortalState", "Speech script queued; utterances=${script.size}")
        deliveryId?.let { addressed.speech(it, "queued") }
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
            addressed.stopAudio(); addressedSpeech = null
            pauseWake()
            cancelCapture()
            speechTail = null
            tts?.stop()
        }
        option("Reconnect", "Open a fresh secure connection to Muse") { connection.connect() }
        option("Pair device", "Pair this Portal with your existing Muse account") { showPairing() }
        option("Diagnostics", "Show Bluetooth and voice capability status") { diagnostics() }
        option(if (ui.typingControlsVisible) "Hide typing controls" else "Show typing controls",
            "Optional local input; voice and touch are the default") { ui.setTypingControlsVisible(!ui.typingControlsVisible) }
        val close = PortalActionButton(this, "Close", PortalActionTone.QUIET).apply { setOnClickListener { dialog.dismiss() } }
        card.addView(close, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)).apply {
            gravity = Gravity.END
        })
        dialog.setContentView(ScrollView(this).apply { addView(card) })
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

    private fun startRecording(handsFree: Boolean = false) {
        if (!foreground || !connected || (!handsFree && !pttHeld) || (handsFree && (!wakeEnabled || !wakeHandoff))) return
        if ((getSystemService(AUDIO_SERVICE) as AudioManager).isMicrophoneMute) { wakeHandoff = false; return }
        pauseWake()
        // Wait for the monitor to release AudioRecord rather than opening two recorders.
        if (wakeDetector.isRunning) {
            privacyHandler.postDelayed({ startRecording(handsFree) }, 75)
            return
        }
        if (capture?.isAlive == true) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
            wakeHandoff = false
            return
        }
        wakeHandoff = false
        addressed.stopAudio(); addressedSpeech = null
        speechTail = null
        tts?.stop()
        cancelled = false
        recording = true
        val captureToken = captureGeneration.begin()
        ui.talkButton.setListening(true, handsFree)
        val captureHint = if (handsFree) "Speak now · Ends after a pause · Maximum 15 seconds" else "Release to send · Maximum 15 seconds"
        ui.deviceStatus.show("LISTENING", captureHint)
        ui.micState.text = "Microphone on · $captureHint"
        capture = Thread {
            var local: AudioRecord? = null
            try {
                if (!foreground || cancelled || !captureGeneration.valid(captureToken)) return@Thread
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
                if (!foreground || cancelled || !captureGeneration.valid(captureToken) ||
                    (getSystemService(AUDIO_SERVICE) as AudioManager).isMicrophoneMute) return@Thread
                local.startRecording()
                val pcm = ByteArrayOutputStream()
                val endpoint = if (handsFree) WakeUtteranceEndpoint() else null
                val block = ByteArray(2048)
                while (recording && foreground && captureGeneration.valid(captureToken) && pcm.size() < 480000) {
                    val count = local.read(block, 0, minOf(block.size, 480000 - pcm.size()))
                    check(count >= 0)
                    if (count > 0) pcm.write(block, 0, count)
                    if (endpoint?.accept(block, count) == true) recording = false
                    var peak = 0
                    for (index in 0 until count - 1 step 2) {
                        val sample = ((block[index].toInt() and 255) or (block[index + 1].toInt() shl 8)).toShort().toInt()
                        peak = maxOf(peak, kotlin.math.abs(sample))
                    }
                    val level = peak / 32768f
                    val progress = pcm.size() / 480000f
                    runOnUiThread {
                        if (recording && captureGeneration.valid(captureToken)) ui.deviceStatus.show("LISTENING", captureHint, level, progress)
                    }
                }
                if (!cancelled && foreground && pcm.size() >= 1600 && (endpoint == null || endpoint.hasSpeech)) {
                    val data = pcm.toByteArray()
                    val header = VoiceNoteEncoder.wavHeader()
                    java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(4, data.size + 36).putInt(40, data.size)
                    val id = java.util.UUID.randomUUID().toString()
                    runOnUiThread {
                        if (!foreground || !connected || cancelled || !captureGeneration.valid(captureToken) ||
                            (handsFree && !wakeEnabled) ||
                            (getSystemService(AUDIO_SERVICE) as AudioManager).isMicrophoneMute ||
                            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@runOnUiThread
                        if (!beginLocal(id, AddressedReplies.Kind.VOICE, hash = AddressedReplies.uploadHash(header + data))) return@runOnUiThread
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
                if (captureGeneration.valid(captureToken) && !cancelled) status("Microphone capture failed; no recording sent.")
            } finally {
                recording = false
                try {
                    local?.stop()
                } catch (_: Exception) {
                }
                local?.release()
                recorder = null
                runOnUiThread {
                    if (!captureGeneration.valid(captureToken)) return@runOnUiThread
                    ui.talkButton.setListening(false)
                    ui.micState.text = "Microphone off · Hold to speak, then release to send"
                }
            }
        }.also { it.start() }
    }

    override fun onStop() {
        foreground = false
        pttHeld = false
        wakeHandoff = false
        // Returning to the foreground requires a fresh opt-in, never a hidden rearm.
        wakeEnabled = false
        privacyHandler.removeCallbacks(wakeTick)
        pauseWake()
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
