package com.hikeratlas.android.offline

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.tan

/** Which tiles cover what, at a zoom. Coordinates are [lng, lat]. */
object TileCover {
    fun tile(lng: Double, lat: Double, z: Int): Pair<Int, Int> {
        val n = 1 shl z
        val x = floor((lng + 180) / 360 * n).toInt().coerceIn(0, n - 1)
        val latR = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val y = floor((1 - asinh(tan(latR)) / PI) / 2 * n).toInt().coerceIn(0, n - 1)
        return x to y
    }

    /** The tiles a box [west, south, east, north] touches. */
    fun box(b: List<Double>, z: Int): Set<Pair<Int, Int>> {
        val (x0, y1) = tile(b[0], b[1], z)
        val (x1, y0) = tile(b[2], b[3], z)
        return buildSet { for (x in x0..x1) for (y in y0..y1) add(x to y) }
    }

    /** How many tiles [box] would give, without making them. */
    fun boxCount(b: List<Double>, z: Int): Long {
        val (x0, y1) = tile(b[0], b[1], z)
        val (x1, y0) = tile(b[2], b[3], z)
        return (x1 - x0 + 1).toLong() * (y1 - y0 + 1)
    }

    /**
     * The tiles within [bufferM] metres of [lines]: each line walked every
     * few metres, with its points [bufferM] out each way.
     */
    fun corridor(lines: List<List<List<Double>>>, z: Int, bufferM: Double, within: List<Double>? = null): Set<Pair<Int, Int>> {
        val out = HashSet<Pair<Int, Int>>()
        // Steps well under a tile's width at this zoom, and the buffer.
        val step = minOf(bufferM, 40_075_000.0 / (1 shl z) / 4).coerceAtLeast(10.0)
        for (line in lines) {
            if (line.isEmpty()) continue
            if (line.size == 1) addAround(out, line[0][0], line[0][1], z, bufferM, within)
            for (i in 1 until line.size) {
                val a = line[i - 1]
                val b = line[i]
                val d = hypot((b[0] - a[0]) * M_PER_DEG * cos(Math.toRadians(a[1])), (b[1] - a[1]) * M_PER_DEG)
                val n = max(1, (d / step).toInt())
                for (k in 0..n) {
                    val t = k.toDouble() / n
                    addAround(out, a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, z, bufferM, within)
                }
            }
        }
        return out
    }

    private fun addAround(out: MutableSet<Pair<Int, Int>>, lng: Double, lat: Double, z: Int, bufferM: Double, within: List<Double>? = null) {
        if (within != null && (lng < within[0] || lat < within[1] || lng > within[2] || lat > within[3])) return
        val dLng = bufferM / (M_PER_DEG * cos(Math.toRadians(lat)))
        val dLat = bufferM / M_PER_DEG
        for (ox in doubleArrayOf(-dLng, 0.0, dLng)) for (oy in doubleArrayOf(-dLat, 0.0, dLat)) out += tile(lng + ox, lat + oy, z)
    }

    /** The box around [lines], [marginM] metres out: [west, south, east, north]. */
    fun boundsOf(lines: List<List<List<Double>>>, marginM: Double): List<Double> {
        val pts = lines.flatten()
        val lat = pts.map { it[1] }.average()
        val dLng = marginM / (M_PER_DEG * cos(Math.toRadians(lat)))
        val dLat = marginM / M_PER_DEG
        return listOf(pts.minOf { it[0] } - dLng, pts.minOf { it[1] } - dLat, pts.maxOf { it[0] } + dLng, pts.maxOf { it[1] } + dLat)
    }

    private const val M_PER_DEG = 111_320.0
}

/**
 * The tiles an offline area needs, by zoom: [vector] from the basemap and
 * (if [withRoutes]) the routes overlay, the same tiles of each; [dem] from
 * Mapterhorn.
 */
