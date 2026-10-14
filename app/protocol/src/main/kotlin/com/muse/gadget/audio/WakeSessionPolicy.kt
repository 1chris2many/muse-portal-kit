package com.muse.gadget.audio

import java.util.concurrent.atomic.AtomicLong

/** Capture ownership across background audio and queued UI work. A new capture never revives old PCM. */
class CaptureGeneration {
    private val value = AtomicLong()
    fun begin(): Long = value.incrementAndGet()
    fun revoke() { value.incrementAndGet() }
    fun valid(token: Long): Boolean = value.get() == token
}

/** Local consent/lifecycle gate. No microphone, persistence, clock, or network side effects. */
class WakeSessionPolicy(private val cooldownMs: Long = 3000) {
    private var generation = 0L
    private var eligible = false
    private var notBefore = 0L

    fun update(enabled: Boolean, foreground: Boolean, connected: Boolean, permission: Boolean,
               muted: Boolean, busy: Boolean) {
        val next = enabled && foreground && connected && permission && !muted && !busy
        if (eligible != next) generation++
        eligible = next
    }

    fun lease(nowMs: Long): Long? = generation.takeIf { eligible && nowMs >= notBefore }

    fun claim(lease: Long, confidence: Double, nowMs: Long): Boolean {
        if (lease != lease(nowMs) || !confidence.isFinite() || confidence !in 0.90..1.0) return false
        generation++
        notBefore = nowMs + cooldownMs
        return true
    }

    fun revoke(nowMs: Long) {
        generation++
        notBefore = nowMs + cooldownMs
    }
}

/** Deliberately exact: no Nova substrings, synonyms, aliases or unknown-word guesses. */
object WakePhrase {
    fun accepts(text: String, word: String, confidence: Double, durationSeconds: Double): Boolean =
        text.trim().equals("nova", ignoreCase = true) && word.equals("nova", ignoreCase = true) &&
            confidence.isFinite() && confidence in 0.90..1.0 && durationSeconds.isFinite() &&
            durationSeconds in 0.12..1.8
}

/** Post-wake utterance endpoint; empty wake-ups never become messages. PCM is held only in RAM. */
class WakeUtteranceEndpoint {
    private var samples = 0L
    private var lastSpeechSample = 0L
    private var voicedSamples = 0L
    val hasSpeech: Boolean get() = voicedSamples >= 3200 // 200ms above the conservative energy floor

    fun accept(pcm: ByteArray, length: Int): Boolean {
        require(length in 0..pcm.size && length % 2 == 0)
        var energy = 0.0
        for (i in 0 until length step 2) {
            val value = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toDouble()
            energy += value * value
        }
        val count = length / 2
        samples += count
        if (count > 0 && energy / count >= 350.0 * 350.0) {
            voicedSamples += count
            lastSpeechSample = samples
        }
        return samples >= 240000 || (!hasSpeech && samples >= 80000) ||
            (hasSpeech && samples - lastSpeechSample >= 19200)
    }
}
