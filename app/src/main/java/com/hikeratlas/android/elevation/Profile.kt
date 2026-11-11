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

    private class Grid(val z: Int, val x: Int, val y: Int, val h: FloatArray)

    private val grids = HashMap<Long, Grid>()

    /**
     * Samples every [step] metres. Climbs and descents count only once they
     * exceed [threshold] metres, so DEM noise doesn't add phantom climb.
     */
    fun profile(line: List<List<Double>>, step: Double = 10.0, threshold: Double = 10.0): Profile {
        require(line.size >= 2) { "a line needs two points" }
        val samples = resample(line, step)
        val elevations = DoubleArray(samples.size) { elevationAt(samples[it].second) }
        var gain = 0.0
        var loss = 0.0
        var ref = elevations[0]
        for (e in elevations) {
            if (e - ref >= threshold) { gain += e - ref; ref = e } else if (ref - e >= threshold) { loss += ref - e; ref = e }
        }
        return Profile(DoubleArray(samples.size) { samples[it].first }, elevations, samples.map { it.second }, gain, loss)
    }

    fun elevationAt(p: DoubleArray): Double {
        val (tx, ty) = lngLatToTile(p[0], p[1], DEM_Z)
        val g = grid(floor(tx).toInt(), floor(ty).toInt())
        val scale = (1 shl (DEM_Z - g.z)).toDouble()
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
     * The grid for a DEM_Z tile: the first source's, at the deepest zoom it
     * has up to DEM_Z, falling back to ancestors where a tile is missing.
     */
    private fun grid(x: Int, y: Int): Grid {
        val key = (x.toLong() shl 32) or y.toLong()
        synchronized(grids) { grids[key] }?.let { return it }
        for (s in sources) {
            for (z in min(DEM_Z, s.maxZoom) downTo s.minZoom) {
                val shift = DEM_Z - z
                val h = s.tile(z, x shr shift, y shr shift) ?: continue
                val g = Grid(z, x shr shift, y shr shift, h)
                synchronized(grids) { grids[key] = g }
                return g
            }
        }
        throw java.io.IOException("no DEM tile for $DEM_Z/$x/$y")
    }

    companion object {
        const val DEM_Z = 14 // ~5 m pixels at 512 px: finer than the 10 m step
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
