package com.hikeratlas.android.atproto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Telling the indexer about a write: what's sent, and that it can't hold up or fail the write. */
class IndexerTest {
    private val server = MockWebServer()
    private val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()

    @After
    fun tearDown() {
        Indexer.notifier = { _, done -> done() }
        server.close()
    }

    @Test
    fun postsTheRecordsUri() {
        server.start()
        server.enqueue(MockResponse.Builder().code(200).body("""{"indexed":true}""").build())
        val uri = "at://did:plc:aaaaaaaaaaaaaaaaaaaaaaaa/com.hikeratlas.temp.review/3lbxyz"
        val done = java.util.concurrent.CountDownLatch(1)
        Indexer.http(http, server.url("/notify").toString())(uri) { done.countDown() }
        val r = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue("told once it's answered", done.await(5, TimeUnit.SECONDS))
        assertEquals("POST", r.method)
        assertEquals("/notify", r.url.encodedPath)
        assertTrue(r.headers["Content-Type"]!!.startsWith("application/json"))
        assertEquals(uri, Json.parseToJsonElement(r.body!!.utf8()).jsonObject["uri"]!!.jsonPrimitive.content)
    }

    // The write is done when this is called: a slow or failing indexer mustn't hold it up or fail it.
    @Test
    fun neverBlocksOrThrows() {
        server.start()
        server.enqueue(MockResponse.Builder().code(200).headersDelay(10, TimeUnit.SECONDS).build())
        val started = System.nanoTime()
        Indexer.http(http, server.url("/notify").toString())("at://did:plc:aaaaaaaaaaaaaaaaaaaaaaaa/com.hikeratlas.temp.track/t") {}
        assertTrue("returned at once", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1))

        Indexer.notifier = { _, _ -> error("indexer trouble") }
        Indexer.notify("at://x") // doesn't throw
        Indexer.notifier = Indexer.http(http, "http://127.0.0.1:1/notify") // nothing listening
        Indexer.notify("at://x")
    }
}
