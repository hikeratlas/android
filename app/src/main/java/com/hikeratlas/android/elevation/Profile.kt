package com.hikeratlas.android.elevation

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Elevation profiles from the same DEM as the map's hillshade (as the
 * website's app/routing/elevation.ts): hikeratlas/nl-dem's 5 m tiles over
 * Newfoundland, else Mapterhorn's. Both are 512 px Terrarium tiles.
 */
class ElevationProfiler(private val sources: List<DemSource>) {
    /** A tile's elevations, row by row, or null where the source has no tile. */
    interface DemSource {
        val minZoom: Int
        val maxZoom: Int
        fun tile(z: Int, x: Int, y: Int): FloatArray?

        /** The tile as published (a Terrarium image), where the source can give it. */
        fun raw(z: Int, x: Int, y: Int): ByteArray? = null
    }

    data class Profile(
        /** Metres along the line, and elevation there. */
        val distances: DoubleArray,
        val elevations: DoubleArray,
        /** Where each point is, [lng, lat]. */
        val coords: List<DoubleArray>,
        val gain: Double,
        val loss: Double,
    ) {
        val low get() = elevations.min()
        val high get() = elevations.max()
        val length get() = distances.last()
    }

    /** A tile's elevations; a stand-in (a coarser tile, as a finer one failed) is used only [until] then. */
    private class Grid(val z: Int, val x: Int, val y: Int, val h: FloatArray, val until: Long = Long.MAX_VALUE)

