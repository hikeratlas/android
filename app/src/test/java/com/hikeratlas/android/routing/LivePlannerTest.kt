package com.hikeratlas.android.routing

import com.hikeratlas.android.pmtiles.HttpRangeSource
import com.hikeratlas.android.pmtiles.Pmtiles
import com.hikeratlas.android.pmtiles.RangeSource
import com.hikeratlas.android.style.StyleLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plans Rubble Creek trailhead to Taylor Meadows from the published basemap,
 * through Pmtiles' merged reads. Opt in with HIKERATLAS_LIVE=1.
 */
class LivePlannerTest {
    @Test
    fun rubbleCreekToTaylorMeadows() = runBlocking {
        assumeTrue(System.getenv("HIKERATLAS_LIVE") == "1")
        val reads = AtomicInteger()
        val http = HttpRangeSource(StyleLoader.BASEMAP_URL, OkHttpClient())
        val counting = object : RangeSource {
            override val key = http.key
            override fun read(offset: Long, length: Int): ByteArray { reads.incrementAndGet(); return http.read(offset, length) }
        }
        val archive = Pmtiles(counting)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val planner = Planner(scope, fetch = { z, tiles -> archive.tiles(z, tiles) }, profiler = null)
        planner.add(listOf(-123.12024, 49.95755), 12.0) // routes at z14
        planner.add(listOf(-123.07652, 49.95141), 12.0)
        withTimeout(120_000) { while (planner.points.size < 2 && planner.error == null) delay(100) }
        assertNull(planner.error)
        // The website's routing gives 7,082.0 m on the same tiles.
        assertEquals(7082.0, planner.metres, 1.0)
        // The leg's ~25 tiles came in a few merged reads, not one each.
        println("reads: ${reads.get()}")
        assertTrue("reads: ${reads.get()}", reads.get() < 20)
        scope.cancel()
    }
}
