package com.hikeratlas.android.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.hikeratlas.android.elevation.ElevationProfiler
import com.hikeratlas.android.routes.RoutesRepository
import com.hikeratlas.android.ui.HikerAtlasTheme
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** A park's sheet, as the website's: counts as tabs, filters, cards; crumbs above its name. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h3000dp-xxhdpi", application = android.app.Application::class)
class ParkSheetTest {
    @get:Rule val compose = createComposeRule()

    private val server = MockWebServer()

    @After
    fun tearDown() = server.close()

    private fun gz(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    @Test
    fun countsFiltersCardsAndCrumbs() {
        val park = gz(javaClass.getResource("/fixtures/garibaldi-park.json")!!.readBytes())
        // Garibaldi Lake from Rubble Creek has two trip reports.
        val reports = gz(
            """{"2226817|day|rubble creek trailhead|garibaldi lake|out-and-back":{"reports":[{"url":"https://a.example","title":"A","site":"a.example"},{"url":"https://b.example","title":"B","site":"b.example"}]}}""".toByteArray(),
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/index.json" -> MockResponse.Builder().body(
                    """{"bundle":"b.bin","parks":{"2226817":[0,${park.size}]},"paths":{"2226817":"canada/british-columbia/garibaldi-provincial-park"},
                       "reports":{"bundle":"r.bin","parks":{"2226817":[0,${reports.size}]}}}""",
                ).build()
                "/regions.json" -> MockResponse.Builder().body(
                    """{"countries":[{"name":"Canada","slug":"canada","bbox":[-141,41,-52,83],"states":[{"name":"British Columbia","slug":"british-columbia","bbox":[-139,48,-114,60],"areas":[]}]}]}""",
                ).build()
                "/b.bin" -> MockResponse.Builder().body(Buffer().write(park)).build()
                "/r.bin" -> MockResponse.Builder().body(Buffer().write(reports)).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
        val repo = RoutesRepository(OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build(), server.url("/").toString().trimEnd('/'))
        kotlinx.coroutines.runBlocking { repo.index() }
        val model = mutableStateOf<PanelModel?>(null)
        val crumbs = mutableListOf<Crumb>()
        val flat = object : ElevationProfiler.DemSource {
            override val minZoom = 0
            override val maxZoom = 14
            override fun tile(z: Int, x: Int, y: Int) = FloatArray(512 * 512) { 1000f }
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) {
                model.value = PanelModel(scope, repo, ElevationProfiler(listOf(flat))) { _, _ -> }.apply { openPark("2226817", "Garibaldi") }
            }
            HikerAtlasTheme {
                model.value?.let { m ->
                    Box(Modifier.width(390.dp).verticalScroll(rememberScrollState())) {
                        androidx.compose.foundation.layout.Column {
                            PanelHeader(m, full = true, onCrumb = { crumbs += it })
                            PanelBody(m) {}
                        }
                    }
                }
            }
        }
        compose.waitUntil(10_000) { (model.value?.panel as? Panel.RouteList)?.reports != null }
        val m = model.value!!

        // Crumbs above the name; tapping one hands back its box.
        compose.onNodeWithText("British Columbia").performClick()
        assertEquals(listOf(-139.0, 48.0, -114.0, 60.0), crumbs.single().bbox)
        compose.onNodeWithText("Garibaldi Provincial Park").assertExists()

        // Counts as tabs: 15 day hikes, 6 base camps, 1 multi-day trip.
        compose.onNodeWithText("day hikes").assertExists()
        compose.onNodeWithText("base camps").assertExists()
        compose.onNodeWithText("multi-day trip").assertExists()
        compose.onNodeWithText("15 hikes, best first").assertExists()

        // Where to start: a big park's picks.
        compose.onNodeWithText("Start here").assertExists()

        // A card: its numbers, and its trip reports counted.
        compose.onNodeWithText("2 reports").assertExists()

        // Filters: summits, then loops too.
        compose.onNodeWithText("Summits").performClick()
        compose.onNodeWithText("10 hikes match").assertExists()
        compose.onNodeWithText("Loops").performClick()
        compose.onNodeWithText("4 hikes match").assertExists()
        compose.onNodeWithText("Trip reports").performClick()
        compose.onNodeWithText("0 hikes match").assertExists()
        compose.onNodeWithText("No hikes match.").assertExists()
        compose.onNodeWithText("Clear filters").performClick()
        compose.onNodeWithText("15 hikes, best first").assertExists()

        // Another tab: the base camps.
        compose.onNodeWithText("base camps").performClick()
        compose.onNodeWithText("Walk in once, camp, and do day hikes from camp.").assertExists()
        assertTrue(compose.onAllNodesWithText("Garibaldi Lake").fetchSemanticsNodes().isNotEmpty())
        assertEquals(ParkTab.Camps, m.parkTab)
    }

