package com.hikeratlas.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.hikeratlas.android.atproto.Indexer
import com.hikeratlas.android.atproto.IndexerClient
import com.hikeratlas.android.atproto.Posts
import com.hikeratlas.android.atproto.Profiles
import com.hikeratlas.android.atproto.Xrpc
import kotlinx.coroutines.flow.MutableSharedFlow
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * A hike sheet's posts from other hikers, against a fake indexer (and a fake
 * PDS for a review's track): the counts, the lists and their paging, "Has a
 * track", Report, the refetch after a write, and nothing at all without the
 * indexer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class FromHikersTest {
    @get:Rule val compose = createComposeRule()

    private val server = MockWebServer()
    private val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val opened = Collections.synchronizedList(mutableListOf<String>())
    private val writes = MutableSharedFlow<String?>(extraBufferCapacity = 8)

    private val key = "1E57E06gbDEBBBICDGF.F55FRcODEp_"
    private val did = "did:plc:abcdefghijklmnopqrstuvwx"
    private val gpx = "<gpx><trk><trkseg><trkpt lat=\"47.5\" lon=\"-121.7\"/><trkpt lat=\"47.51\" lon=\"-121.69\"/></trkseg></trk></gpx>"

    @After
    fun tearDown() = server.close()

    private fun reviewUri(rkey: String) = "at://$did/${Posts.REVIEW}/$rkey"
    private fun trackUri(rkey: String) = "at://$did/${Posts.TRACK}/$rkey"
    private val author = """{"did":"$did","handle":"alice.test"}"""

    private fun review(rkey: String, track: String? = null) =
        """{"uri":"${reviewUri(rkey)}","cid":"c","author":$author,"indexedAt":"x","record":{"text":"Review $rkey","createdAt":"2026-11-14T00:00:00.000Z",
        "hikedAt":"2026-11-13T12:00:00.000Z","tags":["mud"],"license":"CC-BY-4.0"${track?.let { ""","track":{"uri":"${trackUri(it)}","cid":"c"}""" } ?: ""}}}"""

    private fun trackRecord(rkey: String) =
        """{"createdAt":"2026-11-14T00:00:00.000Z","gpx":{"ref":{"${'$'}link":"gpx$rkey"},"mimeType":"application/gzip"},"distance":2434,"gain":79,"duration":1699,"title":"Track $rkey"}"""

    private fun track(rkey: String) = """{"uri":"${trackUri(rkey)}","cid":"c","author":$author,"indexedAt":"x","record":${trackRecord(rkey)}}"""

    private fun ok(body: String) = MockResponse.Builder().body(body).build()

    /** A fake indexer: 5 reviews and 4 tracks, 3 to a first page; and a PDS with one more track and one gone. */
    private fun indexer(
        stats: (fresh: String?) -> String = { f -> """{"routes":[{"routeKey":"$key","faves":${if (f != null) 13 else 12},"reviews":5,"tracks":4,"recentTags":["mud","views"]}]}""" },
        fail: MockResponse? = null,
    ) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val u = request.url
                if (fail != null && u.encodedPath.startsWith("/xrpc/com.hikeratlas")) return fail
                return when (u.encodedPath) {
                    "/xrpc/com.hikeratlas.temp.getRouteStats" -> ok(stats(u.queryParameter("fresh")))
                    "/xrpc/com.hikeratlas.temp.getRouteReviews" -> ok(
                        if (u.queryParameter("cursor") == null) """{"reviews":[${review("r1", "t1")},${review("r2", "t9")},${review("r3", "gone")}],"cursor":"c1"}"""
                        else """{"reviews":[${review("r4")},${review("r5")}]}""",
                    )
                    "/xrpc/com.hikeratlas.temp.getRouteTracks" -> ok(
                        if (u.queryParameter("cursor") == null) """{"tracks":[${track("t1")},${track("t2")},${track("t3")}],"cursor":"tc"}"""
                        else """{"tracks":[${track("t4")},${track("t9")}]}""",
                    )
                    "/xrpc/com.atproto.repo.getRecord" ->
                        if (u.queryParameter("rkey") == "t9") ok("""{"uri":"${trackUri("t9")}","cid":"c9","value":${trackRecord("t9")}}""")
                        else MockResponse.Builder().code(400).body("""{"error":"RecordNotFound"}""").build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    private fun actions(timeoutMs: Long = 5_000) = HikerActions(
        IndexerClient(http, server.url("/").toString(), timeoutMs),
        pdsOf = { server.url("/").toString().trimEnd('/') },
        getTrack = { pds, uri -> Profiles.getTrack(Xrpc.public(http, pds), uri) },
        gpx = { _, _, _ -> gpx },
        openUrl = { opened += it },
        showTrack = { _, _, _ -> },
        shareGpx = { _, _ -> },
        writes = writes,
        now = { 1234L },
    )

    /** The counts and the section with room between, in a short scrolling sheet. */
    private fun show(actions: HikerActions = actions(), height: Int = 300) {
        kotlinx.coroutines.runBlocking { IndexerClient(http, server.url("/").toString()).stats(listOf("warm-up")) } // warm up OkHttp under Robolectric
        requests.clear()
        compose.setContent {
            HikerAtlasTheme {
                val hikers = rememberRouteHikers(key, actions)
                Column(Modifier.height(height.dp).verticalScroll(rememberScrollState())) {
                    Text("The sheet")
                    RouteCounts(hikers)
                    Box(Modifier.height(400.dp))
                    FromHikers(hikers) {}
                    Text("Trip reports")
                }
            }
        }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size
    /** Nodes with [text] in them: a post's title and date share its author's line. */
    private fun has(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size
    private fun tagged(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size
    // A synchronized list is iterated under its lock: the server's thread may be adding to it.
    private fun asked(path: String) = synchronized(requests) { requests.filter { it.url.encodedPath == "/xrpc/$path" } }

    @Test
    fun countsLineSaysWhatsBeenPosted() {
        indexer()
        show()
        compose.waitUntil(10_000) { count("12 faves") > 0 }
        compose.onNodeWithText("5 reviews").assertIsDisplayed()
        compose.onNodeWithText("4 tracks").assertIsDisplayed()
        assertEquals(listOf(key), asked("com.hikeratlas.temp.getRouteStats").single().url.queryParameterValues("keys"))
    }

    @Test
    fun oneOfEachIsSingular() {
        indexer(stats = { """{"routes":[{"routeKey":"$key","faves":1,"reviews":1,"tracks":0}]}""" })
        show()
        compose.waitUntil(10_000) { count("1 fave") > 0 }
        compose.onNodeWithText("1 review").assertIsDisplayed()
        assertEquals(0, count("0 tracks"))
    }

    @Test
    fun nothingUntilSomeoneHasPosted() {
        indexer(stats = { """{"routes":[{"routeKey":"$key","faves":0,"reviews":0,"tracks":0}]}""" })
        show()
        compose.waitUntil(10_000) { asked("com.hikeratlas.temp.getRouteStats").isNotEmpty() }
        compose.waitForIdle()
        assertEquals(0, tagged("route-counts"))
        assertEquals(0, tagged("from-hikers"))
        // No lists asked for when there's nothing in them.
        assertTrue(asked("com.hikeratlas.temp.getRouteReviews").isEmpty())
        assertTrue(asked("com.hikeratlas.temp.getRouteTracks").isEmpty())
    }

    @Test
    fun countsScrollToFromHikers() {
        indexer()
        show()
        compose.waitUntil(10_000) { count("Review r1") > 0 }
        compose.onNodeWithText("From hikers").assertIsNotDisplayed()
        compose.onNodeWithText("5 reviews").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("From hikers").assertIsDisplayed()
    }

    @Test
    fun reviewsAndTracksThreeThenPagesOfTen() {
        indexer()
        show(height = 3000)
        compose.waitUntil(10_000) { count("Review r1") > 0 && has("Track t3") > 0 }
        compose.onNodeWithText("Reported in the last month: Mud, Views").assertIsDisplayed()
        assertEquals(1, count("Review r3"))
        assertEquals(0, count("Review r4"))
        assertEquals("3", asked("com.hikeratlas.temp.getRouteReviews").single().url.queryParameter("limit"))
        // Who, when, tags, text, licence and Report, as the website's.
        assertEquals(6, count("@alice.test"))
        assertEquals(3, has("Nov 13, 2026"))
        assertEquals(3, count("Mud"))
        assertEquals(6, count("Shared under CC BY 4.0") + count("Shared under Hiker Atlas only"))
        assertEquals(3, count("Shared under Hiker Atlas only")) // the tracks give no licence
        assertEquals(6, count("Report"))
        assertEquals(3, count("Show on map"))
        // Each track's numbers, in your units.
        assertEquals(3, count(trackFacts(trackOf("t1"))))

        compose.onNodeWithText("More reviews").performScrollTo().performClick()
        compose.waitUntil(10_000) { count("Review r5") > 0 }
        val page = asked("com.hikeratlas.temp.getRouteReviews").last().url
        assertEquals("10", page.queryParameter("limit"))
        assertEquals("c1", page.queryParameter("cursor"))
        // No cursor back: no more to get.
        assertEquals(0, count("More reviews"))

        compose.onNodeWithText("More tracks").performScrollTo().performClick()
        compose.waitUntil(10_000) { has("Track t4") > 0 }
        assertEquals("tc", asked("com.hikeratlas.temp.getRouteTracks").last().url.queryParameter("cursor"))
        assertEquals(0, count("More tracks"))
    }

    private fun trackOf(rkey: String) = Profiles.Track(trackUri(rkey), "c", "x", Profiles.Blob("b", null), distance = 2434.0, gain = 79.0, duration = 1699.0)

    @Test
    fun hasATrackScrollsToItInTracks() {
        indexer()
        show()
        // r1's and r2's (fetched from the PDS); r3's is gone, so it says nothing.
        compose.waitUntil(10_000) { count("Has a track") == 2 && asked("com.atproto.repo.getRecord").any { it.url.queryParameter("rkey") == "gone" } }
        compose.waitForIdle()
        assertEquals(2, count("Has a track"))
        val t1 = "track-${trackUri("t1")}"
        compose.onAllNodesWithText("Has a track").onFirst().performScrollTo()
        compose.onNodeWithTag(t1).assertIsNotDisplayed()
        compose.onAllNodesWithText("Has a track").onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(t1).assertIsDisplayed()
    }

    @Test
    fun hasATrackNotLoadedYetIsFetchedAndAdded() {
        indexer()
        show()
        compose.waitUntil(10_000) { count("Has a track") == 2 }
        val get = asked("com.atproto.repo.getRecord").first { it.url.queryParameter("rkey") == "t9" }.url
        assertEquals(did, get.queryParameter("repo"))
        assertEquals(Posts.TRACK, get.queryParameter("collection"))
        val t9 = "track-${trackUri("t9")}"
        assertEquals(0, tagged(t9))
        compose.onAllNodesWithText("Has a track")[1].performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(t9).assertIsDisplayed()
        compose.onNodeWithText("Track t9", substring = true).assertIsDisplayed()

        // The next page brings it again: still once.
        compose.onNodeWithText("More tracks").performScrollTo().performClick()
        compose.waitUntil(10_000) { has("Track t4") > 0 }
        assertEquals(1, tagged(t9))
    }

    @Test
    fun reportAndAuthorOpenInTheBrowser() {
        indexer()
        show(height = 3000)
        compose.waitUntil(10_000) { count("Review r1") > 0 }
        compose.onAllNodesWithText("Report").onFirst().performClick()
        compose.onAllNodesWithText("@alice.test").onFirst().performClick()
        assertEquals(listOf(Indexer.reportUrl(reviewUri("r1")), "https://www.hikeratlas.com/@alice.test"), opened.toList())
    }

    @Test
    fun aWriteToThisRouteRefetchesPastTheCache() {
        indexer()
        show()
        compose.waitUntil(10_000) { count("12 faves") > 0 && count("Review r1") > 0 }
        val before = asked("com.hikeratlas.temp.getRouteStats").size
        assertTrue(asked("com.hikeratlas.temp.getRouteStats").all { it.url.queryParameter("fresh") == null })
        // Another route's write: not this sheet's business. Then this one's.
        writes.tryEmit("1E57E06gbDEBBBICDGF.AAAAAAAAAAA")
        writes.tryEmit(key)
        compose.waitUntil(10_000) { count("13 faves") > 0 }
        compose.waitForIdle()
        val stats = asked("com.hikeratlas.temp.getRouteStats")
        assertEquals(before + 1, stats.size)
        assertEquals("1234", stats.last().url.queryParameter("fresh"))
        compose.waitUntil(10_000) { asked("com.hikeratlas.temp.getRouteReviews").any { it.url.queryParameter("fresh") == "1234" } }
        compose.waitUntil(10_000) { asked("com.hikeratlas.temp.getRouteTracks").any { it.url.queryParameter("fresh") == "1234" } }
    }

    @Test
    fun nothingShownWhenTheIndexerFails() {
        indexer(fail = MockResponse.Builder().code(503).build())
        show()
        compose.waitUntil(10_000) { asked("com.hikeratlas.temp.getRouteStats").isNotEmpty() }
        compose.waitForIdle()
        assertEquals(0, tagged("route-counts"))
        assertEquals(0, tagged("from-hikers"))
        compose.onNodeWithText("The sheet").assertIsDisplayed()
        assertEquals(1, count("Trip reports"))
    }

    @Test
    fun nothingShownWhenTheIndexerIsSlow() {
        indexer(fail = MockResponse.Builder().body("{}").headersDelay(3, TimeUnit.SECONDS).build())
        show(actions(timeoutMs = 300))
        compose.waitUntil(10_000) { asked("com.hikeratlas.temp.getRouteStats").isNotEmpty() }
        Thread.sleep(800)
        compose.waitForIdle()
        assertEquals(0, tagged("route-counts"))
        assertEquals(0, tagged("from-hikers"))
        compose.onNodeWithText("The sheet").assertIsDisplayed()
    }
}
