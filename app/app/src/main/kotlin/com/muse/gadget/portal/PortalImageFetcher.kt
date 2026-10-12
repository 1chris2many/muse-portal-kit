package com.muse.gadget.portal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.muse.gadget.display.DisplayImagePolicy
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Fetches one image for `display.draw_url` within [DisplayImagePolicy]: https only,
 * no cookies or credentials, bounded redirects, bytes and dimensions, format from the
 * bytes. Nothing is written to disk and the URL is never logged.
 */
class PortalImageFetcher(private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
    .callTimeout(DisplayImagePolicy.DEADLINE_MS, TimeUnit.MILLISECONDS)
    .followRedirects(false).followSslRedirects(false).build()) {
    data class Shown(val bitmap: Bitmap, val format: String, val width: Int, val height: Int,
        val scale: Int, val bytes: Int, val ms: Long)
    class Failure(val code: String, message: String) : Exception(message)

    @Throws(Failure::class)
    fun fetch(url: String): Shown {
        val started = System.nanoTime()
        var current = when (val check = DisplayImagePolicy.checkUrl(url)) {
            is DisplayImagePolicy.Check.Ok -> check.url
            is DisplayImagePolicy.Check.Rejected -> throw Failure(check.code, check.message)
        }
        var hop = 0
        while (true) {
            if ((System.nanoTime() - started) / 1_000_000 > DisplayImagePolicy.DEADLINE_MS) throw Failure("download_failed", "download timed out")
            val response: Response = try {
                http.newCall(Request.Builder().url(current).header("Accept", DisplayImagePolicy.ACCEPT)
                    .header("User-Agent", "MusePortal/0.3 (display.draw_url)").build()).execute()
            } catch (e: Exception) {
                throw Failure("download_failed", "download failed")
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
                val format = DisplayImagePolicy.sniff(data) ?: throw Failure("unsupported_format", "not a JPEG, PNG or WebP")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                val sample = DisplayImagePolicy.sampleSize(bounds.outWidth, bounds.outHeight)
                if (sample == 0) throw Failure("too_large", "image is over ${DisplayImagePolicy.MAX_SOURCE_EDGE} pixels on a side")
                val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options)
                    ?: throw Failure("unsupported_format", "image could not be decoded")
                return Shown(bitmap, format, bitmap.width, bitmap.height, sample, data.size, (System.nanoTime() - started) / 1_000_000)
            }
        }
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