    @Test
    fun aShapeFitsItsSquare() {
        val pts = thumbPoints(listOf(listOf(-123.0, 50.0), listOf(-122.9, 50.0), listOf(-122.9, 50.1)), 56f, 6f)!!
        assertTrue(pts.all { (x, y) -> x in 0f..56f && y in 0f..56f })
        // Longer north-south than east-west at 50°N: the height fills it, pad to pad.
        assertEquals(6f, pts.minOf { it.second }, 0.01f)
        assertEquals(50f, pts.maxOf { it.second }, 0.01f)
        assertEquals(null, thumbPoints(listOf(listOf(0.0, 0.0)), 56f, 6f))
        assertEquals("By the road", quickStop(0.0, "Road's end"))
        assertEquals("0.8 km there and back from Lot 3", quickStop(0.4, "Lot 3 Trailhead"))
    }

    /** A park with few hikes: a summary, every hike in full, "do them all", and parks nearby with more. */
    @Test
    fun aSmallParksCompactSheet() {
        val park = gz(
            """{"park":{"id":"9","name":"Little Park"},"day_hikes":[
                 {"from":"Lot trailhead","dest":"Pond","kind":"loop","route":{"km":2.0,"gain":20,"max_ele":120,"min_ele":100,"line":[[-123.0,49.0],[-123.01,49.01]]}},
                 {"from":"Lot trailhead","dest":"Falls","kind":"loop","route":{"km":1.5,"gain":30,"max_ele":130,"line":[[-123.0,49.0],[-123.0,49.01]]}}]}""".toByteArray(),
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/index.json" -> MockResponse.Builder().body("""{"bundle":"b.bin","parks":{"9":[0,${park.size}]}}""").build()
                "/regions.json" -> MockResponse.Builder().body(
                    """{"countries":[{"name":"Canada","slug":"canada","states":[{"name":"BC","slug":"bc","areas":[
                        {"id":"9","name":"Little Park","bbox":[-123.1,48.9,-122.9,49.1],"hikes":2},
                        {"id":"10","name":"Big Park","bbox":[-123.1,49.1,-122.9,49.3],"hikes":12}]}]}]}""",
                ).build()
                "/b.bin" -> MockResponse.Builder().body(Buffer().write(park)).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
        val repo = RoutesRepository(OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build(), server.url("/").toString().trimEnd('/'))
        kotlinx.coroutines.runBlocking { repo.index() }
        val model = mutableStateOf<PanelModel?>(null)
        val flat = object : ElevationProfiler.DemSource {
            override val minZoom = 0
            override val maxZoom = 14
            override fun tile(z: Int, x: Int, y: Int) = FloatArray(512 * 512) { 1000f }
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) {
                model.value = PanelModel(scope, repo, ElevationProfiler(listOf(flat))) { _, _ -> }.apply { openPark("9", "Little Park") }
            }
            HikerAtlasTheme {
                model.value?.let { m ->
                    Box(Modifier.width(390.dp).verticalScroll(rememberScrollState())) {
                        androidx.compose.foundation.layout.Column {
                            PanelHeader(m, full = true)
                            PanelBody(m) {}
                        }
                    }
                }
            }
        }
        compose.waitUntil(10_000) { (model.value?.panel as? Panel.RouteList)?.doc != null }
        compose.onNodeWithText("Two short, nearly flat loops, each under an hour.").assertExists()
        compose.onNodeWithText("Do them all").assertExists()
        compose.onNodeWithText("3.5 km and about 55 minutes of walking in all.").assertExists()
        compose.onNodeWithText("More hiking nearby").assertExists()
        assertEquals(0, compose.onAllNodesWithText("Find a hike").fetchSemanticsNodes().size)
        compose.onNodeWithText("Big Park").performClick()
        compose.waitUntil(10_000) { (model.value?.panel as? Panel.Failed)?.heading == "Big Park" || (model.value?.panel as? Panel.RouteList)?.heading == "Big Park" }
    }
}
