package com.hikeratlas.android.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * A hike's sheet keeps its height from tap to loaded: the loading
 * placeholder is laid out as the hike's header is, so the sheet doesn't jump.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class SheetHeightTest {
    @get:Rule val compose = createComposeRule()

    private val server = MockWebServer()

    @After
    fun tearDown() = server.close()

    @Test
    fun loadingAHikeKeepsTheHeadersHeight() {
        val park = javaClass.getResource("/fixtures/garibaldi-park.json")!!.readBytes()
        val bundle = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(park) } }.toByteArray()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/index.json" -> MockResponse.Builder().body("""{"bundle":"b.bin","parks":{"2226817":[0,${bundle.size}]}}""").build()
                // Slow, so the loading state is seen.
                "/b.bin" -> MockResponse.Builder().body(Buffer().write(bundle)).headersDelay(1, TimeUnit.SECONDS).build()
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
            override fun tile(z: Int, x: Int, y: Int) = FloatArray(512 * 512) { 1000f + it % 512 }
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) {
                model.value = PanelModel(scope, repo, ElevationProfiler(listOf(flat))) { _, _ -> }.apply {
                    openRoute("2226817", "2226817/day/5", hint = HikeHint("The Black Tusk from Rubble Creek Trailhead", "Day hike · 23.6 km · ↑ 1,770 m"))
                }
            }
            HikerAtlasTheme {
                model.value?.let { m ->
                    Box(Modifier.width(390.dp)) {
                        PanelHeader(m, full = false)
                    }
                }
            }
        }
        // (onSizeChanged wouldn't do: it only fires when the size changes.)
        fun height() = compose.onRoot().fetchSemanticsNode().size.height
        compose.waitUntil(10_000) { model.value?.panel is Panel.Loading }
        compose.waitForIdle()
        val loading = height()
        compose.waitUntil(30_000) { (model.value?.panel as? Panel.Detail)?.profile is ProfileState.Ready }
        compose.waitForIdle()
        assertTrue(loading > 0)
        assertEquals("loading vs loaded", loading, height())
    }
}
