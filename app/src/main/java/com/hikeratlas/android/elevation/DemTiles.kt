package com.hikeratlas.android.elevation

import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import kotlin.math.floor

/**
 * Terrain heights for any tile, for the hillshade and contours: from the
 * first source that has the tile or an ancestor of it, in [sources] order
 * (nl-dem before Mapterhorn). An ancestor's part of the tile is upsampled to
 * the full size, so it stitches with full-resolution neighbours, as the
 * website's demFallback.ts does.
 */
class DemTiles(private val sources: List<ElevationProfiler.DemSource>) {
    /** Heights for a tile, and whether they came from the tile itself. */
    class Heights(val h: FloatArray, val exact: Boolean, val source: ElevationProfiler.DemSource)

    private val cache = object : LinkedHashMap<Long, FutureTask<Heights>>(32, 0.75f, true) {
        // 512 x 512 floats is 1 MB a tile.
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, FutureTask<Heights>>) = size > 24
    }

    fun heights(z: Int, x: Int, y: Int): Heights {
        val key = (z.toLong() shl 58) or (x.toLong() shl 29) or y.toLong()
        val task = synchronized(cache) { cache.getOrPut(key) { FutureTask { load(z, x, y) } } }
        task.run()
        return try {
            task.get()
        } catch (e: ExecutionException) {
            synchronized(cache) { cache.remove(key) }
            throw e.cause ?: e
        }
    }

    /** The height at a point in tile [z]'s pixel space (512 px a tile), between pixel centres. */
    fun sample(z: Int, px: Double, py: Double): Double {
        val fx = px - 0.5
        val fy = py - 0.5
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val u = fx - x0
        val v = fy - y0
        fun at(gx: Int, gy: Int): Double {
            val t = heights(z, Math.floorDiv(gx, SIZE), Math.floorDiv(gy, SIZE))
            return t.h[Math.floorMod(gy, SIZE) * SIZE + Math.floorMod(gx, SIZE)].toDouble()
        }
        return (at(x0, y0) * (1 - u) + at(x0 + 1, y0) * u) * (1 - v) + (at(x0, y0 + 1) * (1 - u) + at(x0 + 1, y0 + 1) * u) * v
    }

    private fun load(z: Int, x: Int, y: Int): Heights {
        for (s in sources) {
            for (zz in minOf(z, s.maxZoom) downTo s.minZoom) {
                val shift = z - zz
                val h = s.tile(zz, x shr shift, y shr shift) ?: continue
                return if (shift == 0) Heights(h, true, s) else Heights(part(h, shift, x, y), false, s)
            }
        }
        throw IOException("no DEM tile for $z/$x/$y")
    }

    companion object {
        const val SIZE = 512

        /**
         * The part of an ancestor's heights (that many zoom levels up) that
         * covers tile x, y, bilinearly upsampled to the full size.
         */
        fun part(h: FloatArray, levels: Int, x: Int, y: Int): FloatArray {
            val n = SIZE
            val scale = 1 shl levels
            val mask = scale - 1
            val ox = (x and mask) * n.toDouble() / scale
            val oy = (y and mask) * n.toDouble() / scale
            fun at(px: Int, py: Int) = h[py.coerceIn(0, n - 1) * n + px.coerceIn(0, n - 1)]
            val out = FloatArray(n * n)
            for (j in 0 until n) {
                val sy = oy + (j + 0.5) / scale - 0.5
                val y0 = floor(sy).toInt()
                val fy = (sy - y0).toFloat()
                for (i in 0 until n) {
                    val sx = ox + (i + 0.5) / scale - 0.5
                    val x0 = floor(sx).toInt()
                    val fx = (sx - x0).toFloat()
                    out[j * n + i] = (at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx) * (1 - fy) +
                        (at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx) * fy
                }
            }
            return out
        }

        /** Terrarium-encodes heights as ARGB pixels (for a PNG). */
        fun encodeTerrarium(h: FloatArray): IntArray = IntArray(h.size) {
            val v = (h[it] + 32768).toDouble().coerceIn(0.0, 65535.996)
            val r = floor(v / 256).toInt()
            val g = floor(v).toInt() % 256
            val b = floor((v - floor(v)) * 256).toInt()
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