    // Least recently used first out: each grid is 1 MB.
    private val grids = object : LinkedHashMap<Long, Grid>(MAX_CACHED, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Grid>) = size > MAX_CACHED
    }

    /** How many tiles' grids are kept now. */
    fun cachedGrids(): Int = synchronized(grids) { grids.size }

    /**
     * Samples every [step] metres. Climbs and descents count only once they
     * exceed [threshold] metres, so DEM noise doesn't add phantom climb.
     */
    fun profile(line: List<List<Double>>, step: Double = 10.0, threshold: Double = 10.0): Profile {
        require(line.size >= 2) { "a line needs two points" }
        // A long line (a flight, a long trip) is sampled further apart, so it
        // isn't tens of thousands of samples.
        var length = 0.0
        for (i in 1 until line.size) length += haversine(line[i - 1], line[i])
        val samples = resample(line, maxOf(step, length / MAX_SAMPLES))
        // The finest zoom whose tiles along it number at most MAX_TILES: a
        // hike reads z14; 200 km reads z11 or so, still ~40 m pixels. Each
        // tile is a 1 MB grid, and a 200 km line was ~170 of them at z14.
        var z = DEM_Z
        var tiles = tilesAt(samples, z)
        while (tiles.size > MAX_TILES && z > MIN_Z) {
            z--
            tiles = tilesAt(samples, z)
        }
        // Fetch them all at once (one round trip's wait, not one each), and
        // hold them while measuring: the shared cache may let them go.
        val held = java.util.concurrent.ConcurrentHashMap<Long, Grid>()
        if (tiles.size > 1) {
            FETCHERS.invokeAll(tiles.map { (x, y) -> java.util.concurrent.Callable { runCatching { held[key(z, x, y)] = grid(z, x, y) } } })
        }
        val gridAt = { x: Int, y: Int -> held.getOrPut(key(z, x, y)) { grid(z, x, y) } }
        val elevations = DoubleArray(samples.size) { elevationAt(samples[it].second, z, gridAt) }
        var gain = 0.0
        var loss = 0.0
        var ref = elevations[0]
        for (e in elevations) {
            if (e - ref >= threshold) { gain += e - ref; ref = e } else if (ref - e >= threshold) { loss += ref - e; ref = e }
        }
        return Profile(DoubleArray(samples.size) { samples[it].first }, elevations, samples.map { it.second }, gain, loss)
    }

    private fun tilesAt(samples: List<Pair<Double, DoubleArray>>, z: Int) =
        samples.map { (_, p) -> lngLatToTile(p[0], p[1], z).let { (tx, ty) -> floor(tx).toInt() to floor(ty).toInt() } }.distinct()

    private fun key(z: Int, x: Int, y: Int) = (z.toLong() shl 58) or (x.toLong() shl 29) or y.toLong()

    fun elevationAt(p: DoubleArray): Double = elevationAt(p, DEM_Z) { x, y -> grid(DEM_Z, x, y) }

    private fun elevationAt(p: DoubleArray, zoom: Int, gridAt: (Int, Int) -> Grid): Double {
        val (tx, ty) = lngLatToTile(p[0], p[1], zoom)
        val g = gridAt(floor(tx).toInt(), floor(ty).toInt())
        val scale = (1 shl (zoom - g.z)).toDouble()
        // Pixel coordinates within the grid's tile, between pixel centres.
        val fx = (tx / scale - g.x) * SIZE - 0.5
        val fy = (ty / scale - g.y) * SIZE - 0.5
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        fun at(x: Int, y: Int) = g.h[min(SIZE - 1, max(0, y)) * SIZE + min(SIZE - 1, max(0, x))].toDouble()
        val u = fx - x0
        val v = fy - y0
        return (at(x0, y0) * (1 - u) + at(x0 + 1, y0) * u) * (1 - v) + (at(x0, y0 + 1) * (1 - u) + at(x0 + 1, y0 + 1) * u) * v
    }

    /**
     * The grid for a tile at [zoom] (DEM_Z for a hike): the first source's, at
     * the deepest zoom it has up to it, falling back to ancestors where a tile
     * is missing.
     *
     * A tile that can't be fetched (offline, say, past what an offline area
     * kept) falls back to an ancestor too, so a profile still comes from
     * what's on the phone. That stand-in is kept a minute, then the finer
     * tile is tried again.
     */
    private fun grid(zoom: Int, x: Int, y: Int): Grid {
        val key = key(zoom, x, y)
        synchronized(grids) { grids[key] }?.let { if (it.until > System.currentTimeMillis()) return it }
        var failed: java.io.IOException? = null
        for (s in sources) {
            for (z in min(zoom, s.maxZoom) downTo s.minZoom) {
                val shift = zoom - z
                val h = try {
                    s.tile(z, x shr shift, y shr shift)
                } catch (e: java.io.IOException) {
                    failed = failed ?: e
                    null
                } ?: continue
                val until = if (failed == null) Long.MAX_VALUE else System.currentTimeMillis() + STAND_IN_MS
                val g = Grid(z, x shr shift, y shr shift, h, until)
                synchronized(grids) { grids[key] = g }
                return g
            }
        }
        throw failed ?: java.io.IOException("no DEM tile for $zoom/$x/$y")
    }

    companion object {
        const val DEM_Z = 14 // ~5 m pixels at 512 px: finer than the 10 m step

        /** At most this many tiles a profile: a longer line reads coarser ones. */
        const val MAX_TILES = 24

        /** The coarsest zoom a long line is read at. */
        const val MIN_Z = 8

        /** At most this many tiles' grids kept (1 MB each). */
        const val MAX_CACHED = 32

        /** At most this many samples a profile: a longer line is sampled further apart. */
        const val MAX_SAMPLES = 4000

        /** How long a coarser stand-in for a tile that failed is used before trying the tile again. */
        private const val STAND_IN_MS = 60_000L

        /** Threads for fetching a profile's tiles at once (network-bound, so more than the cores). */
        private val FETCHERS = java.util.concurrent.Executors.newFixedThreadPool(8) { r ->
            Thread(r, "dem-fetch").apply { isDaemon = true }
        }
        const val SIZE = 512

        /** Terrarium: elevation = R * 256 + G + B / 256 - 32768 metres. */
        fun terrarium(argb: IntArray): FloatArray = FloatArray(argb.size) {
            val c = argb[it]
            (((c shr 16) and 0xff) * 256 + ((c shr 8) and 0xff) + (c and 0xff) / 256f) - 32768f
        }

        fun lngLatToTile(lng: Double, lat: Double, z: Int): Pair<Double, Double> {
            val n = (1 shl z).toDouble()
            val r = lat * PI / 180
            return (lng + 180) / 360 * n to (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n
        }

        fun haversine(a: List<Double>, b: List<Double>): Double {
            val r = PI / 180
            val h = sin((b[1] - a[1]) * r / 2).let { it * it } +
                cos(a[1] * r) * cos(b[1] * r) * sin((b[0] - a[0]) * r / 2).let { it * it }
            return 2 * 6371000.0 * asin(sqrt(min(1.0, h)))
        }

        /** Points every [step] metres along a line, with their distance from its start. */
        fun resample(coords: List<List<Double>>, step: Double): List<Pair<Double, DoubleArray>> {
            val out = mutableListOf(0.0 to doubleArrayOf(coords[0][0], coords[0][1]))
            var acc = 0.0
            for (i in 1 until coords.size) {
                val a = coords[i - 1]
                val b = coords[i]
                val d = haversine(a, b)
                while (d > 0 && out.last().first + step <= acc + d) {
                    val t = (out.last().first + step - acc) / d
                    out += (out.last().first + step) to doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
                }
                acc += d
            }
            out += acc to doubleArrayOf(coords.last()[0], coords.last()[1])
            return out
        }
    }
}
