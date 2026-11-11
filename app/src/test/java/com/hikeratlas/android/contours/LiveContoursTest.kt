package com.hikeratlas.android.contours

import com.hikeratlas.android.elevation.DemServer
import com.hikeratlas.android.elevation.DemTiles
import com.hikeratlas.android.elevation.MapterhornSource
import com.hikeratlas.android.elevation.NlDemSource
import com.hikeratlas.android.pmtiles.TileServer
import com.hikeratlas.android.style.StyleLoader
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The contours' whole path, as MapLibre takes it: the published style,
 * rewritten by StyleLoader; its contour tile URL for a tile over Garibaldi;
 * that URL through the app's interceptors. Opt in with HIKERATLAS_LIVE=1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LiveContoursTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun garibaldiHasContours() {
        assumeTrue(System.getenv("HIKERATLAS_LIVE") == "1")
        val http = OkHttpClient()
        val sources = listOf(NlDemSource(http), MapterhornSource(http))
        val dem = DemTiles(sources)
        val mapClient = http.newBuilder()
            .addInterceptor(TileServer).addInterceptor(DemServer(dem, sources)).addInterceptor(ContourServer(dem))
            .build()

        val style = JSONObject(runBlocking { StyleLoader(http, tmp.root).load() })
        val template = style.getJSONObject("sources").getJSONObject("contours").getJSONArray("tiles").getString(0)
        assertTrue(template, template.startsWith("https://${ContourServer.HOST}/{z}/{x}/{y}?"))
        // The contour layers are still in the style.
        val layers = style.getJSONArray("layers")
        assertTrue((0 until layers.length()).any { layers.getJSONObject(it).optString("source") == "contours" })

        // The Black Tusk at z13.
        val url = template.replace("{z}", "13").replace("{x}", "1297").replace("{y}", "2779")
        mapClient.newCall(Request.Builder().url(url).build()).execute().use { r ->
            assertEquals(200, r.code)
            val features = Mvt.decode(r.body.bytes())
            assertEquals(setOf("contours-15"), features.map { it.layer }.toSet())
            val eles = features.mapNotNull { it.props["ele"] }
            // 20 m contours from the meadows to near the summit.
            assertTrue("elevations: ${eles.sorted()}", eles.size > 20 && eles.all { it % 20 == 0L } && eles.max() > 2000)
        }
    }
}
