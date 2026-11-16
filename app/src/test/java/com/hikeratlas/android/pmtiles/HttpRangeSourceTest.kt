package com.hikeratlas.android.pmtiles

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Range reads, and reading again when the network breaks one off (android#95). */
class HttpRangeSourceTest {
    private val server = MockWebServer()
    private val bytes = ByteArray(64 * 1024) { it.toByte() }
    // Short, so a stalled body times out quickly.
    private val http = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun source(delays: List<Long> = listOf(1, 1)) = HttpRangeSource(server.url("/a.pmtiles").toString(), http, delays)

    private fun range(etag: String = "\"a\"") = MockResponse.Builder()
        .code(206)
        .addHeader("ETag", etag)
        .body(okio.Buffer().write(bytes))
        .build()

    // The headers come, then the connection drops before the body.
    private fun cutOff() = MockResponse.Builder()
        .code(206)
        .addHeader("ETag", "\"a\"")
        .body(okio.Buffer().write(bytes))
        .onResponseBody(SocketEffect.CloseSocket())
        .build()

    // The headers come, then nothing.
    private fun stalled() = MockResponse.Builder()
        .code(206)
        .addHeader("ETag", "\"a\"")
        .body(okio.Buffer().write(bytes))
        .onResponseBody(SocketEffect.Stall)
        .build()

    @Test
    fun readsARange() {
        server.enqueue(range())
        assertArrayEquals(bytes, source().read(100, bytes.size))
        assertEquals("bytes=100-${100 + bytes.size - 1}", server.takeRequest().headers["Range"])
    }

    @Test
    fun aConnectionCutOffMidBodyIsReadAgain() {
        server.enqueue(cutOff())
        server.enqueue(range())
        assertArrayEquals(bytes, source().read(0, bytes.size))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aStalledBodyIsReadAgain() {
        server.enqueue(stalled())
        server.enqueue(range())
        assertArrayEquals(bytes, source().read(0, bytes.size))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun givesUpAfterTwoRetries() {
        repeat(3) { server.enqueue(cutOff()) }
        server.enqueue(range())
        assertThrows(IOException::class.java) { source().read(0, bytes.size) }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun anHttpErrorIsNotRetried() {
        server.enqueue(MockResponse.Builder().code(500).build())
        server.enqueue(range())
        val e = assertThrows(IOException::class.java) { source().read(0, bytes.size) }
        assertTrue(e.message, e.message!!.startsWith("HTTP 500"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aRebuiltArchiveIsNotRetried() {
        server.enqueue(range("\"a\""))
        server.enqueue(range("\"b\""))
        server.enqueue(range("\"b\""))
        val s = source()
        s.read(0, bytes.size)
        assertThrows(ArchiveChangedException::class.java) { s.read(0, bytes.size) }
        assertEquals(2, server.requestCount)
    }

    // With no network, a read fails at once rather than after the retry pauses.
    @Test
    fun noServerFailsWithoutWaiting() {
        val url = server.url("/a.pmtiles").toString()
        server.close()
        val start = System.nanoTime()
        assertThrows(IOException::class.java) { HttpRangeSource(url, http, listOf(10_000, 10_000)).read(0, 10) }
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5))
    }
}
