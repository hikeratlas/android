package com.hikeratlas.android.logs

import androidx.test.core.app.ApplicationProvider
import com.hikeratlas.android.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LogTest {
    @get:Rule val tmp = TemporaryFolder()

    private val t0 = 1_794_830_400_000L

    private fun install(detailed: Boolean, direct: Boolean = true): LogStore {
        val store = LogStore(tmp.root, { ZoneOffset.UTC })
        Log.install(store, detailed, direct = direct, clock = { t0 })
        return store
    }

    @After
    fun tearDown() {
        Log.resetForTest()
        Thread.setDefaultUncaughtExceptionHandler(null)
    }

    @Test
    fun errorsWarningsAndInfoAlwaysKeptDebugOnlyWhenDetailed() {
        val store = install(detailed = false)
        Log.d("t", "hidden")
        Log.i("t", "info")
        Log.w("t", "warn")
        Log.e("t", "error")
        assertEquals(listOf("error", "warn", "info"), store.read().map { it.message })

        Log.detailed = true
        Log.d("t", "shown")
        assertEquals(Level.DEBUG, store.read().first().level)
        assertEquals("shown", store.read().first().message)
    }

    @Test
    fun detailedLoggingDefaultsToTheBuildTypeAndIsRemembered() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        Log.install(app)
        // On in debug builds (these tests), off in release.
        assertEquals(BuildConfig.DEBUG, Log.detailed)
        Log.detailed = !BuildConfig.DEBUG
        Log.resetForTest()
        Log.install(app)
        assertEquals(!BuildConfig.DEBUG, Log.detailed)
    }

    @Test
    fun linesAreWrittenInOrderOffTheCallersThread() {
        val store = install(detailed = true, direct = false)
        for (i in 0 until 50) Log.i("t", "line $i")
        Log.flush()
        assertEquals((49 downTo 0).map { "line $it" }, store.read().map { it.message })
    }

    @Test
    fun notInstalledOrBrokenItNeverThrows() {
        Log.i("t", "nowhere")
        Log.e("t", "nowhere", IOException("x"))
        Log.flush()
        // A store that can't write (its folder is a file).
        val file = tmp.newFile("not-a-folder")
        Log.install(LogStore(file), detailed = true, direct = true)
        Log.i("t", "dropped")
    }

    @Test
    fun anExceptionIsLoggedByTypeAndMessageWithItsCauses() {
        val store = install(detailed = false)
        Log.w("outbox", "Upload stopped", IOException("Couldn't post", java.net.SocketTimeoutException("timeout")))
        assertEquals("Upload stopped: IOException: Couldn't post ← SocketTimeoutException: timeout", store.read().single().message)
    }

    @Test
    fun placesAreRoundedToTwoDecimals() {
        assertEquals("47.45,-121.80", Log.point(47.4512, -121.8012))
        assertEquals("-33.87,151.21", Log.point(-33.8688, 151.2093))
        assertEquals("0.00,0.00", Log.point(0.001, -0.004))
    }

    @Test
    fun tokensPassedByMistakeAreMasked() {
        val jwt = "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJkaWQ6cGxjOmFiYyJ9.c2lnbmF0dXJlLWhlcmU"
        val store = install(detailed = false)
        Log.e("account", "Refresh failed with $jwt")
        Log.e("account", "Header Authorization: DPoP abcdefgh12345678 sent")
        Log.e("account", "Header Authorization: Bearer abcdefgh12345678")
        Log.e("account", """{"access_token":"opaque-123456","refresh_token": "ref-abcdef","token_type":"DPoP"}""")
        Log.e("account", "Redirect ?code=cod-123&state=xyz&iss=https://bsky.social")
        val text = store.text()
        for (secret in listOf(jwt, "abcdefgh12345678", "opaque-123456", "ref-abcdef", "cod-123")) {
            assertFalse("$secret in $text", text.contains(secret))
        }
        // What isn't a secret stays readable.
        assertTrue(text.contains("token_type"))
        assertTrue(text.contains("iss=https://bsky.social"))
        assertTrue(text.contains("[token]"))
    }

    @Test
    fun handlesDidsAndUrisStay() {
        val s = "Signed in as did:plc:abc123xyz (alice.bsky.social): at://did:plc:abc123xyz/com.hikeratlas.temp.track/3lb2abc"
        assertEquals(s, Log.scrub(s))
    }

    @Test
    fun theCrashHandlerWritesTheStackThenHandsItOn() {
        val store = install(detailed = false, direct = false)
        Log.i("app", "before")
        var seenByPrevious: List<LogEntry>? = null
        var chained: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { _, e ->
            // What the previous handler sees: the log already written.
            seenByPrevious = store.read()
            chained = e
        }
        Thread.setDefaultUncaughtExceptionHandler(previous)
        CrashLog.install()
        // Installed once: a second install doesn't wrap it again.
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        CrashLog.install()
        assertSame(handler, Thread.getDefaultUncaughtExceptionHandler())

        val boom = IllegalStateException("boom")
        handler!!.uncaughtException(Thread.currentThread(), boom)

        assertSame(boom, chained)
        val crash = seenByPrevious!!.first()
        assertEquals(Level.ERROR, crash.level)
        assertEquals("crash", crash.tag)
        assertTrue(crash.message.startsWith("Uncaught on ${Thread.currentThread().name}: java.lang.IllegalStateException: boom"))
        assertTrue("the stack", crash.message.contains("at com.hikeratlas.android.logs.LogTest"))
        assertEquals("before", seenByPrevious!![1].message)
    }
}
