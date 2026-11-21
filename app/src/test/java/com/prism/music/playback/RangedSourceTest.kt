package com.prism.music.playback

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 1.9: [RangedSource] fetches a stream in 512 KiB blocks and, for a whole song's scan, one block ahead while the decoder
 * works on this one. What it hands the extractor must be the stream's own bytes — a block fetched ahead, a seek back to
 * the start, a server that answers a range request with the whole file — and a server that sends something else must fail
 * loudly rather than have another recording's audio filed as this one's.
 */
class RangedSourceTest {
    private val length = 1_300_000
    private val data = ByteArray(length) { ((it * 31 + 7) and 0xFF).toByte() }
    private val ranges = java.util.Collections.synchronizedList(ArrayList<Pair<Long, Long>>())

    /** A server that honours Range, or one that ignores it and sends the whole file ([whole]), or lies about where. */
    private fun server(whole: Boolean = false, lieStart: Long? = null): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val asked = Regex("bytes=(\\d+)-(\\d+)").find(request.header("Range") ?: "")
            val from = asked?.groupValues?.get(1)?.toLong() ?: 0L
            val to = asked?.groupValues?.get(2)?.toLong() ?: (length - 1).toLong()
            ranges += from to to
            val body = data.copyOfRange(from.toInt(), (to + 1).toInt())
            if (whole) {
                Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                    .body(data.toResponseBody()).build()
            } else {
                Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(206).message("Partial")
                    .header("Content-Range", "bytes ${lieStart ?: from}-$to/${length}")
                    .body(body.toResponseBody()).build()
            }
        })
        .build()

    /** Everything [source] hands over, read the way a decoder reads it. */
    private fun whole(source: RangedSource): ByteArray {
        val out = ByteArray(length)
        var at = 0
        var reads = 0
        while (at < length && reads < 10_000) {
            val n = source.readAt(at.toLong(), out, at, minOf(64 * 1024, length - at))
            if (n <= 0) break
            at += n
            reads++
        }
        assertEquals("the whole stream was read", length, at)
        return out
    }

    @Test
    fun aWholeSongIsTheStreamsOwnBytes() {
        val source = RangedSource(server(), "https://prism.test/audio", length.toLong(), prefetch = true)
        try {
            assertArrayEquals(data, whole(source))
        } finally {
            source.close()
        }
    }

    /** The extractor reads an MP4's index at the end before the start: a block fetched for the wrong place must not stick. */
    @Test
    fun aSeekBackToTheStartReadsTheRightBytes() {
        val source = RangedSource(server(), "https://prism.test/audio", length.toLong(), prefetch = true)
        try {
            val tail = ByteArray(4096)
            assertEquals(4096, source.readAt((length - 4096).toLong(), tail, 0, 4096))
            assertArrayEquals(data.copyOfRange(length - 4096, length), tail)
            assertArrayEquals(data, whole(source))
        } finally {
            source.close()
        }
    }

    /** A server that ignores the range sends the whole file: kept, and every block comes out of it, still right. */
    @Test
    fun aServerThatIgnoresTheRangeStillGivesTheSongsOwnBytes() {
        val source = RangedSource(server(whole = true), "https://prism.test/audio", length.toLong(), prefetch = true)
        try {
            assertArrayEquals(data, whole(source))
            assertEquals("the whole file was fetched once, not per block", 1, ranges.size)
        } finally {
            source.close()
        }
    }

    /** Bytes that aren't the block asked for would decode as another recording's audio: that's a failure, not a scan. */
    @Test
    fun aBlockFromSomewhereElseIsAFailure() {
        val source = RangedSource(server(lieStart = 1234L), "https://prism.test/audio", length.toLong(), prefetch = true)
        try {
            val out = ByteArray(1024)
            val thrown = runCatching { source.readAt(0, out, 0, out.size) }.exceptionOrNull()
            assertTrue("a read that isn't the bytes asked for throws: $thrown", thrown is IOException)
            assertTrue("and is remembered, so a half-read stream is never filed as measured", source.failure != null)
        } finally {
            source.close()
        }
    }
}
