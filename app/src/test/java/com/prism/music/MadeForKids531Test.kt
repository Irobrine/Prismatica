package com.prism.music

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.str
import com.prism.music.data.prefs.SettingsRepository
import com.prism.music.data.stream.StreamException
import com.prism.music.data.stream.StreamResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * GitHub #531: signed out, every "made for kids" video failed with "No playable audio stream was found". The visionOS
 * app (Prism's direct way, and the client NewPipe asks as) answers them "UNPLAYABLE: This video is not available"; the
 * older TV app as a Samsung TV ([StreamResolver.SIGNED_OUT_CLIENT]) answers them with links, without an account. It is
 * the last way signed out, only after the visionOS app and NewPipe had nothing.
 *
 * A smaller stand-in for the upstream test's saved answers: what each way answers is made here, and the TV link stands
 * in plain (its real ones are ciphered, solved by YouTube's player JS, which doesn't run on the PC).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MadeForKids531Test {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** The settings store this test signed in through: signed out again after it (it's shared with the JVM's tests). */
    private var signedIn: SettingsRepository? = null

    @After fun signOutAfterTest() { signedIn?.let { signOutNow(it) } }

    /** Signed out at once, and again after the test ([signOutAfterTest]). */
    private fun signOutNow(settings: SettingsRepository) {
        signedIn = settings
        settings.logout()
        runBlocking { withTimeout(10_000) { settings.flow.first { !it.isLoggedIn } } }
    }

    /**
     * YouTube as the ways find it signed out: the visionOS app refuses the made-for-kids video and plays others, NewPipe
     * (every other request) is answered nothing, and the older TV app as a Samsung TV answers the kids video with a
     * link — but only for a request without an account, so a signed-in try can't get it.
     */
    private inner class FakeYouTube(
        /** The TV app has a link for the made-for-kids video. */
        private val tvAnswers: Boolean = true,
    ) {
        val asked = Collections.synchronizedList(mutableListOf<String>())
        /** The Cookie header each TV ask went out with. */
        val tvCookies = Collections.synchronizedList(mutableListOf<String?>())
        /** NewPipe's own requests (the watch page above all): what the visionOS app's answer left it to ask. */
        val others = AtomicInteger()
        /** googlevideo's asks: the signed-out TV link's, which waits until it's served. */
        val streamAsks = AtomicInteger()

        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val path = req.url.encodedPath
            fun answer(code: Int, text: String) = Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("fake")
                .body(text.toResponseBody("application/json".toMediaType())).build()
            when {
                req.url.host.endsWith("googlevideo.com") -> {
                    streamAsks.incrementAndGet()
                    answer(206, "x".repeat(1024))
                }
                path.endsWith("/player") -> {
                    val buf = okio.Buffer().also { req.body?.writeTo(it) }
                    val body = InnerTube.json.parseToJsonElement(buf.readUtf8()).jsonObject
                    val name = body.str("context", "client", "clientName") ?: "?"
                    val make = body.str("context", "client", "deviceMake")
                    val id = body.str("videoId") ?: "?"
                    asked += "$name${make?.let { "/$it" }.orEmpty()} $id"
                    val tv = name == "TVHTML5" && make == "Samsung"
                    if (tv) tvCookies += req.header("Cookie")
                    answer(200, when {
                        id != KIDS && name == "VISIONOS" -> playable(id, "VISIONOS")
                        id == KIDS && name == "VISIONOS" -> UNPLAYABLE
                        id == KIDS && tv && tvAnswers && req.header("Cookie").isNullOrBlank() -> playable(id, "TVHTML5")
                        else -> UNPLAYABLE
                    })
                }
                path.contains("visitor_id") -> answer(200, """{"responseContext":{"visitorData":"v"}}""")
                else -> {
                    others.incrementAndGet()
                    answer(404, "{}")
                }
            }
        }).build()

        val settings = SettingsRepository(context, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val streams: StreamResolver

        init {
            // Signed out for this test (and signed out again after it).
            signOutNow(settings)
            streams = StreamResolver(context, http, settings, InnerTube(http, { settings.current }))
            // A served link is answered at once: the wait itself is the TV way's, not this test's.
            streams.servedPausesMs = emptyList()
        }

        fun tvAsks() = asked.count { it.startsWith("TVHTML5/Samsung") }
        fun tvAsksWithAccount() = tvCookies.count { !it.isNullOrBlank() }
    }

    @Test fun signedOutAMadeForKidsVideoPlaysFromTheTvApp() {
        val yt = FakeYouTube()
        assertFalse("signed out", yt.settings.current.isLoggedIn)
        val s = yt.streams.resolve(KIDS)
        assertEquals("the TV app's link", "TVHTML5", s.audioUrl.toHttpUrl().queryParameter("c"))
        assertEquals("Opus 251, as for any song", 251, s.itag)
        assertTrue("the visionOS app was asked first: ${yt.asked}", yt.asked.first().startsWith("VISIONOS"))
        assertEquals("the TV app once: ${yt.asked}", 1, yt.tvAsks())
        assertEquals("the last way, after NewPipe's page was answered nothing: ${yt.asked}", "TVHTML5/Samsung $KIDS", yt.asked.last())
        assertTrue("NewPipe's page was asked before it: ${yt.asked}", yt.others.get() > 0)
        assertTrue("no account's cookies: ${yt.tvCookies}", yt.tvCookies.all { it.isNullOrBlank() })
        assertEquals("its first bytes asked for until served", 1, yt.streamAsks.get())
    }

    @Test fun aSongTheVisionOsAppPlaysNeverAsksTheTvApp() {
        val yt = FakeYouTube()
        yt.streams.resolve("songA")
        assertEquals(listOf("VISIONOS/Apple songA"), yt.asked.toList())
        assertEquals("no wait for the usual links", 0, yt.streamAsks.get())
    }

    @Test fun whenTheTvAppHasNothingEitherItFailsAsBeforeAndIsAskedAgainNextTime() {
        val yt = FakeYouTube(tvAnswers = false)
        repeat(2) {
            val failed = runCatching { yt.streams.resolve(KIDS) }.exceptionOrNull()
            assertTrue("nothing plays it: $failed", failed is StreamException)
        }
        // Each lookup asks the TV app twice: once more on UNPLAYABLE, as for the account ([StreamResolver.AccountClients.retries]).
        assertEquals("not kept as refused: asked again: ${yt.asked}", 4, yt.tvAsks())
    }

    @Test fun signedInTheTvAppIsOnlyEverAskedAsTheAccount() {
        val yt = FakeYouTube()
        signIn(yt.settings)
        val failed = runCatching { yt.streams.resolve(KIDS) }.exceptionOrNull()
        assertTrue("nothing plays it signed in: $failed", failed is StreamException)
        assertTrue("the TV app was asked as the account: ${yt.asked}", yt.tvAsksWithAccount() > 0)
        assertEquals("its signed-out way wasn't used: ${yt.asked}", 0, yt.tvCookies.count { it.isNullOrBlank() })
    }

    /** Signs [settings] in for the test; [signOutAfterTest] afterward. */
    private fun signIn(settings: SettingsRepository) {
        signedIn = settings
        runBlocking {
            settings.saveAccount("SAPISID=abc; __Secure-3PAPISID=abc", "visitor", "user||")
            withTimeout(10_000) { settings.flow.first { it.isLoggedIn } }
        }
    }

    private companion object {
        const val KIDS = "XqZsoesa55w"

        const val UNPLAYABLE = """{"playabilityStatus":{"status":"UNPLAYABLE","reason":"This video is not available"}}"""

        /** An answer with one plain link: [id]'s audio as [client] (`c` in the link) gives it. */
        fun playable(id: String, client: String): String {
            val url = "https://rr1---sn-test.googlevideo.com/videoplayback?id=o-$id&itag=251&c=$client&expire=9999999999&clen=1000"
            return """{"playabilityStatus":{"status":"OK"},"streamingData":{"expiresInSeconds":"21600","adaptiveFormats":[""" +
                """{"itag":251,"mimeType":"audio/webm; codecs=\"opus\"","url":"$url","averageBitrate":"130000","contentLength":"1000","approxDurationMs":"200000"}""" +
                """]},"videoDetails":{"lengthSeconds":"200"}}"""
        }
    }
}
