package com.hikeratlas.android.imagery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/** A box: west, south, east, north. */
typealias Box = DoubleArray

/**
 * An area's outline: rings of [lon, lat], as the website's public/imagery
 * files hold them. Answers whether a tile touches it, or lies wholly inside
 * (ported from the website's components/imagery.ts).
 */
class Outline(val rings: List<List<DoubleArray>>) {
    private val boxes: List<Box> = rings.map { r ->
        doubleArrayOf(r.minOf { it[0] }, r.minOf { it[1] }, r.maxOf { it[0] }, r.maxOf { it[1] })
    }

    fun contains(lon: Double, lat: Double): Boolean {
        var inside = false
        rings.forEachIndexed { k, r ->
            val b = boxes[k]
            if (lon < b[0] || lon > b[2] || lat < b[1] || lat > b[3]) return@forEachIndexed
            var j = r.size - 1
            for (i in r.indices) {
                val (xi, yi) = r[i][0] to r[i][1]
                val (xj, yj) = r[j][0] to r[j][1]
                if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
        }
        return inside
    }

    /** Whether any outline edge crosses the box (or lies in it). */
    private fun edgeIn(t: Box): Boolean {
        val (w, s, e, n) = listOf(t[0], t[1], t[2], t[3])
        fun hit(ax: Double, ay: Double, bx: Double, by: Double): Boolean {
            // Liang-Barsky clip of segment a-b against the box.
            var t0 = 0.0
            var t1 = 1.0
            val dx = bx - ax
            val dy = by - ay
            for ((p, q) in listOf(-dx to ax - w, dx to e - ax, -dy to ay - s, dy to n - ay)) {
                if (p == 0.0) { if (q < 0) return false; continue }
                val r = q / p
                if (p < 0) { if (r > t1) return false; if (r > t0) t0 = r } else { if (r < t0) return false; if (r < t1) t1 = r }
            }
            return true
        }
        return rings.indices.any { k ->
            overlaps(boxes[k], t) && rings[k].indices.any { i -> i > 0 && hit(rings[k][i - 1][0], rings[k][i - 1][1], rings[k][i][0], rings[k][i][1]) }
        }
    }

    private fun corners(t: Box) = listOf(t[0] to t[1], t[2] to t[1], t[0] to t[3], t[2] to t[3])

    fun touches(t: Box): Boolean {
        if (boxes.none { overlaps(it, t) }) return false
        return corners(t).any { (lon, lat) -> contains(lon, lat) } || edgeIn(t)
    }

    fun within(t: Box): Boolean = corners(t).all { (lon, lat) -> contains(lon, lat) } && !edgeIn(t)

    companion object {
        fun parse(json: String) = Outline(
            Json.parseToJsonElement(json).jsonArray.map { ring ->
                ring.jsonArray.map { p -> p.jsonArray.let { doubleArrayOf(it[0].jsonPrimitive.content.toDouble(), it[1].jsonPrimitive.content.toDouble()) } }
            },
        )

        fun overlaps(a: Box, b: Box) = a[0] <= b[2] && b[0] <= a[2] && a[1] <= b[3] && b[1] <= a[3]

        /** A tile's box, in degrees. */
        fun tileBox(z: Int, x: Int, y: Int): Box {
            val n = (1 shl z).toDouble()
            fun lat(t: Double) = atan(sinh(PI * (1 - 2 * t / n))) * 180 / PI
            return doubleArrayOf(x / n * 360 - 180, lat(y + 1.0), (x + 1) / n * 360 - 180, lat(y.toDouble()))
        }
    }
}
