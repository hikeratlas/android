package com.hikeratlas.android.contours

import com.hikeratlas.android.elevation.DemTiles
import com.hikeratlas.android.elevation.ElevationProfiler
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ContoursTest {
    @Test
    fun aConeGivesClosedRings() {
        // A cone, 100 m high at the centre of a 41 x 41 grid, falling 5 m per cell.
        val n = 41
        val grid = FloatArray(n * n) { k -> (100 - 5 * hypot((k % n - 20).toDouble(), (k / n - 20).toDouble())).toFloat() }
        val levels = Isolines.trace(grid, n, n, 20.0)
        // The corners fall below 0 m, so there are levels down to -40 too;
        // the peak sample is exactly 100 m, which counts as above 100.
        assertEquals(listOf(-40.0, -20.0, 0.0, 20.0, 40.0, 60.0, 80.0, 100.0), levels.keys.toList())
        // Rings that fit inside the grid (radius under 20 cells) close.
        for ((ele, lines) in levels) {
            if (ele < 20 || ele >= 100) continue
            assertEquals("one line at $ele", 1, lines.size)
            val pts = lines.single()
            // Closed: it ends where it starts.
            assertEquals(pts[0], pts[pts.size - 2], 1e-4f)
            assertEquals(pts[1], pts[pts.size - 1], 1e-4f)
            // A circle of the right radius.
            val r = (100 - ele) / 5
            for (k in pts.indices step 2) {
                assertEquals(r, hypot(pts[k] - 20.0, pts[k + 1] - 20.0), 0.3)
            }
        }
    }

    @Test
    fun aSlopeGivesOneLinePerLevelAcrossTheGrid() {
        val n = 10
        val grid = FloatArray(n * n) { k -> (k % n * 10).toFloat() } // rises east, 10 m a cell
        val lines = Isolines.traceLevel(grid, n, n, 35.0)
        assertEquals(1, lines.size)
        val pts = lines.single()
        assertEquals(n * 2, pts.size) // one point on every row
        for (k in pts.indices step 2) assertEquals(3.5f, pts[k], 1e-4f)
    }

    @Test
    fun parsesMaplibreContourOptions() {
        val url = ("https://contours.hikeratlas.invalid/12/1/2?buffer=1&overzoom=1&subsampleBelow=256&contourLayer=contours-15" +
            "&elevationKey=ele&extent=4096&levelKey=level&thresholds=11*100*500~12*50*200~13*20*100~14*10*50").toHttpUrl()
        val o = ContourServer.Options.parse(url)
        assertEquals("contours-15", o.layer)
        assertEquals(1, o.overzoom)
        assertEquals(listOf(50.0, 200.0), o.at(12))
        assertEquals(listOf(10.0, 50.0), o.at(16))
        assertNull(o.at(10))
    }

    /** A DEM rising 1 m per pixel east across the world, at every zoom. */
    private val ramp = object : ElevationProfiler.DemSource {
        override val minZoom = 0
        override val maxZoom = 14
        override fun tile(z: Int, x: Int, y: Int) = FloatArray(512 * 512) { (x * 512 + it % 512).toFloat() % 1000 }
    }

    @Test
    fun contourTilesAreVectorTilesWithLevels() {
        val server = ContourServer(DemTiles(listOf(ramp)))
        val o = ContourServer.Options(mapOf(13 to listOf(20.0, 100.0)), layer = "contours-15", overzoom = 1)
        // DEM z12 tile 0's left quarter: 0..255 m, so contours at 20, 40, ... 240 (100 and 200 major).
        val bytes = server.tile(13, 0, 0, o)!!
        val features = Mvt.decode(bytes)
        assertEquals(setOf("contours-15"), features.map { it.layer }.toSet())
        val byEle = features.associateBy { it.props["ele"] }
        assertEquals((1..12).map { it * 20L }.toSet(), byEle.keys)
        assertEquals(1L, byEle[100L]!!.props["level"])
        assertEquals(0L, byEle[120L]!!.props["level"])
        // 120 m is the centre of DEM pixel 120, 120.5 px from the tile's
        // edge: 120.5 / 256 of the tile is x = 1928 of 4096. (Lines left of
        // the tile are in its buffer, where this DEM wraps around the world.)
        val xs = byEle[120L]!!.lines.flatMap { l -> l.indices.filter { it % 2 == 0 }.map { l[it] } }.filter { it >= 0 }.toSet()
        assertEquals(setOf(1928), xs)
    }

    @Test
    fun flatGroundHasNoTile() {
        val flat = object : ElevationProfiler.DemSource {
            override val minZoom = 0
            override val maxZoom = 14
            override fun tile(z: Int, x: Int, y: Int) = FloatArray(512 * 512) { 5f }
        }
        assertNull(ContourServer(DemTiles(listOf(flat))).tile(13, 0, 0, ContourServer.Options(mapOf(13 to listOf(20.0, 100.0)), overzoom = 1)))
    }

    @Test
    fun missingTilesComeFromAnAncestor() {
        val shallow = object : ElevationProfiler.DemSource {
            override val minZoom = 0
            override val maxZoom = 14
            override fun tile(z: Int, x: Int, y: Int) =
                if (z > 10) null else FloatArray(512 * 512) { (it % 512).toFloat() }
        }
        val h = DemTiles(listOf(shallow)).heights(11, 1, 0)
        assertTrue(!h.exact)
        // Tile 11/1/0 is the right half of 10/0/0: heights 256..511, upsampled 2x.
        assertEquals(256f, h.h[0], 0.6f)
        assertEquals(511f, h.h[511], 0.6f)
    }
}
