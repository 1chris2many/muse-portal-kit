package com.muse.gadget.portal

import android.app.Application
import android.os.Looper
import com.muse.gadget.session.InlineMedia
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PortalAudioPlayerTest {
    private class FakeEngine(var millis: Int = 1000) : PortalAudioPlayer.Engine {
        override val duration get(): Int {
            if (failDuration) throw IllegalStateException("fixture duration failure")
            return millis
        }
        lateinit var ready: () -> Unit
        lateinit var completed: () -> Unit
        lateinit var failed: () -> Unit
        var starts = 0
        var closes = 0
        var input: ByteArray? = null
        var failPrepare = false
        var failStart = false
        var failDuration = false
        override fun prepare(bytes: ByteArray, ready: () -> Unit, completed: () -> Unit, failed: () -> Unit) {
            input = bytes
            this.ready = ready; this.completed = completed; this.failed = failed
            if (failPrepare) throw IllegalStateException("fixture prepare failure")
        }
        override fun start() { if (failStart) throw IllegalStateException("fixture start failure"); starts++ }
        override fun close() { closes++; input?.fill(0) }
    }
    private fun bytes() = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0)
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun alreadyRevokedInputIsErasedWithoutCreatingPlayer() {
        var creates = 0
        val player = PortalAudioPlayer { creates++; FakeEngine() }
        val audio = bytes(); val states = mutableListOf<String>()
        player.play(audio, { false }, states::add)
        assertEquals(0, creates); assertFalse(player.busy)
        assertEquals(listOf("cancelled"), states); assertTrue(audio.all { it == 0.toByte() })
    }

    @Test fun malformedAndOversizeAudioNeverPrepares() {
        for (audio in listOf(byteArrayOf(), "not audio".toByteArray(), ByteArray(InlineMedia.MAX_BYTES + 1))) {
            var creates = 0
            val player = PortalAudioPlayer { creates++; FakeEngine() }
            val states = mutableListOf<String>()
            player.play(audio, { true }, states::add)
            assertEquals(0, creates); assertEquals(listOf("cancelled"), states)
            assertTrue(audio.all { it == 0.toByte() }); assertFalse(player.busy)
        }
    }

    @Test fun revokeDuringPrepareCannotStartPlayback() {
        val engine = FakeEngine(); val player = PortalAudioPlayer { engine }
        var valid = true; val states = mutableListOf<String>()
        player.play(bytes(), { valid }, states::add)
        assertTrue(player.busy); assertEquals(listOf("queued"), states)
        valid = false; engine.ready()
        assertEquals(0, engine.starts); assertEquals(1, engine.closes)
        assertEquals(listOf("queued", "cancelled"), states); assertFalse(player.busy)
    }

    @Test fun invalidDurationsFailClosedBeforeStart() {
        for (duration in listOf(-1, 0, InlineMedia.MAX_AUDIO_MS + 1, Int.MAX_VALUE)) {
            val engine = FakeEngine(duration); val player = PortalAudioPlayer { engine }
            val states = mutableListOf<String>()
            player.play(bytes(), { true }, states::add); engine.ready()
            assertEquals(0, engine.starts); assertEquals(1, engine.closes)
            assertEquals(listOf("queued", "failed"), states); assertFalse(player.busy)
        }
    }

    @Test fun durationBoundaryStartsAndStopRevokesOnce() {
        val engine = FakeEngine(InlineMedia.MAX_AUDIO_MS); val player = PortalAudioPlayer { engine }
        val audio = bytes(); val states = mutableListOf<String>()
        player.play(audio, { true }, states::add); engine.ready()
        assertEquals(1, engine.starts); assertTrue(player.busy)
        player.stop(); player.stop(); engine.completed(); engine.failed()
        assertEquals(1, engine.closes); assertFalse(player.busy)
        assertEquals(listOf("queued", "started", "cancelled"), states)
        assertTrue(audio.all { it == 0.toByte() })
    }

    @Test fun stopWhilePreparingMakesQueuedReadyHarmless() {
        val engine = FakeEngine(); val player = PortalAudioPlayer { engine }
        val states = mutableListOf<String>()
        player.play(bytes(), { true }, states::add)
        shadowOf(Looper.getMainLooper()).pause()
        android.os.Handler(Looper.getMainLooper()).post { engine.ready() }
        player.stop(); advance(1)
        assertEquals(0, engine.starts); assertEquals(1, engine.closes)
        assertEquals(listOf("queued", "cancelled"), states)
    }

    @Test fun supersededCallbacksCannotAffectNewPlayerOrItsTimeout() {
        val old = FakeEngine(); val fresh = FakeEngine()
        val engines = ArrayDeque(listOf(old, fresh)); val player = PortalAudioPlayer { engines.removeFirst() }
        val oldStates = mutableListOf<String>(); val newStates = mutableListOf<String>()
        player.play(bytes(), { true }, oldStates::add)
        player.play(bytes(), { true }, newStates::add)
        old.ready(); old.completed(); old.failed()
        assertTrue(player.busy); assertEquals(0, old.starts); assertEquals(0, fresh.closes)
        assertEquals(listOf("queued", "cancelled"), oldStates)
        advance(5001)
        assertEquals(listOf("queued", "failed"), newStates); assertEquals(1, fresh.closes)
    }

    @Test fun stalledPrepareTimesOutAndLateReadyCannotStart() {
        val engine = FakeEngine(); val player = PortalAudioPlayer { engine }
        val states = mutableListOf<String>()
        player.play(bytes(), { true }, states::add); advance(4999)
        assertTrue(player.busy); advance(2)
        engine.ready()
        assertEquals(0, engine.starts); assertEquals(1, engine.closes)
        assertEquals(listOf("queued", "failed"), states); assertFalse(player.busy)
    }

    @Test fun activePlaybackIsStoppedWhenLeaseExpires() {
        val engine = FakeEngine(60_000); val player = PortalAudioPlayer { engine }
        var valid = true; val states = mutableListOf<String>()
        player.play(bytes(), { valid }, states::add); engine.ready()
        valid = false; advance(251)
        assertEquals(listOf("queued", "started", "cancelled"), states)
        assertEquals(1, engine.closes); assertFalse(player.busy)
    }

    @Test fun completionReleasesAndCancelsAllWatchdogs() {
        val engine = FakeEngine(); val player = PortalAudioPlayer { engine }
        val audio = bytes(); val states = mutableListOf<String>()
        player.play(audio, { true }, states::add); engine.ready(); engine.completed()
        advance(10_000); engine.failed()
        assertEquals(listOf("queued", "started", "completed"), states)
        assertEquals(1, engine.closes); assertFalse(player.busy)
        assertTrue(audio.all { it == 0.toByte() })
    }

    @Test fun missingCompletionHasFinitePlaybackWatchdog() {
        val engine = FakeEngine(1000); val player = PortalAudioPlayer { engine }
        val states = mutableListOf<String>()
        player.play(bytes(), { true }, states::add); engine.ready(); advance(3001)
        assertEquals(listOf("queued", "started", "failed"), states)
        assertEquals(1, engine.closes); assertFalse(player.busy)
    }

    @Test fun platformPrepareAndStartErrorsReleaseThePlayer() {
        for (failPrepare in listOf(true, false)) {
            val engine = FakeEngine().apply { this.failPrepare = failPrepare; failStart = !failPrepare }
            val player = PortalAudioPlayer { engine }; val audio = bytes(); val states = mutableListOf<String>()
            player.play(audio, { true }, states::add)
            if (!failPrepare) engine.ready()
            assertEquals(listOf("queued", "failed"), states)
            assertEquals(1, engine.closes); assertFalse(player.busy)
            assertTrue(audio.all { it == 0.toByte() })
        }
    }

    @Test fun platformCreationFailureCannotEscapeOrRetainAudio() {
        val player = PortalAudioPlayer { throw IllegalStateException("fixture media service unavailable") }
        val audio = bytes(); val states = mutableListOf<String>()
        player.play(audio, { true }, states::add)
        assertFalse(player.busy); assertEquals("failed", states.last())
        assertTrue(audio.all { it == 0.toByte() })
    }

    @Test fun platformDurationFailureCannotEscapePreparedCallback() {
        val engine = FakeEngine().apply { failDuration = true }
        val player = PortalAudioPlayer { engine }; val audio = bytes(); val states = mutableListOf<String>()
        player.play(audio, { true }, states::add); engine.ready()
        assertEquals(0, engine.starts); assertEquals(1, engine.closes); assertFalse(player.busy)
        assertEquals(listOf("queued", "failed"), states)
        assertTrue(audio.all { it == 0.toByte() })
    }

    @Test fun memorySourceExactRangesBoundariesAndErasure() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val source = PortalAudioPlayer.MemorySource(bytes)
        assertEquals(5L, source.size)
        val out = ByteArray(8) { 9 }
        assertEquals(3, source.readAt(2, out, 1, 6))
        assertArrayEquals(byteArrayOf(9, 3, 4, 5, 9, 9, 9, 9), out)
        assertEquals(-1, source.readAt(-1, out, 0, 1))
        assertEquals(-1, source.readAt(0, out, -1, 1))
        assertEquals(-1, source.readAt(0, out, 0, -1))
        assertEquals(-1, source.readAt(0, out, 7, 2))
        assertEquals(-1, source.readAt(0, out, Int.MAX_VALUE, 1))
        assertEquals(-1, source.readAt(Long.MAX_VALUE, out, 0, 1))
        assertEquals(-1, source.readAt(5, out, 0, 1))
        assertEquals(0, source.readAt(0, out, 8, 0))
        source.close(); source.close()
        assertEquals(0L, source.size); assertTrue(bytes.all { it == 0.toByte() })
        assertEquals(-1, source.readAt(0, out, 0, 1))
    }
}
