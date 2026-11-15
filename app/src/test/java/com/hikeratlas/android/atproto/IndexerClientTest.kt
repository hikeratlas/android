package com.hikeratlas.android.atproto

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** The indexer's queries: what's asked, what's kept of other people's records, and never an error for the sheet. */
class IndexerClientTest {
    private val server = MockWebServer()
    private val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
    private lateinit var client: IndexerClient

    private val did = "did:plc:abcdefghijklmnopqrstuvwx"
    private val key = "1E57E06gbDEBBBICDGF.F55FRcODEp_"

    @Before
    fun setUp() {
        server.start()
        client = IndexerClient(http, server.url("/").toString(), timeoutMs = 500)
    }

    @After
    fun tearDown() {
        Indexer.notifier = { _, done -> done() }
        server.close()
    }

    private fun answer(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun statsAskForEveryKeyOnceAndPassFresh() = runBlocking {
        answer("""{"routes":[{"routeKey":"$key","faves":12,"reviews":3,"tracks":2,"recentTags":["mud","views",7,"mud"]},{"routeKey":"k2","faves":-4,"reviews":"x","tracks":0}]}""")
        val s = client.stats(listOf(key, "k2", key), fresh = 1234)!!
        val r = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/xrpc/com.hikeratlas.temp.getRouteStats", r.url.encodedPath)
        assertEquals(listOf(key, "k2"), r.url.queryParameterValues("keys"))
        assertEquals("1234", r.url.queryParameter("fresh"))
        assertEquals(IndexerClient.RouteStats(key, 12, 3, 2, listOf("mud", "views")), s[key])
        // Odd counts read as none.
        assertEquals(IndexerClient.RouteStats("k2", 0, 0, 0), s["k2"])
    }

    @Test
    fun noFreshUnlessAfterAWrite() = runBlocking {
        answer("""{"reviews":[]}""")
        client.reviews(key, 3)
        val r = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertNull(r.url.queryParameter("fresh"))
        assertEquals(key, r.url.queryParameter("key"))
        assertEquals("3", r.url.queryParameter("limit"))
    }

    private fun author(d: String = did, extra: String = ""","handle":"alice.test","pds":"https://pds.example"""") = """{"did":"$d"$extra}"""

    private fun review(rkey: String, record: String, repo: String = did, a: String = author()) =
        """{"uri":"at://$repo/${Posts.REVIEW}/$rkey","cid":"c$rkey","author":$a,"record":$record,"indexedAt":"2026-11-15T00:00:00Z"}"""

    @Test
    fun malformedReviewsAreSkippedAndTheRestCapped() = runBlocking {
        val good = """{"text":"Lovely.","createdAt":"2026-11-14T00:00:00Z","hikedAt":"2026-11-13T12:00:00Z","tags":["views","${"x".repeat(100)}"],"track":{"uri":"at://$did/${Posts.TRACK}/t1","cid":"c"},"license":"CC0-1.0"}"""
        val long = """{"text":"${"a".repeat(5000)}","createdAt":"2026-11-14T00:00:00Z","tags":[${(1..15).joinToString(",") { "\"t$it\"" }}],
            "photos":[${(1..80).joinToString(",") { """{"image":{"ref":{"${'$'}link":"b$it"},"mimeType":"image/jpeg"}}""" }}]}"""
        answer(
            """{"reviews":[
            ${review("r1", good)},
            ${review("r2", """{"createdAt":"2026-11-14T00:00:00Z"}""")},
            ${review("r3", good, repo = "did:plc:zzzzzzzzzzzzzzzzzzzzzzzz")},
            ${review("r4", good, a = author("not-a-did"))},
            ${review("r5", "\"just a string\"")},
            ${review("r1", good)},
            ${review("r6", long, a = author(extra = ""","handle":"bad handle/../x","pds":"http://insecure.example""""))},
            {"uri":"at://$did/${Posts.TRACK}/t9","author":${author()},"record":$good},
            42
            ],"cursor":"next"}""",
        )
        val page = client.reviews(key, 3)!!
        assertEquals("next", page.cursor)
        assertEquals(listOf("at://$did/${Posts.REVIEW}/r1", "at://$did/${Posts.REVIEW}/r6"), page.items.map { it.uri })
        val r1 = page.items[0]
        assertEquals(IndexerClient.Author(did, "alice.test", "https://pds.example"), r1.author)
        assertEquals("Lovely.", r1.record.text)
        assertEquals("at://$did/${Posts.TRACK}/t1", r1.record.trackUri)
        assertEquals(listOf("views", "x".repeat(64)), r1.record.tags)
        val r6 = page.items[1]
        // A handle that isn't one, and a PDS that isn't https, are dropped (the PDS is then looked up).
        assertEquals(IndexerClient.Author(did, null, null), r6.author)
        assertEquals(3000, r6.record.text.length)
        assertEquals(10, r6.record.tags.size)
        assertEquals(70, r6.record.photos.size)
    }

    @Test
    fun tracksNeedTheirGpx() = runBlocking {
        fun track(rkey: String, record: String) =
            """{"uri":"at://$did/${Posts.TRACK}/$rkey","cid":"c","author":${author()},"record":$record,"indexedAt":"x"}"""
        answer(
            """{"tracks":[
            ${track("t1", """{"createdAt":"2026-11-14T00:00:00Z","gpx":{"ref":{"${'$'}link":"bafgpx"},"mimeType":"application/gzip"},"distance":2434,"gain":79,"duration":1699,"title":"${"T".repeat(300)}"}""")},
            ${track("t2", """{"createdAt":"2026-11-14T00:00:00Z"}""")}
            ]}""",
        )
        val page = client.tracks(key, 3)!!
        val t = page.items.single().record
        assertEquals("bafgpx", t.gpx.cid)
        assertEquals(2434.0, t.distance!!, 0.0)
        assertEquals(200, t.title!!.length)
        assertNull(page.cursor)
    }

    @Test
    fun withoutTheIndexerEveryCallIsNull() = runBlocking {
        answer("oops", code = 500)
        assertNull(client.stats(listOf(key)))
        answer("not json")
        assertNull(client.reviews(key, 3))
        answer("""{"tracks":"nope"}""")
        assertNull(client.tracks(key, 3))
        answer("[1,2]")
        assertNull(client.stats(listOf(key)))
        // Slow: given up on after the timeout.
        server.enqueue(MockResponse.Builder().body("""{"routes":[]}""").headersDelay(5, TimeUnit.SECONDS).build())
        val started = System.nanoTime()
        assertNull(client.stats(listOf(key)))
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3))
        // Nothing listening.
        assertNull(IndexerClient(http, "http://127.0.0.1:1").stats(listOf(key)))
    }

    @Test
    fun reportOpensTheFeedbackFormWithThePostsUri() {
        val uri = "at://$did/${Posts.REVIEW}/3lbxyz"
        val u = Indexer.reportUrl(uri).toHttpUrl()
        assertEquals("https://github.com/hikeratlas/feedback/issues/new", u.newBuilder().query(null).build().toString())
        assertEquals("report.yml", u.queryParameter("template"))
        assertEquals(uri, u.queryParameter("link"))
    }

    // A sheet showing the route reads it again once the indexer has been told, not before.
    @Test
    fun aWriteIsHeardWithItsRouteOnceTheIndexerIsTold() = runBlocking {
        var finish: (() -> Unit)? = null
        Indexer.notifier = { _, done -> finish = done }
        val heard = async { withTimeout(5_000) { Indexer.written.first() } }
        kotlinx.coroutines.yield()
        Indexer.notify("at://$did/${Faves.NSID}/$key", key)
        kotlinx.coroutines.yield()
        assertTrue("not before the indexer answers", heard.isActive)
        finish!!()
        assertEquals(key, heard.await())

        // A notifier that throws: still heard.
        Indexer.notifier = { _, _ -> error("indexer trouble") }
        val again = async { withTimeout(5_000) { Indexer.written.first() } }
        kotlinx.coroutines.yield()
        Indexer.notify("at://x", null)
        assertNull(again.await())
    }
}
