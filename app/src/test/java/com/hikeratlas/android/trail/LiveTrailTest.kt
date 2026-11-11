package com.hikeratlas.android.trail

import com.hikeratlas.android.pmtiles.HttpRangeSource
import com.hikeratlas.android.pmtiles.Pmtiles
import com.hikeratlas.android.routes.RoutesRepository
import com.hikeratlas.android.routes.itemsFromPark
import com.hikeratlas.android.routes.lookup
import com.hikeratlas.android.search.SearchApi
import com.hikeratlas.android.style.StyleLoader
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Black Tusk Trail from search, the basemap and the routes. Opt in with HIKERATLAS_LIVE=1. */
class LiveTrailTest {
    @Test
    fun blackTuskTrail() = runBlocking {
        assumeTrue(System.getenv("HIKERATLAS_LIVE") == "1")
        val http = OkHttpClient()
        val item = SearchApi(http).search("black tusk trail", 49.95, -123.05).first { it.kind == "trail" }
        assertEquals("Garibaldi PP", item.park)

        val box = doubleArrayOf(item.min_lon!!, item.min_lat!!, item.max_lon!!, item.max_lat!!)
        val tiles = TrailGeometry.tilesFor(box)!!
        val archive = Pmtiles(HttpRangeSource(StyleLoader.BASEMAP_URL, http))
        val g = TrailGeometry.build(item.name, tiles.map { (x, y) -> Triple(x, y, archive.tile(TrailGeometry.Z, x, y)) })
        // In the map data (as of 2026-11) it's in pieces, as the website
        // finds too: then only its high and low points are shown.
        assertTrue("found its ways", g.lines.isNotEmpty())
        println("Black Tusk Trail: ${g.lines.size} ways, single ${g.single?.size}, longest ${g.longest?.size}")

        // Its park, and Garibaldi's hikes that walk it.
        val routes = RoutesRepository(http)
        val parkId = routes.parkIdFor(item.state, item.park!!)
        assertEquals("2226817", parkId)
        val doc = routes.park(parkId!!)!!
        val hikes = itemsFromPark(doc).filter { (lookup(doc, it.id)?.route?.via ?: emptyList()).contains(item.name) }
        assertTrue(hikes.any { it.title.startsWith("The Black Tusk") })
    }
}
