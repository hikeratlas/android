package com.hikeratlas.android.elevation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTest {
    /** A DEM that rises 1 m per metre east, from 0 at a fixed longitude. */
    private class Slope(val tiles: MutableList<String> = mutableListOf()) : ElevationProfiler.DemSource {
        override val minZoom = 0
        override val maxZoom = 14
        override fun tile(z: Int, x: Int, y: Int): FloatArray? {
            tiles += "$z/$x/$y"
            if (z < 12) return null // force an ancestor lookup to be tested
            val size = ElevationProfiler.SIZE
            val n = (1 shl z).toDouble()
            return FloatArray(size * size) { i ->
                val px = i % size
                val lng = (x + (px + 0.5) / size) / n * 360 - 180
                ((lng + 123.0) * 71_700).toFloat() // ~71.7 km per degree at 50°N
            }
        }
    }

    private fun profiler(dem: ElevationProfiler.DemSource) = ElevationProfiler(listOf(dem))

    @Test
    fun resamplesEveryStep() {
        val pts = ElevationProfiler.resample(listOf(listOf(-123.0, 50.0), listOf(-122.99, 50.0)), 10.0)
        val length = ElevationProfiler.haversine(listOf(-123.0, 50.0), listOf(-122.99, 50.0))
        assertEquals(length, pts.last().first, 1e-6)
        assertEquals(10.0, pts[1].first, 1e-9)
        assertEquals((length / 10).toInt() + 2, pts.size)
    }

    @Test
    fun measuresClimbAlongASlope() {
        val p = profiler(Slope()).profile(listOf(listOf(-123.0, 50.0), listOf(-122.99, 50.0)))
        // 0.01° east at 50°N is ~715 m, rising ~1 m per metre.
        assertEquals(p.length, p.high - p.low, 15.0)
        assertEquals(p.high - p.low, p.gain, 15.0)
        assertEquals(0.0, p.loss, 0.0)
    }

    @Test
    fun ignoresWobblesBelowTheThreshold() {
        val flat = object : ElevationProfiler.DemSource {
            override val minZoom = 14
            override val maxZoom = 14
            override fun tile(z: Int, x: Int, y: Int) = FloatArray(512 * 512) { if ((it % 512) % 2 == 0) 100f else 105f }
        }
        val p = profiler(flat).profile(listOf(listOf(-123.0, 50.0), listOf(-122.99, 50.0)))
        assertEquals(0.0, p.gain, 0.0)
        assertEquals(0.0, p.loss, 0.0)
    }

    @Test
    fun fallsBackToAncestorsAndCachesGrids() {
        val dem = Slope()
        val prof = profiler(dem)
        prof.profile(listOf(listOf(-123.0, 50.0), listOf(-122.999, 50.0)))
        val asked = dem.tiles.size
        assertTrue("asked z14 then fell back: ${dem.tiles}", dem.tiles.first().startsWith("14/"))
        prof.profile(listOf(listOf(-123.0, 50.0), listOf(-122.999, 50.0)))
        assertEquals("cached", asked, dem.tiles.size)
    }

    @Test
    fun offlineAProfileComesFromTheCoarserTilesKept() {
        // As offline: z13-14 can't be fetched (not kept), z12 is on the phone.
        val slope = Slope()
        val asked = mutableListOf<String>()
        val offline = object : ElevationProfiler.DemSource {
            override val minZoom = 0
            override val maxZoom = 14
            override fun tile(z: Int, x: Int, y: Int): FloatArray? {
                asked += "$z/$x/$y"
                if (z > 12) throw java.io.IOException("Unable to resolve host")
                return slope.tile(z, x, y)
            }
        }
        val prof = profiler(offline)
        val line = listOf(listOf(-123.0, 50.0), listOf(-122.99, 50.0))
        val p = prof.profile(line)
        assertEquals(p.length, p.high - p.low, 30.0)
        // The stand-in is kept a while: the next profile doesn't try z14 for each sample again.
        val n = asked.size
        prof.profile(line)
        assertEquals(n, asked.size)
    }

    @Test
    fun withNothingKeptAProfileFailsWithTheFetchsError() {
        val down = object : ElevationProfiler.DemSource {
            override val minZoom = 0
            override val maxZoom = 14
            override fun tile(z: Int, x: Int, y: Int): FloatArray? = throw java.io.IOException("Unable to resolve host")
        }
        val e = runCatching { profiler(down).profile(listOf(listOf(-123.0, 50.0), listOf(-122.99, 50.0))) }.exceptionOrNull()
        assertEquals("Unable to resolve host", e?.message)
    }

    @Test
    fun fetchesAProfilesTilesAtOnce() {
        // Each tile takes 300 ms to come; a line across six z14 tiles.
        val slow = object : ElevationProfiler.DemSource {
            override val minZoom = 14
            override val maxZoom = 14
            override fun tile(z: Int, x: Int, y: Int): FloatArray {
                Thread.sleep(300)
                return FloatArray(512 * 512) { 100f }
            }
        }
        val line = listOf(listOf(-123.0, 50.0), listOf(-122.86, 50.0)) // ~10 km: 6+ tiles across
        val t0 = System.nanoTime()
        profiler(slow).profile(line)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms: tiles were fetched one at a time", ms < 900)
    }

    @Test
    fun nlDemCoversNewfoundlandOnly() {
        fun at(lon: Double, lat: Double, z: Int): Boolean {
            val (x, y) = ElevationProfiler.lngLatToTile(lon, lat, z)
            return NlDemSource.covers(z, x.toInt(), y.toInt())
        }
        assertTrue(at(-57.75, 49.6, 13)) // Gros Morne
        assertTrue(at(-52.71, 47.56, 13)) // St. John's
        assertTrue(!at(-123.39, 48.77, 13)) // Ruckle, BC
        assertTrue(!at(-63.57, 44.65, 13)) // Halifax
    }

    @Test
    fun decodesTerrarium() {
        // R=128, G=0, B=0 is 0 m; R=128 G=100 B=128 is 100.5 m.
        val px = intArrayOf(0xFF800000.toInt(), 0xFF806480.toInt())
        val e = ElevationProfiler.terrarium(px)
        assertEquals(0f, e[0], 0f)
        assertEquals(100.5f, e[1], 0f)
    }
}
