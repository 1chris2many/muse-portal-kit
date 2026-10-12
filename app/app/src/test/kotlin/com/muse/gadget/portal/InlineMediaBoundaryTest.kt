package com.muse.gadget.portal

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import com.muse.gadget.portal.ui.PortalActivity
import com.muse.gadget.session.AddressedReplies
import com.muse.gadget.session.InlineMedia
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class InlineMediaBoundaryTest {
    private fun root(vararg content: JSONObject) = JSONObject().put("version", 1)
        .put("delivery_id", "delivery").put("device_id", "device").put("conversation_id", "existing-main")
        .put("generation", "g").put("request_id", "request").put("input_message_id", "native")
        .put("content", JSONArray(content.toList()))
    private fun part(type: String, mime: String, bytes: ByteArray) = JSONObject()
        .put("type", type).put("mime_type", mime).put("data_base64", Base64.getEncoder().encodeToString(bytes))
    private fun png(width: Int = 8, height: Int = 6): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)
        return try { ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray() }
        finally { bitmap.recycle() }
    }
    private fun reject(block: () -> Unit) {
        try { block(); fail("Malformed media must be rejected") }
        catch (_: AddressedReplies.Rejected) { }
    }
    private fun decode(part: JSONObject) = AddressedDeliveryJson.prepare(root(part))

    @Test fun actualPngDecodedAndHasImmutableOriginalByteIdentity() {
        val bytes = png(); val prepared = decode(part("image", "image/png", bytes))
        try {
            assertNotNull(prepared.image); assertEquals(8, prepared.image!!.width); assertEquals(6, prepared.image.height)
            assertNull(prepared.audio)
            assertEquals(AddressedReplies.uploadHash(bytes), prepared.envelope.image!!.sha256)
            assertEquals(bytes.size, prepared.envelope.image!!.bytes)
        } finally { prepared.discard() }
        assertTrue(prepared.image!!.isRecycled)
    }

    @Test fun sourceDimensionsAreCappedAndAcceptedImageIsDownsampled() {
        val accepted = decode(part("image", "image/png", png(4096, 4)))
        try { assertTrue(accepted.image!!.width <= 1280); assertTrue(accepted.image.height >= 1) }
        finally { accepted.discard() }
        reject { decode(part("image", "image/png", png(4097, 4))) }
        reject { decode(part("image", "image/png", png(4, 4097))) }
    }

    @Test fun wrongMimeAndSignatureOnlyFilesFailRealDecoder() {
        reject { decode(part("image", "image/jpeg", png())) }
        reject { decode(part("image", "image/png", byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10))) }
        reject { decode(part("image", "image/jpeg", byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0))) }
        reject { decode(part("image", "image/webp", "RIFF0000WEBPbad".toByteArray())) }
        reject { decode(part("image", "image/svg+xml", "<svg/>".toByteArray())) }
    }

    @Test fun imageAndAudioMimeCannotBeInterchanged() {
        reject { decode(part("audio", "image/png", png())) }
        reject { decode(part("image", "audio/mpeg", byteArrayOf(73, 68, 51, 4))) }
        reject { decode(part("audio", "audio/wav", "RIFF-WAVE".toByteArray())) }
        reject { decode(part("audio", "audio/mpeg", "not an MP3".toByteArray())) }
    }

    @Test fun noUrlsPathsIdsRemoteFlagsOrUnknownMediaFields() {
        for ((key, value) in listOf("url" to "https://example.com/a.png", "path" to "sandbox://file",
            "artifact_id" to "secret", "speak" to true, "callback" to "run", "html" to "<script/>")) {
            reject { decode(part("image", "image/png", png()).put(key, value)) }
        }
        reject { decode(JSONObject().put("type", "image").put("url", "https://example.com/a.png")) }
        reject { decode(part("video", "video/mp4", byteArrayOf(1))) }
    }

    @Test fun badAndNoncanonicalBase64AndOversizeNeverDecode() {
        for (encoded in listOf("", "%%%?", "aQ", "aR==", "aQ==\n", "____")) {
            reject { decode(JSONObject().put("type", "audio").put("mime_type", "audio/mpeg").put("data_base64", encoded)) }
        }
        reject { decode(part("audio", "audio/mpeg", ByteArray(InlineMedia.MAX_BYTES + 1))) }
        reject { decode(part("image", "image/png", ByteArray(InlineMedia.MAX_BYTES + 1))) }
        reject { decode(JSONObject().put("type", "audio").put("mime_type", "audio/mpeg").put("data_base64", 17)) }
    }

    @Test fun repeatedContentTypesFailAndFourDistinctTypesAreBounded() {
        val image = part("image", "image/png", png())
        val audio = part("audio", "audio/mpeg", byteArrayOf(73, 68, 51, 4))
        reject { AddressedDeliveryJson.prepare(root(image, image)) }
        reject { AddressedDeliveryJson.prepare(root(audio, audio)) }
        val prepared = AddressedDeliveryJson.prepare(root(image, audio,
            JSONObject().put("type", "text").put("text", "fixture"),
            JSONObject().put("type", "options").put("options", JSONArray().put("One"))))
        try { assertNotNull(prepared.image); assertNotNull(prepared.audio); assertEquals("fixture", prepared.envelope.text) }
        finally { prepared.discard() }
        assertTrue(prepared.audio!!.all { it == 0.toByte() }); assertTrue(prepared.image!!.isRecycled)
    }

    @Test fun audioIdentityBindsExactBytesAndDiscardErasesDecodedBuffer() {
        val first = byteArrayOf(73, 68, 51, 4, 1)
        val second = byteArrayOf(73, 68, 51, 4, 2)
        val a = decode(part("audio", "audio/mpeg", first)); val b = decode(part("audio", "audio/mpeg", second))
        try {
            assertArrayEquals(first, a.audio); assertEquals(AddressedReplies.uploadHash(first), a.envelope.audio!!.sha256)
            assertNotEquals(a.envelope.audio, b.envelope.audio)
        } finally { a.discard(); b.discard() }
        assertTrue(a.audio!!.all { it == 0.toByte() }); assertTrue(b.audio!!.all { it == 0.toByte() })
    }

    /** Entirely local fixture requests: no sendText/sendVoice/camera or Muse network call. */
    private fun queuedImageRace(expected: String, invalidate: (PortalActivity, AddressedReplies) -> Unit) {
        // Construct only: avoid starting Bluetooth, pairing, voice models or the real connection.
        // These rejected-transaction fixtures do not need onCreate's UI/transport dependencies.
        val activity = Robolectric.buildActivity(PortalActivity::class.java).get()
        fun field(name: String, value: Any) {
            PortalActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
        }
        val gate = AddressedReplies("device", nonce = { "g" }).apply {
            setForeground(true)
            begin("request", AddressedReplies.Kind.TEXT, "local fixture")
            acknowledge("request", "native")
            read(exactText = "local fixture")
        }
        field("addressed", gate); field("connected", true); field("foreground", true)
        val method = PortalActivity::class.java.getDeclaredMethod("bridgeDelivery", String::class.java, JSONObject::class.java)
            .apply { isAccessible = true }
        val caught = AtomicReference<Throwable?>()
        val worker = Thread {
            try { method.invoke(activity, "/deliver", root(part("image", "image/png", png()))) }
            catch (failure: java.lang.reflect.InvocationTargetException) { caught.set(failure.targetException) }
            catch (failure: Throwable) { caught.set(failure) }
        }
        try {
            worker.start()
            // PAUSED looper keeps delivery queued while the bridge worker waits for its UI transaction.
            val deadline = System.nanoTime() + 2_000_000_000L
            while (worker.isAlive && worker.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals("Fixture must queue, not render before invalidation", Thread.State.TIMED_WAITING, worker.state)
            invalidate(activity, gate)
            shadowOf(Looper.getMainLooper()).idle()
            worker.join(1000)
            assertFalse("Bridge worker must finish", worker.isAlive)
            val rejected = caught.get() as? AddressedReplies.Rejected
            assertNotNull("Expected rejected delivery, got ${caught.get()}", rejected)
            assertEquals(expected, rejected!!.code)
            if (expected == "display_revoked") {
                // Image-only clear rejects stale pixels without consuming the otherwise valid request.
                assertFalse(gate.accept(AddressedReplies.Envelope(1, "after-clear", "device", "existing-main", "g",
                    "request", "native", "text still allowed", null), false).duplicate)
            }
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
            worker.join(1000)
        }
    }

    @Test fun queuedInlineImageCannotReappearAfterNativeImageClear() = queuedImageRace("display_revoked") { activity, _ ->
        PortalActivity::class.java.getDeclaredMethod("revokeImages").apply { isAccessible = true }.invoke(activity)
    }

    @Test fun queuedInlineImageLosesToNativeFinalWithoutSecondRendering() = queuedImageRace("unknown_request") { _, gate ->
        assertNotNull(gate.acceptNative("native", true))
    }

    @Test fun queuedInlineImageCannotOutliveForegroundRevocation() = queuedImageRace("background") { _, gate ->
        gate.revoke(false)
    }
}
