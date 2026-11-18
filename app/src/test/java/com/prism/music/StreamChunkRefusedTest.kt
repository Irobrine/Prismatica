package com.prism.music

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.prism.music.data.stream.ForgetOnForbidden
import com.prism.music.data.stream.YouTubeStreamInterceptor
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * GitHub #408 ("Error 403" on many songs, downloads failing too): googlevideo let the first megabyte through and refused a
 * later one. That came out as a plain IOException, so the player saw a dropped connection (no HTTP code) and asked the
 * same client again instead of another one, and a download kept its refused link through all of Media3's retries. A
 * refused later chunk now fails the way a refused first one does: as an HTTP 403.
 *
 * GitHub #514: googlevideo answering HTTP 503 (its server can't serve now, not the link being refused) was handed on as
 * it was, so the same dead link stayed in hand through every retry. A 5xx is now asked again twice, quickly — the first
 * chunk and later chunks alike — and what comes of that is the answer; a 403 keeps its single retry.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StreamChunkRefusedTest {
    private val chunk = 1024L
    private val clen = 4 * chunk
    private val url = "https://rr1---sn-test.googlevideo.com/videoplayback?id=o-test&itag=251&clen=$clen&c=WEB_REMIX"

    /** googlevideo: the first chunk comes, everything from [refuseFrom] on is a 403. */
    private fun googlevideo(refuseFrom: Long) = Interceptor { chain ->
        val req = chain.request()
        val range = req.url.queryParameter("range") ?: "0-"
        val from = range.substringBefore('-').toLong()
        val to = range.substringAfter('-').toLongOrNull() ?: (clen - 1)
        val b = Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
        if (from >= refuseFrom) b.code(403).message("Forbidden").body(ByteArray(0).toResponseBody(null)).build()
        else b.code(200).message("OK").body(ByteArray((to - from + 1).toInt()) { 7 }.toResponseBody(null)).build()
    }

    private fun source(): OkHttpDataSource {
        val chunkClient = OkHttpClient.Builder().addInterceptor(googlevideo(refuseFrom = chunk)).build()
        val http = OkHttpClient.Builder().addInterceptor(YouTubeStreamInterceptor(chunkClient, chunk))
            .addInterceptor(googlevideo(refuseFrom = chunk)).build()
        return OkHttpDataSource.Factory(http).createDataSource()
    }

    /** Reads [s] to its end; the failure it ends in. */
    private fun readToFailure(open: () -> Unit, read: (ByteArray) -> Int): IOException {
        open()
        val buf = ByteArray(4096)
        try {
            while (read(buf) != androidx.media3.common.C.RESULT_END_OF_INPUT) Unit
        } catch (e: IOException) {
            return e
        }
        fail("the refused chunk was read as if it came")
        error("unreachable")
    }

    /** The code PlaybackService's retry finds in the failure's causes (its `httpCode`). */
    private fun httpCode(e: Throwable): Int? = generateSequence(e) { it.cause }
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode

    @Test fun aRefusedLaterChunkFailsAsAn403() {
        val s = source()
        val e = readToFailure({ assertEquals(clen, s.open(DataSpec.Builder().setUri(Uri.parse(url)).build())) }) { s.read(it, 0, it.size) }
        s.close()
        assertEquals("the player's retry must see the 403 (refused = true), not a dropped connection: $e", 403, httpCode(e))
    }

    @Test fun aDownloadForgetsTheLinkWhenALaterChunkIsRefused() {
        val forgotten = mutableListOf<Pair<String, Int>>()
        val s = ForgetOnForbidden(source()) { key, code -> forgotten += key to code }
        readToFailure({ s.open(DataSpec.Builder().setUri(Uri.parse(url)).setKey("video:abc").build()) }) { s.read(it, 0, it.size) }
        s.close()
        assertEquals(listOf("video:abc" to 403), forgotten)
    }

    @Test fun aRefusedFirstChunkStillFailsAsAn403FromOpen() {
        val chunkClient = OkHttpClient.Builder().addInterceptor(googlevideo(refuseFrom = 0)).build()
        val http = OkHttpClient.Builder().addInterceptor(YouTubeStreamInterceptor(chunkClient, chunk))
            .addInterceptor(googlevideo(refuseFrom = 0)).build()
        val forgotten = mutableListOf<Pair<String, Int>>()
        val s = ForgetOnForbidden(OkHttpDataSource.Factory(http).createDataSource()) { key, code -> forgotten += key to code }
        try {
            s.open(DataSpec.Builder().setUri(Uri.parse(url)).setKey("abc").build())
            fail("opened a refused link")
        } catch (e: IOException) {
            assertEquals(403, httpCode(e))
        }
        assertTrue(forgotten == listOf("abc" to 403))
    }

    // ---------------------------------------------------------------- #514: a server answering 5xx

    /** The whole file in one request: its `clen` is one chunk, so the interceptor doesn't stitch ranges for it. */
    private val whole = "https://rr1---sn-test.googlevideo.com/videoplayback?id=o-whole&itag=251&clen=$chunk&c=WEB_REMIX"

    /**
     * googlevideo answering [answers] in turn (the last of them for every ask after), the bytes of the range asked for
     * when it serves, counting its asks in all and per byte.
     */
    private inner class Swerving(vararg answers: Int) {
        private val codes = answers.toList()
        private val all = AtomicInteger()
        private val froms = ConcurrentHashMap<Long, AtomicInteger>()

        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val range = req.url.queryParameter("range") ?: "0-"
            val from = range.substringBefore('-').toLong()
            froms.computeIfAbsent(from) { AtomicInteger() }.incrementAndGet()
            val code = codes[minOf(all.getAndIncrement(), codes.size - 1)]
            // The file's own length (whole is one chunk; url is four).
            val length = req.url.queryParameter("clen")?.toLongOrNull() ?: clen
            val to = range.substringAfter('-').toLongOrNull() ?: (length - 1)
            val b = Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
            if (code == 200) b.code(200).message("OK").header("Content-Length", (to - from + 1).toString())
                .body(ByteArray((to - from + 1).toInt()) { 7 }.toResponseBody(null)).build()
            else b.code(code).message("Busy").body(ByteArray(0).toResponseBody(null)).build()
        }

        /** How many asks the bytes at [from] got. */
        fun asksAt(from: Long): Int = froms[from]?.get() ?: 0
        val asks: Int get() = all.get()

        /** Prism's own client for such a link: [before] (the stream's interceptor), then this googlevideo. */
        fun client(vararg before: Interceptor) = OkHttpClient.Builder()
            .apply { before.forEach { addInterceptor(it) } }.addInterceptor(interceptor).build()
    }

    /** Reads [s] to its end; how many bytes came. */
    private fun readAll(s: OkHttpDataSource): Int {
        val buf = ByteArray(4096)
        var read = 0
        while (true) {
            val n = s.read(buf, 0, buf.size)
            if (n == androidx.media3.common.C.RESULT_END_OF_INPUT) return read
            assertTrue("only the served bytes", (0 until n).all { buf[it] == 7.toByte() })
            read += n
        }
    }

    @Test fun a503OnTheFirstRequestIsAskedAgainAndPlays() {
        val yt = Swerving(503, 200)
        val s = OkHttpDataSource.Factory(yt.client(YouTubeStreamInterceptor())).createDataSource()
        assertEquals(chunk, s.open(DataSpec.Builder().setUri(Uri.parse(whole)).build()))
        assertEquals("all of it came", chunk.toInt(), readAll(s))
        s.close()
        assertEquals("asked once more, quickly", 2, yt.asks)
    }

    @Test fun aServerThatKeepsAnswering503IsGivenUpAfterTwoMoreAsks() {
        val yt = Swerving(503)
        val s = OkHttpDataSource.Factory(yt.client(YouTubeStreamInterceptor())).createDataSource()
        try {
            s.open(DataSpec.Builder().setUri(Uri.parse(whole)).build())
            fail("opened a link whose server kept answering 503")
        } catch (e: IOException) {
            assertEquals("what the player's retry sees must be the 503", 503, httpCode(e))
        }
        s.close()
        assertEquals("asked once and twice again, then given up", 3, yt.asks)
    }

    @Test fun a403IsStillAskedOnceMoreOnly() {
        val yt = Swerving(403, 200)
        val s = OkHttpDataSource.Factory(yt.client(YouTubeStreamInterceptor())).createDataSource()
        assertEquals(chunk, s.open(DataSpec.Builder().setUri(Uri.parse(whole)).build()))
        assertEquals("all of it came", chunk.toInt(), readAll(s))
        s.close()
        assertEquals("one more ask, as before: a refusal is no 5xx", 2, yt.asks)
    }

    @Test fun a503OnALaterChunkIsAskedAgainToo() {
        // The first chunk comes; the next one's server is busy twice, then serves it.
        val yt = Swerving(200, 503, 503, 200)
        val s = OkHttpDataSource.Factory(yt.client(YouTubeStreamInterceptor(yt.client(), chunk))).createDataSource()
        assertEquals(clen, s.open(DataSpec.Builder().setUri(Uri.parse(url)).build()))
        assertEquals("every byte came, past the busy chunk", clen.toInt(), readAll(s))
        s.close()
        assertEquals("the busy chunk asked again twice", 3, yt.asksAt(chunk))
    }

    @Test fun aLaterChunkWhoseServerKeepsAnswering503FailsAsAn503() {
        val yt = Swerving(200, 503)
        val s = OkHttpDataSource.Factory(yt.client(YouTubeStreamInterceptor(yt.client(), chunk))).createDataSource()
        val e = readToFailure({ s.open(DataSpec.Builder().setUri(Uri.parse(url)).build()) }) { s.read(it, 0, it.size) }
        s.close()
        assertEquals("what the retry sees must be the 503", 503, httpCode(e))
        assertEquals("asked once and twice again, then given up", 3, yt.asksAt(chunk))
    }
}