class AreaPlan(
    val vector: Map<Int, Set<Pair<Int, Int>>>,
    val dem: Map<Int, Set<Pair<Int, Int>>>,
    val withRoutes: Boolean = true,
    /** Finer terrain along the park's trails too: worked out from its z14 map tiles once they're kept. */
    val alongTrails: AlongTrails? = null,
) {
    /** Terrain at [zooms] within [bufferM] of the trails in [box]'s z14 map tiles. */
    class AlongTrails(val zooms: IntRange, val box: List<Double>, val bufferM: Double)

    val vectorCount get() = vector.values.sumOf { it.size }
    val demCount get() = dem.values.sumOf { it.size }

    companion object {
        /** The basemap's deepest zoom: past it, MapLibre overzooms. */
        const val VECTOR_MAX = 14

        /** Terrain over the whole area, to this zoom: about 12 m pixels at 50°N. */
        const val COARSE_DEM_MAX = 12

        /**
         * A big park (more z12 terrain tiles than this, ~30 MB) gets z11 over
         * the whole park and z12 only along its routes. Banff: 74 MB, not 125.
         */
        const val BIG_PARK_DEM_TILES = 300

        /** Finer terrain, to this zoom, near the routes (where Mapterhorn has it; it 404s elsewhere). */
        const val FINE_DEM_MAX = 14
        const val ROUTE_BUFFER_M = 200.0

        /** A hike's map: this far either side of it. */
        const val HIKE_MARGIN_M = 1000.0

        /** Below this zoom a hike's map is its box (a few tiles), from it the corridor. */
        private const val HIKE_CORRIDOR_FROM_Z = 10

        /** Once, with the first area: the whole build at small scale, so the map isn't blank around it. */
        const val OVERVIEW_MAX = 6

        /**
         * A park: its box, z0-14; terrain z0-12 over it (z0-11 for a big
         * park), and finer along [routes] and its trails.
         */
        fun park(box: List<Double>, routes: List<List<List<Double>>>): AreaPlan {
            val vector = (0..VECTOR_MAX).associateWith { z -> TileCover.box(box, z) }
            val big = TileCover.boxCount(box, COARSE_DEM_MAX) > BIG_PARK_DEM_TILES
            val coarseMax = if (big) COARSE_DEM_MAX - 1 else COARSE_DEM_MAX
            val dem = HashMap<Int, Set<Pair<Int, Int>>>()
            for (z in 0..coarseMax) dem[z] = TileCover.box(box, z)
            for (z in coarseMax + 1..FINE_DEM_MAX) dem[z] = TileCover.corridor(routes, z, ROUTE_BUFFER_M)
            return AreaPlan(vector, dem, alongTrails = AlongTrails(coarseMax + 1..FINE_DEM_MAX, box, ROUTE_BUFFER_M))
        }

        /**
         * A hike: the map within [HIKE_MARGIN_M] of it, z0-14; terrain z0-12
         * over its box, and finer within [ROUTE_BUFFER_M] of it.
         */
        fun hike(line: List<List<Double>>): AreaPlan {
            val lines = listOf(line)
            val box = TileCover.boundsOf(lines, HIKE_MARGIN_M)
            val vector = (0..VECTOR_MAX).associateWith { z ->
                if (z < HIKE_CORRIDOR_FROM_Z) TileCover.box(box, z) else TileCover.corridor(lines, z, HIKE_MARGIN_M)
            }
            val dem = HashMap<Int, Set<Pair<Int, Int>>>()
            for (z in 0..COARSE_DEM_MAX) dem[z] = TileCover.box(box, z)
            for (z in COARSE_DEM_MAX + 1..FINE_DEM_MAX) dem[z] = TileCover.corridor(lines, z, ROUTE_BUFFER_M)
            return AreaPlan(vector, dem)
        }

        /** The overview: the build's [bounds], z0-6, basemap only. */
        fun overview(bounds: List<Double>): AreaPlan =
            AreaPlan((0..OVERVIEW_MAX).associateWith { z -> TileCover.box(bounds, z) }, emptyMap(), withRoutes = false)
    }
}
