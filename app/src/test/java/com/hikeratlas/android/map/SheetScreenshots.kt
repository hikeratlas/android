package com.hikeratlas.android.map

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.hikeratlas.android.elevation.ElevationProfiler
import com.hikeratlas.android.routes.RoutesRepository
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.launch
import java.util.zip.GZIPOutputStream

/**
 * Renders the sheets to PNGs under app/build/screenshots, to look at. Not a
 * golden test: nothing is compared. Opt in with SCREENSHOTS=1.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the app's own starts MapLibre, whose native code isn't here.
@Config(sdk = [35], qualifiers = "w390dp-h1400dp-xxhdpi", application = android.app.Application::class)
class SheetScreenshots {
    @get:Rule val compose = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var repo: RoutesRepository
    private val modelState = androidx.compose.runtime.mutableStateOf<PanelModel?>(null)
    private val model get() = modelState.value!!

    /** Rises and falls across a few kilometres, like a hill. */
    private val hill = object : ElevationProfiler.DemSource {
        override val minZoom = 14
        override val maxZoom = 14
        override fun tile(z: Int, x: Int, y: Int): FloatArray {
            val n = 512
            return FloatArray(n * n) { i ->
                val gx = (x * n + i % n).toDouble()
                val gy = (y * n + i / n).toDouble()
                (1500 + 700 * Math.sin(gx / 900) * Math.cos(gy / 1100)).toFloat()
            }
        }
    }

    @Before
    fun setUp() {
        assumeTrue(System.getenv("SCREENSHOTS") == "1")
        val park = javaClass.getResource("/fixtures/garibaldi-park.json")!!.readBytes()
        val bundle = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(park) } }.toByteArray()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/index.json" -> MockResponse.Builder().body(
                    """{"bundle":"b.bin","parks":{"2226817":[0,${bundle.size}]},"paths":{"2226817":"canada/british-columbia/garibaldi-provincial-park"}}""",
                ).build()
                "/b.bin" -> MockResponse.Builder().body(Buffer().write(bundle)).build()
                "/regions.json" -> MockResponse.Builder().body(
                    """{"countries":[{"name":"Canada","states":[{"name":"British Columbia","areas":[{"id":"2226817","name":"Garibaldi Provincial Park"}]}]}]}""",
                ).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
        // Straight to the local server, never through a proxy.
        repo = RoutesRepository(OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build(), server.url("/").toString().trimEnd('/'))
        // Warm up: under Robolectric the first request loads (and instruments)
        // OkHttp and kotlinx.serialization, which can outlast waitUntil.
        kotlinx.coroutines.runBlocking { repo.index() }
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.close()
    }

    private fun shoot(name: String, open: PanelModel.() -> Unit, ready: () -> Boolean) {
        compose.setContent {
            // The model's scope comes from the composition, as in the app:
            // Robolectric gives each test a new main looper.
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            androidx.compose.runtime.LaunchedEffect(Unit) {
                modelState.value = PanelModel(scope, repo, ElevationProfiler(listOf(hill))) { _, _ -> }.apply(open)
            }
            com.hikeratlas.android.ui.HikerAtlasTheme {
                Column(Modifier.width(390.dp).background(Color.White).verticalScroll(rememberScrollState())) {
                    modelState.value?.let { PanelContent(it) {} }
                }
            }
        }
        try {
            compose.waitUntil(timeoutMillis = 60_000) { modelState.value != null && ready() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("still showing ${modelState.value?.panel ?: "nothing"}", e)
        }
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("wrote ${out.absolutePath}")
    }

    /** The real sheet over a phone-sized map area, settled at [stop]. */
    private fun shootSheet(name: String, open: PanelModel.() -> Unit, stop: Stop, ready: () -> Boolean) {
        val sheet = AnchoredDraggableState(Stop.Peek)
        var sheetScope: kotlinx.coroutines.CoroutineScope? = null
        compose.setContent {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            sheetScope = scope
            androidx.compose.runtime.LaunchedEffect(Unit) {
                modelState.value = PanelModel(scope, repo, ElevationProfiler(listOf(hill))) { _, _ -> }.apply(open)
            }
            var container by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
            var header by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
            com.hikeratlas.android.ui.HikerAtlasTheme {
                androidx.compose.foundation.layout.Box(
                    Modifier.size(390.dp, 844.dp).background(Color(0xFFD3EFDF)).onSizeChanged { container = it.height },
                ) {
                    val m = modelState.value
                    if (m != null && container > 0) {
                        MapSheet(
                            state = sheet, containerPx = container, peekPx = header, onHeaderHeight = { header = it },
                            halfAndFull = true,
                            header = { PanelHeader(m, full = sheet.settledValue == Stop.Full) },
                            body = { PanelBody(m) {} },
                        )
                    }
                }
            }
        }
        compose.waitUntil(timeoutMillis = 60_000) { modelState.value != null && ready() && sheet.anchors.hasPositionFor(Stop.Peek) }
        compose.waitUntil(timeoutMillis = 10_000) { sheet.anchors.hasPositionFor(stop) }
        // On the composition's scope: the test runs on the main thread.
        compose.runOnIdle { sheetScope!!.launch { sheet.snapTo(stop) } }
        compose.waitUntil(timeoutMillis = 10_000) { sheet.settledValue == stop }
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("wrote ${out.absolutePath}")
    }

    @Test
    fun hikeSheetPeek() = shootSheet("sheet-hike-peek", { openRoute("2226817", "2226817/day/5") }, Stop.Peek) {
        (modelState.value?.panel as? Panel.Detail)?.profile is ProfileState.Ready
    }

    @Test
    fun hikeSheetHalf() = shootSheet("sheet-hike-half", { openRoute("2226817", "2226817/day/5") }, Stop.Half) {
        (modelState.value?.panel as? Panel.Detail)?.profile is ProfileState.Ready
    }

    @Test
    fun hikeSheetFull() = shootSheet("sheet-hike-full", { openRoute("2226817", "2226817/day/5") }, Stop.Full) {
        (modelState.value?.panel as? Panel.Detail)?.profile is ProfileState.Ready
    }

    @Test
    fun parkSheetPeek() = shootSheet("sheet-park-peek", { openPark("2226817", "Garibaldi PP") }, Stop.Peek) {
        modelState.value?.panel is Panel.RouteList
    }

    @Test
    fun trailSheet() = shootSheet(
        "sheet-trail",
        // No box: no ways to read here, so just its hikes.
        { openTrail("Black Tusk Trail", 2.4, "Garibaldi PP", "British Columbia", null) },
        Stop.Full,
    ) { (modelState.value?.panel as? Panel.Trail)?.loading == false }

    @Test
    fun planner() {
        // A trail across one z14 tile near Garibaldi; every other tile empty.
        val (tx, ty) = 2597 to 5561
        val trail = com.hikeratlas.android.contours.MvtLayer("path").apply {
            add(mapOf("name" to "x"), listOf(intArrayOf(200, 3000, 1500, 2000, 2600, 2300, 3900, 1000)))
        }.encode()
        fun ll(x: Double, y: Double): List<Double> {
            val n = 4096.0 * (1 shl 14)
            val wx = tx * 4096 + x
            val wy = ty * 4096 + y
            return listOf(wx / n * 360 - 180, Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * wy / n)))))
        }
        var planner: com.hikeratlas.android.routing.Planner? = null
        compose.setContent {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val p = androidx.compose.runtime.remember {
                com.hikeratlas.android.routing.Planner(
                    scope,
                    fetch = { z, tiles -> tiles.associateWith { (x, y) -> if (z == 14 && x == tx && y == ty) trail else null } },
                    profiler = ElevationProfiler(listOf(hill)),
                ).also { planner = it }
            }
            com.hikeratlas.android.ui.HikerAtlasTheme {
                androidx.compose.foundation.layout.Box(Modifier.size(390.dp, 844.dp).background(Color(0xFFD3EFDF))) {
                    PlannerBar(p, onDone = {}, modifier = Modifier.padding(12.dp))
                    PlannerPanel(p, onScrub = {}, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
                }
            }
        }
        compose.waitUntil(5_000) { planner != null }
        compose.runOnIdle {
            planner!!.add(ll(200.0, 3000.0), 13.0)
            planner!!.add(ll(2600.0, 2300.0), 13.0)
            planner!!.add(ll(3900.0, 1000.0), 13.0)
        }
        compose.waitUntil(60_000) { planner!!.points.size == 3 && planner!!.profile != null }
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = File("build/screenshots/planner.png").apply { parentFile!!.mkdirs() }
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun park() {
        shoot("park", { openPark("2226817", "Garibaldi PP") }) { model.panel is Panel.RouteList }
    }

    @Test
    fun hike() {
        shoot("hike", { openRoute("2226817", "2226817/day/5") }) { (model.panel as? Panel.Detail)?.profile is ProfileState.Ready }
    }

    @Test
    fun baseCamp() {
        shoot("base-camp", { openRoute("2226817", "2226817/base/1") }) { (model.panel as? Panel.Detail)?.profile is ProfileState.Ready }
    }
}
