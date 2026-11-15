package com.hikeratlas.android.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.hikeratlas.android.atproto.IndexerClient
import com.hikeratlas.android.elevation.ElevationProfiler
import com.hikeratlas.android.routes.RoutesRepository
import com.hikeratlas.android.ui.HikerActions
import com.hikeratlas.android.ui.HikerAtlasTheme
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
import java.util.Collections
import java.util.zip.GZIPOutputStream

/** The hike sheet asks the indexer only about a route the build gave a key, and shows its counts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class HikersSheetTest {
    @get:Rule val compose = createComposeRule()

    private val server = MockWebServer()
    private val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
    private val key = "1E57E06gbDEBBBICDGF.F55FRcODEp_"
    private val asked = Collections.synchronizedList(mutableListOf<String>())

    @After
    fun tearDown() = server.close()

    /** Garibaldi's routes (a build from before keys), with a key given to day hike 5 only. */
    private fun bundle(): ByteArray {
        val doc = Json.parseToJsonElement(javaClass.getResource("/fixtures/garibaldi-park.json")!!.readText()).jsonObject
        val days = doc["day_hikes"]!!.jsonArray.mapIndexed { i, h ->
            if (i != 5) h else {
                val route = JsonObject(h.jsonObject["route"]!!.jsonObject + ("route_key" to JsonPrimitive(key)))
                JsonObject(h.jsonObject + ("route" to route))
            }
        }
        val park = JsonObject(doc + ("day_hikes" to JsonArray(days))).toString().toByteArray()
        return ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(park) } }.toByteArray()
    }

    private fun open(id: String): PanelModel {
        val bundle = bundle()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/index.json" -> MockResponse.Builder().body("""{"bundle":"b.bin","parks":{"2226817":[0,${bundle.size}]}}""").build()
                "/b.bin" -> MockResponse.Builder().body(Buffer().write(bundle)).build()
                "/xrpc/com.hikeratlas.temp.getRouteStats" -> {
                    asked += request.url.queryParameter("keys")!!
                    MockResponse.Builder().body("""{"routes":[{"routeKey":"$key","faves":12,"reviews":0,"tracks":0}]}""").build()
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
        val repo = RoutesRepository(http, server.url("/").toString().trimEnd('/'))
        kotlinx.coroutines.runBlocking { repo.index() } // warm up OkHttp under Robolectric
        val model = mutableStateOf<PanelModel?>(null)
        val flat = object : ElevationProfiler.DemSource {
            override val minZoom = 0
            override val maxZoom = 14
            override fun tile(z: Int, x: Int, y: Int) = FloatArray(512 * 512) { 1000f }
        }
        val hikers = HikerActions(
            IndexerClient(http, server.url("/").toString()),
            pdsOf = { null }, getTrack = { _, _ -> null }, gpx = { _, _, _ -> "" },
            openUrl = {}, showTrack = { _, _, _ -> }, shareGpx = { _, _ -> }, writes = emptyFlow(),
        )
        compose.setContent {
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) {
                model.value = PanelModel(scope, repo, ElevationProfiler(listOf(flat))) { _, _ -> }.apply { openRoute("2226817", id) }
            }
            HikerAtlasTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    model.value?.let { PanelBody(it, hikers = hikers, onScrub = {}) }
                }
            }
        }
        compose.waitUntil(30_000) { model.value?.panel is Panel.Detail }
        return model.value!!
    }

    @Test
    fun aRouteWithAKeyShowsItsCounts() {
        open("2226817/day/5")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("12 faves").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(key), asked.toList())
    }

    @Test
    fun aRouteWithoutOneAsksNothing() {
        open("2226817/day/4")
        compose.waitForIdle()
        assertTrue(asked.isEmpty())
        assertTrue(compose.onAllNodesWithText("faves", substring = true).fetchSemanticsNodes().isEmpty())
    }
}
