package com.muse.gadget.portal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.muse.gadget.display.DisplayImagePolicy
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Fetches one image for `display.draw_url` within [DisplayImagePolicy]: https only,
 * no cookies or credentials, bounded redirects, bytes and dimensions, format from the
 * bytes. The resolver vets every address before the connection uses it, the 30 s
 * budget spans redirects and decoding, nothing is written to disk and the URL is
 * never logged.
 */
class PortalImageFetcher(system: Dns = Dns.SYSTEM) {
    data class Shown(val bitmap: Bitmap, val format: String, val width: Int, val height: Int,
        val scale: Int, val bytes: Int, val ms: Long)
    class Failure(val code: String, message: String) : Exception(message)
    private class BlockedDestination(host: String) : UnknownHostException("$host resolves to a non-public address")

    /** OkHttp connects only to what this returns, so vetting here is the connection policy. */
    private val vettedDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            DisplayImagePolicy.vetAddresses(system.lookup(hostname)) ?: throw BlockedDestination(hostname)
    }
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).dns(vettedDns).build()
    @Volatile private var current: Call? = null

    /** Stops the request in flight, if any; its caller gets a `cancelled` failure. */
    fun cancel() { current?.cancel() }

    @Throws(Failure::class)
    fun fetch(url: String): Shown {
        val started = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - started) / 1_000_000
        fun budget(): Long = DisplayImagePolicy.remainingBudgetMs(elapsedMs()).also {
            if (it <= 0) throw Failure("download_failed", "download timed out")
        }
        var current = when (val check = DisplayImagePolicy.checkUrl(url)) {
            is DisplayImagePolicy.Check.Ok -> check.url
            is DisplayImagePolicy.Check.Rejected -> throw Failure(check.code, check.message)
        }
        var hop = 0
        while (true) {
            val request = Request.Builder().url(current).header("Accept", DisplayImagePolicy.ACCEPT)
                .header("User-Agent", "MusePortal/0.3 (display.draw_url)").build()
            // Each hop gets only what is left of the one budget, never a fresh 30 s.
            val call = http.newBuilder().callTimeout(budget(), TimeUnit.MILLISECONDS).build().newCall(request)
            this.current = call
            val response: Response = try {
                call.execute()
            } catch (e: Exception) {
                throw when {
                    causedByBlockedDestination(e) -> Failure("bad_url", "url resolves to a non-public address")
                    call.isCanceled() -> Failure("cancelled", "the request was cancelled")
                    else -> Failure("download_failed", "download failed")
                }
            } finally {
                this.current = null
            }
            response.use { r ->
                if (r.isRedirect) {
                    val target = r.header("Location")?.let { r.request.url.resolve(it)?.toString() }
                    current = when (val check = DisplayImagePolicy.redirectAllowed(hop, target)) {
                        is DisplayImagePolicy.Check.Ok -> check.url
                        is DisplayImagePolicy.Check.Rejected -> throw Failure(check.code, check.message)
                    }
                    hop++
                    return@use
                }
                if (!r.isSuccessful) throw Failure("download_failed", "server answered HTTP ${r.code}")
                val body = r.body ?: throw Failure("download_failed", "empty response")
                if (!DisplayImagePolicy.lengthAllowed(body.contentLength())) throw Failure("too_large", "image is over 2 MiB")
                val data = readBounded(body.byteStream(), DisplayImagePolicy.MAX_BYTES)
                budget()
                val format = DisplayImagePolicy.sniff(data) ?: throw Failure("unsupported_format", "not a JPEG, PNG or WebP")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                val sample = DisplayImagePolicy.sampleSize(bounds.outWidth, bounds.outHeight)
                if (sample == 0) throw Failure("too_large", "image is over ${DisplayImagePolicy.MAX_SOURCE_EDGE} pixels on a side")
                val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options)
                    ?: throw Failure("unsupported_format", "image could not be decoded")
                if (DisplayImagePolicy.remainingBudgetMs(elapsedMs()) <= 0) {
                    bitmap.recycle()
                    throw Failure("download_failed", "download timed out")
                }
                return Shown(bitmap, format, bitmap.width, bitmap.height, sample, data.size, elapsedMs())
            }
        }
    }

    private fun causedByBlockedDestination(e: Throwable): Boolean {
        var cause: Throwable? = e
        var depth = 0
        while (cause != null && depth++ < 8) {
            if (cause is BlockedDestination) return true
            cause = cause.cause
        }
        return false
    }

    private fun readBounded(input: java.io.InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val block = ByteArray(16384)
        while (true) {
            val n = input.read(block)
            if (n < 0) break
            if (out.size() + n > max) throw Failure("too_large", "image is over 2 MiB")
            out.write(block, 0, n)
        }
        if (out.size() == 0) throw Failure("download_failed", "empty response")
        return out.toByteArray()
    }
}
