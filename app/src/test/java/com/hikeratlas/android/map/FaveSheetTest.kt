package com.hikeratlas.android.map

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import com.hikeratlas.android.atproto.AccountState
import com.hikeratlas.android.elevation.ElevationProfiler
import com.hikeratlas.android.routes.RoutesRepository
import com.hikeratlas.android.ui.AccountActions
import com.hikeratlas.android.ui.FaveActions
import com.hikeratlas.android.ui.HikerAtlasTheme
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** The hike sheet's heart: on a route with a key from the build, and not on one without. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class FaveSheetTest {
    @get:Rule val compose = createComposeRule()

    private val server = MockWebServer()
    private val key = "1E57E06gbDEBBBICDGF.F55FRcODEp_"

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

    private fun hearts(id: String): Int {
        val bundle = bundle()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/index.json" -> MockResponse.Builder().body("""{"bundle":"b.bin","parks":{"2226817":[0,${bundle.size}]}}""").build()
                "/b.bin" -> MockResponse.Builder().body(Buffer().write(bundle)).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
        val repo = RoutesRepository(OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build(), server.url("/").toString().trimEnd('/'))
        kotlinx.coroutines.runBlocking { repo.index() } // warm up OkHttp under Robolectric
        val model = mutableStateOf<PanelModel?>(null)
        val flat = object : ElevationProfiler.DemSource {
            override val minZoom = 0
            override val maxZoom = 14
            override fun tile(z: Int, x: Int, y: Int) = FloatArray(512 * 512) { 1000f }
        }
        val faves = FaveActions(
            AccountActions(AccountState.In("did:plc:x", null, null), signIn = { "" }, openPage = {}, signOut = {}),
            isFaved = { false }, setFave = { _, _ -> },
        )
        compose.setContent {
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) {
                model.value = PanelModel(scope, repo, ElevationProfiler(listOf(flat))) { _, _ -> }.apply { openRoute("2226817", id) }
            }
            HikerAtlasTheme {
                model.value?.let { PanelHeader(it, full = false, faves = faves) }
            }
        }
        compose.waitUntil(30_000) { model.value?.panel is Panel.Detail }
        compose.waitForIdle()
        return compose.onAllNodesWithContentDescription("Save to your faves").fetchSemanticsNodes().size
    }

    @Test
    fun aRouteWithAKeyHasAHeart() = assertEquals(1, hearts("2226817/day/5"))

    @Test
    fun aRouteWithoutOneHasNone() = assertEquals(0, hearts("2226817/day/4"))
}
