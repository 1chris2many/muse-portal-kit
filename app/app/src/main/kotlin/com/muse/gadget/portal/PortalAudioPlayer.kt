package com.muse.gadget.portal

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.muse.gadget.session.InlineMedia

/** Bounded in-memory MP3 playback. No URL, file, network, cache, microphone or voice provider. */
class PortalAudioPlayer(private val factory: () -> Engine = { AndroidEngine() }) {
    interface Engine {
        val duration: Int
        fun prepare(bytes: ByteArray, ready: () -> Unit, completed: () -> Unit, failed: () -> Unit)
        fun start()
        fun close()
    }
    private data class Active(val engine: Engine, val valid: () -> Boolean, val state: (String) -> Unit)
    private val handler = Handler(Looper.getMainLooper())
    private var active: Active? = null
    val busy get() = active != null
    fun play(bytes: ByteArray, valid: () -> Boolean, state: (String) -> Unit) {
        stop()
        if (bytes.size !in 1..InlineMedia.MAX_BYTES || !InlineMedia.looksLikeMp3(bytes) || !valid()) {
            bytes.fill(0); state("cancelled"); return
        }
        val engine = try { factory() } catch (_: Exception) { bytes.fill(0); state("failed"); return }
        val entry = Active(engine, valid, state)
        active = entry
        state("queued")
        handler.postDelayed({ if (active === entry) finish(entry, "failed") }, 5000)
        try {
            entry.engine.prepare(bytes, {
                if (active !== entry) return@prepare
                handler.removeCallbacksAndMessages(null)
                try {
                    if (!entry.valid()) { finish(entry, "cancelled"); return@prepare }
                    val duration = entry.engine.duration
                    if (duration !in 1..InlineMedia.MAX_AUDIO_MS) { finish(entry, "failed"); return@prepare }
                    entry.engine.start(); entry.state("started")
                    watch(entry)
                    handler.postDelayed({ finish(entry, "failed") }, (duration + 2000).toLong())
                } catch (_: Exception) { finish(entry, "failed") }
            }, { finish(entry, "completed") }, { finish(entry, "failed") })
        } catch (_: Exception) { bytes.fill(0); finish(entry, "failed") }
    }
    private fun watch(entry: Active) {
        handler.postDelayed({
            if (active === entry) {
                if (!entry.valid()) finish(entry, "cancelled") else watch(entry)
            }
        }, 250)
    }
    private fun finish(entry: Active, state: String) {
        if (active !== entry) return
        active = null
        handler.removeCallbacksAndMessages(null)
        try { entry.engine.close() } catch (_: Exception) { }
        entry.state(state)
    }
    fun stop() { active?.let { finish(it, "cancelled") } }

    private class AndroidEngine : Engine {
        private val player = MediaPlayer()
        private var source: MemorySource? = null
        override val duration get() = player.duration
        override fun prepare(bytes: ByteArray, ready: () -> Unit, completed: () -> Unit, failed: () -> Unit) {
            source = MemorySource(bytes)
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            player.isLooping = false
            player.setOnPreparedListener { ready() }
            player.setOnCompletionListener { completed() }
            player.setOnErrorListener { _, _, _ -> failed(); true }
            player.setDataSource(source!!)
            player.prepareAsync()
        }
        override fun start() { player.start() }
        override fun close() { try { player.release() } finally { source?.close(); source = null } }
    }
    class MemorySource(private var bytes: ByteArray) : MediaDataSource() {
        @Synchronized override fun getSize(): Long = bytes.size.toLong()
        @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0 || offset < 0 || size < 0 || offset > buffer.size - size) return -1
            if (size == 0) return 0
            if (position >= bytes.size) return -1
            val count = minOf(size.toLong(), bytes.size - position).toInt()
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }
        @Synchronized override fun close() { bytes.fill(0); bytes = ByteArray(0) }
    }
}
