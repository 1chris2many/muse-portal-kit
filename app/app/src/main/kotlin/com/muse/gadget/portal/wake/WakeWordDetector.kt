package com.muse.gadget.portal.wake

/**
 * Historical prototype interface; not wired to the foreground app.
 * The opt-in prototype uses [VoskWakeWordDetector] with lifecycle gating in
 * [com.muse.gadget.portal.ui.PortalActivity]. No background VoiceService is started.
 */
interface WakeWordDetector {
    /** Detection threshold in [0, 1]; higher = fewer false wakes. Default 0.5. */
    fun setThreshold(threshold: Float)

    /** Starts listening; [onWake] fires on the main thread per detection. */
    fun start(onWake: (score: Float) -> Unit)

    /** Stops listening; safe to call when not started. */
    fun stop()

    fun release()
}
