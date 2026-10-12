package com.muse.gadget.portal.wake

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.muse.gadget.audio.WakePhrase
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Foreground opt-in keyword monitor. PCM/results stay in memory and are never uploaded or logged. */
class VoskWakeWordDetector(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong()
    @Volatile private var worker: Thread? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var callbackPending = false
    val isRunning: Boolean get() = worker?.isAlive == true || callbackPending
    @Volatile var isListening: Boolean = false
        private set

    fun available(): Boolean = try { context.assets.open("wake-vosk/archive-sha256.txt").use { true } } catch (_: Exception) { false }

    /** Returns after scheduling; callback is after microphone release, on the UI thread. */
    fun start(onWake: (Double) -> Unit, onError: (String) -> Unit) {
        if (isRunning) return
        val token = generation.incrementAndGet()
        worker = Thread({
            var capture: AudioRecord? = null
            var score: Double? = null
            var error: String? = null
            try {
                check(available()) { "model_missing" }
                val root = prepareModel()
                if (generation.get() != token) return@Thread
                Model(root.absolutePath).use { model ->
                    // A two-word Nova/unknown grammar mishears "no" and "never" as Nova.
                    // Decode locally with the full vocabulary, then require an exact one-word match.
                    Recognizer(model, 16000f).use { recognizer ->
                        recognizer.setWords(true)
                        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                        check(minimum > 0)
                        if (generation.get() != token) return@use
                        capture = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 6400))
                        recorder = capture
                        check(capture!!.state == AudioRecord.STATE_INITIALIZED)
                        if (generation.get() != token) return@use
                        capture!!.startRecording()
                        isListening = true
                        val pcm = ShortArray(1600)
                        while (generation.get() == token) {
                            val count = capture!!.read(pcm, 0, pcm.size)
                            check(count > 0)
                            if (recognizer.acceptWaveForm(pcm, count)) {
                                val result = JSONObject(recognizer.result)
                                val words = result.optJSONArray("result")
                                val word = words?.takeIf { it.length() == 1 }?.optJSONObject(0)
                                if (word != null && WakePhrase.accepts(result.optString("text"), word.optString("word"),
                                        word.optDouble("conf"), word.optDouble("end") - word.optDouble("start"))) {
                                    score = word.optDouble("conf")
                                    break
                                }
                            }
                            pcm.fill(0)
                        }
                        pcm.fill(0)
                    }
                }
            } catch (_: SecurityException) {
                error = "Microphone permission required"
            } catch (_: Exception) {
                error = "Wake listening unavailable; use Hold to talk"
            } catch (_: LinkageError) {
                error = "Offline wake engine unavailable on this device"
            } finally {
                try { capture?.stop() } catch (_: Exception) { }
                capture?.release()
                recorder = null
                isListening = false
                // A stale callback cannot activate recording after disable/background/PTT.
                // Keep start() from racing ahead and invalidating a queued valid detection.
                callbackPending = true
                main.post {
                    try {
                        if (generation.get() == token) {
                            if (score != null) onWake(score!!) else if (error != null) onError(error!!)
                        }
                    } finally {
                        callbackPending = false
                    }
                }
            }
        }, "portal-wake-local").also { it.start() }
    }

    fun stop() {
        generation.incrementAndGet()
        try { recorder?.stop() } catch (_: Exception) { }
    }

    /** Only trusted bundled assets are copied; never a user path or network request. */
    private fun prepareModel(): File {
        val root = File(context.noBackupFilesDir, "wake-vosk-30f26242")
        val marker = File(root, ".complete")
        if (marker.isFile) return root
        fun copy(asset: String, target: File) {
            val children = context.assets.list(asset).orEmpty()
            if (children.isNotEmpty()) {
                check(target.isDirectory || target.mkdirs())
                children.forEach { copy("$asset/$it", File(target, it)) }
            } else {
                target.parentFile?.mkdirs()
                context.assets.open(asset).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
        copy("wake-vosk", root)
        marker.writeText("verified bundled model copied\n")
        return root
    }
}
