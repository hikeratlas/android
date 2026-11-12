package com.hikeratlas.android.offline

import androidx.test.core.app.ApplicationProvider
import com.hikeratlas.android.elevation.MapterhornSource
import com.hikeratlas.android.pmtiles.Compression
import com.hikeratlas.android.pmtiles.HttpRangeSource
import com.hikeratlas.android.pmtiles.Pmtiles
import com.hikeratlas.android.pmtiles.RangeSource
import com.hikeratlas.android.routes.RoutesRepository
import com.hikeratlas.android.routes.hikeSlugs
import com.hikeratlas.android.routes.lookup
import com.hikeratlas.android.style.StyleLoader
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads a real hike (Upward Mobility, Raging River SF: ~5 MB) from the
 * published archives and Mapterhorn, and reads it back. Opt in with
 * HIKERATLAS_LIVE=1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LiveOfflineTest {
    private class Counting(private val s: RangeSource) : RangeSource by s {
        val reads = AtomicInteger()
        override fun read(offset: Long, length: Int): ByteArray = s.read(offset, length).also { reads.incrementAndGet() }
    }

    @Test
    fun downloadsAHike() = runBlocking {
        assumeTrue(System.getenv("HIKERATLAS_LIVE") == "1")
        val http = OkHttpClient()
        val routes = RoutesRepository(http)
        val parkId = routes.regions().countries.flatMap { it.states }.flatMap { it.areas }.first { it.slug == "raging-river-state-forest" }.id
        val doc = routes.park(parkId)!!
        val id = hikeSlugs(doc).entries.first { it.value == "upward-mobility" }.key
        val line = lookup(doc, id)!!.route.line

        val basemap = Counting(HttpRangeSource(StyleLoader.BASEMAP_URL, http))
        val overlay = Counting(HttpRangeSource(StyleLoader.ROUTES_URL, http))
        val dem = AtomicInteger()
        OfflineStore(ApplicationProvider.getApplicationContext(), name = null).use { store ->
            val d = AreaDownloader(store, Pmtiles(basemap) { null }, Pmtiles(overlay) { null }, { z, x, y ->
                dem.incrementAndGet()
                MapterhornSource(http).fetch(z, x, y)
            })
            val plan = AreaPlan.hike(line)
            val estimate = d.estimate(plan)
            val area = store.area(OfflineStore.HIKE, id, "Upward Mobility").id
            d.download(area, plan)
            store.finishArea(area)
            val bytes = store.areaById(area)!!.bytes
            println("Upward Mobility: estimated ${megabytes(estimate)}, kept ${megabytes(bytes)}; " +
                "${plan.vectorCount} vector tiles a archive, ${plan.demCount} terrain; " +
                "reads: basemap ${basemap.reads}, routes overlay ${overlay.reads}, terrain ${dem.get()}")
            assertTrue("kept ${megabytes(bytes)}", bytes in 1_000_000L..20_000_000L)

            // The trailhead's z14 tile reads back as the map's vector tile.
            val (x, y) = TileCover.tile(line[0][0], line[0][1], 14)
            val t = store.tile(StyleLoader.BASEMAP_URL, 14, x, y)!!
            assertTrue(Compression.decompress(t.data, t.compression).size > 100)
            assertTrue(store.tile(MapterhornSource.KEY, 12, TileCover.tile(line[0][0], line[0][1], 12).first, TileCover.tile(line[0][0], line[0][1], 12).second) != null)
        }
    }
}
