package com.hikeratlas.android.search

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SearchScreenTest {
    @get:Rule val compose = createComposeRule()

    private val server = MockWebServer()

    @After
    fun tearDown() = server.close()

    private fun result(name: String) =
        """[{"kind":"peak","osm_id":"1","name":"$name","lat":49.9,"lon":-123.0}]"""

    /** Typing on while a search is out cancels it: that isn't a failure to show. */
    @Test
    fun typingOnDoesntFlashAnError() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.queryParameter("q")) {
                // Slow, so the next letter comes while it's out.
                "bla" -> MockResponse.Builder().body(result("Black Tusk")).headersDelay(1, TimeUnit.SECONDS).build()
                else -> MockResponse.Builder().body(result("Blackcomb Peak")).build()
            }
        }
        server.start()
        val api = SearchApi(OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build(), server.url("/").toString())
        val recents = RecentSearches(ApplicationProvider.getApplicationContext())
        compose.setContent { SearchScreen(api, recents, null, null, onPick = {}, onClose = {}) }

        val field = compose.onNode(hasSetTextAction())
        field.performTextInput("bla")
        // Past the typing pause: "bla" is out.
        compose.waitUntil(5_000) { server.requestCount >= 1 }
        field.performTextInput("ckc")
        var errorSeen = false
        compose.waitUntil(10_000) {
            if (compose.onAllNodesWithText("Search isn't available right now.").fetchSemanticsNodes().isNotEmpty()) errorSeen = true
            compose.onAllNodesWithText("Blackcomb Peak").fetchSemanticsNodes().isNotEmpty()
        }
        // The cancelled search only ends when its slow request returns,
        // after the new results are showing: keep watching past that.
        val until = System.nanoTime() + 2_500_000_000L
        while (System.nanoTime() < until) {
            compose.waitForIdle()
            if (compose.onAllNodesWithText("Search isn't available right now.").fetchSemanticsNodes().isNotEmpty()) errorSeen = true
            Thread.sleep(50)
        }
        assertEquals("error shown while typing on", false, errorSeen)
        assertEquals(1, compose.onAllNodesWithText("Blackcomb Peak").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Search isn't available right now.").fetchSemanticsNodes().size)
    }

    /** A search that really fails still says so. */
    @Test
    fun aFailedSearchSaysSo() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse.Builder().code(500).build()
        }
        server.start()
        val api = SearchApi(OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build(), server.url("/").toString())
        compose.setContent { SearchScreen(api, RecentSearches(ApplicationProvider.getApplicationContext()), null, null, onPick = {}, onClose = {}) }
        compose.onNode(hasSetTextAction()).performTextInput("tusk")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Search isn't available right now.").fetchSemanticsNodes().isNotEmpty() }
    }

    /** A search with no results says so (#21), but not before it's back. */
    @Test
    fun noResultsSaysSo() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse.Builder().body("[]").headersDelay(500, TimeUnit.MILLISECONDS).build()
        }
        server.start()
        val api = SearchApi(OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build(), server.url("/").toString())
        compose.setContent { SearchScreen(api, RecentSearches(ApplicationProvider.getApplicationContext()), null, null, onPick = {}, onClose = {}) }
        compose.onNode(hasSetTextAction()).performTextInput("zzqx")
        // Out: not yet.
        compose.waitUntil(5_000) { server.requestCount >= 1 }
        assertEquals(0, compose.onAllNodesWithText("No matches for \u201czzqx\u201d").fetchSemanticsNodes().size)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("No matches for \u201czzqx\u201d").fetchSemanticsNodes().isNotEmpty() }
    }
}
