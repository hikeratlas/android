package com.hikeratlas.android.trail

import com.hikeratlas.android.elevation.ElevationProfiler
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sinh

/**
 * A named trail's lines, from the basemap's z14 tiles: what a trail picked in
 * search is drawn with and measured along (as the website's
 * app/routing/trail.ts).
 *
 * The trail's ways are the "path" features with its name in the tiles that
 * cover its box. Tiles carry a buffer past their edges, so a way crossing
 * from one tile to the next is in both, overlapping: each tile's copy is
 * clipped to the tile's own bounds, so the two meet at its edge. (The
 * website joins them without clipping, so it finds most long trails "in
 * pieces".) The two edge vertices can land a unit or two apart, so vertices
 * on a tile edge that close together are one. If the ways chain end to end into
 * one line (no branches, one piece), it's [single], which can be profiled
 * end to end. A branched trail in one piece has [longest] instead: its
 * longest stretch. A trail in separate pieces only has [lines].
 */
class TrailGeometry(
    val lines: List<List<List<Double>>>,
    val single: List<List<Double>>?,
    val longest: List<List<Double>>?,
) {
    companion object {
        const val Z = 14

        /** More tiles than this (~15-20 km across) and the trail isn't read. */
        const val MAX_TILES = 256

        private const val SNAP = 4 // tile units: edge vertices this close are one

        /** The z14 tiles covering a box (west, south, east, north), or null if too many. */
        fun tilesFor(box: DoubleArray): List<Pair<Int, Int>>? {
            val (x0, y0) = ElevationProfiler.lngLatToTile(box[0], box[3], Z)
            val (x1, y1) = ElevationProfiler.lngLatToTile(box[2], box[1], Z)
            val xs = floor(x0).toInt()..floor(x1).toInt()
            val ys = floor(y0).toInt()..floor(y1).toInt()
            if (xs.count().toLong() * ys.count() > MAX_TILES) return null
            return xs.flatMap { x -> ys.map { y -> x to y } }
        }

        /**
         * The trail called [name] in [tiles] (z14 x, y, and the tile's bytes,
         * or null where there's none).
         */
        fun build(name: String, tiles: List<Triple<Int, Int, ByteArray?>>): TrailGeometry {
            val want = name.trim().lowercase()
            val at = HashMap<String, List<Double>>()
            val adj = LinkedHashMap<String, MutableSet<String>>()
            val lines = ArrayList<List<List<Double>>>()
            for ((tx, ty, bytes) in tiles) {
                if (bytes == null) continue
                val layer = VectorTile.layer(bytes, "path") ?: continue
                val ext = layer.extent
                fun toLngLat(wx: Double, wy: Double): List<Double> {
                    val n = ext.toDouble() * (1 shl Z)
                    return listOf(wx / n * 360 - 180, atan(sinh(PI * (1 - 2 * wy / n))) * 180 / PI)
                }
                for (f in layer.features) {
                    if (f.type != VectorTile.Feature.LINESTRING) continue
                    if ((f.properties["name"]?.toString() ?: "").trim().lowercase() != want) continue
                    for (part in f.parts()) {
                        for (piece in clip(part, ext.toDouble())) {
                            val line = ArrayList<List<Double>>()
                            var prev: String? = null
                            for ((px, py) in piece) {
                                val wx = tx.toLong() * ext + px
                                val wy = ty.toLong() * ext + py
                                val onEdge = px <= 0 || px >= ext || py <= 0 || py >= ext
                                val key = if (onEdge) "e${(wx / SNAP).roundToLong()},${(wy / SNAP).roundToLong()}" else "$wx,$wy"
                                val p = at.getOrPut(key) { toLngLat(wx, wy) }
                                line += p
                                if (prev != null && prev != key) {
                                    adj.getOrPut(prev) { LinkedHashSet() } += key
                                    adj.getOrPut(key) { LinkedHashSet() } += prev
                                }
                                prev = key
                            }
                            if (line.size > 1) lines += line
                        }
                    }
                }
            }
            if (lines.isEmpty()) return TrailGeometry(lines, null, null)
            val single = chain(adj, at)
            return TrailGeometry(lines, single, if (single == null) longestStretch(adj, at) else null)
        }

        /**
         * A line ([x0, y0, x1, y1, ...] in tile units) clipped to the tile
         * (0..ext both ways): the pieces of it inside.
         */
        fun clip(line: IntArray, ext: Double): List<List<Pair<Double, Double>>> {
            val out = ArrayList<List<Pair<Double, Double>>>()
            var cur = ArrayList<Pair<Double, Double>>()
            fun end() { if (cur.size > 1) out += cur; cur = ArrayList() }
            for (k in 1 until line.size / 2) {
                val ax = line[2 * k - 2].toDouble(); val ay = line[2 * k - 1].toDouble()
                val bx = line[2 * k].toDouble(); val by = line[2 * k + 1].toDouble()
                // Liang-Barsky.
                var t0 = 0.0
                var t1 = 1.0
                val dx = bx - ax
                val dy = by - ay
                var inside = true
                for ((p, q) in listOf(-dx to ax, dx to ext - ax, -dy to ay, dy to ext - ay)) {
                    if (p == 0.0) { if (q < 0) inside = false; continue }
                    val r = q / p
                    if (p < 0) { if (r > t1) inside = false else if (r > t0) t0 = r } else { if (r < t0) inside = false else if (r < t1) t1 = r }
                }
                if (!inside || t0 > t1) { end(); continue }
                val s = (ax + t0 * dx) to (ay + t0 * dy)
                val e = (ax + t1 * dx) to (ay + t1 * dy)
                if (cur.isEmpty() || cur.last() != s) { end(); cur += s }
                cur += e
                if (t1 < 1.0) end() // it leaves the tile here
            }
            end()
            return out
        }

        /** The vertices in order, if they form one unbranched line (or loop). */
        private fun chain(adj: Map<String, Set<String>>, at: Map<String, List<Double>>): List<List<Double>>? {
            if (adj.values.any { it.size > 2 }) return null
            val ends = adj.keys.filter { adj.getValue(it).size == 1 }
            if (ends.isNotEmpty() && ends.size != 2) return null
            val start = ends.firstOrNull() ?: adj.keys.first()
            val out = ArrayList<List<Double>>()
            val seen = HashSet<String>()
            var cur: String? = start
            while (cur != null && cur !in seen) {
                seen += cur
                out += at.getValue(cur)
                cur = adj.getValue(cur).firstOrNull { it !in seen }
            }
            if (ends.isEmpty()) out += at.getValue(start) // a loop closes
            // Every vertex visited: one piece. Otherwise it's in several.
            return if (seen.size == adj.size) out else null
        }

        /**
         * In a trail of one piece, the path between its two farthest-apart
         * points (along the trail). Null if the trail is in several pieces.
         */
        private fun longestStretch(adj: Map<String, Set<String>>, at: Map<String, List<Double>>): List<List<Double>>? {
            class Far(val end: String, val prev: Map<String, String>, val reached: Int)
            fun far(from: String): Far {
                val dist = HashMap<String, Double>().apply { put(from, 0.0) }
                val prev = HashMap<String, String>()
                val queue = java.util.PriorityQueue<Pair<Double, String>>(compareBy { it.first })
                queue += 0.0 to from
                val done = HashSet<String>()
                while (queue.isNotEmpty()) {
                    val (d, u) = queue.poll()!!
                    if (!done.add(u)) continue
                    for (v in adj.getValue(u)) {
                        val nd = d + ElevationProfiler.haversine(at.getValue(u), at.getValue(v))
                        if (nd < (dist[v] ?: Double.MAX_VALUE)) { dist[v] = nd; prev[v] = u; queue += nd to v }
                    }
                }
                return Far(dist.maxBy { it.value }.key, prev, dist.size)
            }
            val first = adj.keys.firstOrNull() ?: return null
            val a = far(first)
            if (a.reached != adj.size) return null // in pieces
            val b = far(a.end)
            val out = ArrayList<List<Double>>()
            var k: String? = b.end
            while (k != null) { out += at.getValue(k); k = b.prev[k] }
            return if (out.size > 1) out else null
        }
    }
}
