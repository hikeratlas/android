package com.hikeratlas.android.routing

import com.hikeratlas.android.elevation.ElevationProfiler
import com.hikeratlas.android.trail.VectorTile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh

/**
 * Routing on the basemap's own vector tiles, as the website's
 * app/routing/graph.ts does.
 *
 * The z14 tiles carry trails ("path") and roads ("road") at full detail. Two
 * lines that share an OSM node share a vertex, at the same tile coordinates,
 * so the tiles around a route make a routing graph: vertices are nodes, and
 * consecutive vertices of a line are edges. Tiles cut lines at their edges,
 * adding a vertex there in each tile; the copies should land on the same
 * point, but can be a unit or two apart, so vertices on a tile edge that
 * close together are joined.
 */
class Graph(val z: Int) {
    /** Node i is at (xs[i], ys[i]), in world coordinates: tile x * extent + x. */
    var xs = IntArray(1024); private set
    var ys = IntArray(1024); private set
    var size = 0; private set
    var extent = 4096
    // Edges: for node i, adjacency in parallel lists.
    private var adjTo = arrayOfNulls<IntArray>(1024)
    private var adjM = arrayOfNulls<DoubleArray>(1024)
    private var adjN = IntArray(1024)

    fun degree(i: Int) = adjN[i]
    fun to(i: Int, k: Int) = adjTo[i]!![k]
    fun metres(i: Int, k: Int) = adjM[i]!![k]

    internal fun addNode(x: Int, y: Int): Int {
        if (size == xs.size) {
            val n = size * 2
            xs = xs.copyOf(n); ys = ys.copyOf(n); adjN = adjN.copyOf(n)
            adjTo = adjTo.copyOf(n); adjM = adjM.copyOf(n)
        }
        xs[size] = x; ys[size] = y
        return size++
    }

    internal fun addEdge(a: Int, b: Int, m: Double) {
        add(a, b, m); add(b, a, m)
    }

    private fun add(a: Int, b: Int, m: Double) {
        var t = adjTo[a]
        var d = adjM[a]
        if (t == null) { t = IntArray(2); d = DoubleArray(2); adjTo[a] = t; adjM[a] = d }
        if (adjN[a] == t.size) { t = t.copyOf(t.size * 2); d = d!!.copyOf(t.size); adjTo[a] = t; adjM[a] = d }
        t[adjN[a]] = b
        d!![adjN[a]] = m
        adjN[a]++
    }

    fun lngLat(i: Int): List<Double> {
        val n = (1 shl z).toDouble() * extent
        return listOf(xs[i] / n * 360 - 180, atan(sinh(PI * (1 - 2 * ys[i] / n))) * 180 / PI)
    }

    companion object {
        /** The zoom routing reads tiles at, unless told otherwise. */
        const val Z = 14
        private val LAYERS = listOf("path", "road")

        /** Paths walkers may not use. */
        fun footForbidden(props: Map<String, Any?>) = props["foot"].let { it == "no" || it == "private" || it == "use_sidepath" }

        /** Closed trails: drawn, greyed out, but not for routing. */
        fun closed(props: Map<String, Any?>) = props["closed"] == true

        /**
         * The tiles (at zoom z) covering two points, with a margin so the
         * route can wander outside the box between them.
         */
        fun tilesAround(a: List<Double>, b: List<Double>, maxTiles: Int = 600, z: Int = Z): List<Pair<Int, Int>> {
            val (ax, ay) = ElevationProfiler.lngLatToTile(a[0], a[1], z)
            val (bx, by) = ElevationProfiler.lngLatToTile(b[0], b[1], z)
            // At least two z14 tiles' width (half a tile at z12), or 40% of the span.
            val margin = max(2.0 * Math.pow(2.0, (z - Z).toDouble()), 0.4 * max(abs(ax - bx), abs(ay - by)))
            val x0 = floor(min(ax, bx) - margin).toInt()
            val x1 = floor(max(ax, bx) + margin).toInt()
            val y0 = floor(min(ay, by) - margin).toInt()
            val y1 = floor(max(ay, by) + margin).toInt()
            val n = (x1 - x0 + 1).toLong() * (y1 - y0 + 1)
            if (n > maxTiles) throw TooFarException(n)
            return (x0..x1).flatMap { x -> (y0..y1).map { y -> x to y } }
        }

        /** A graph from tiles (x, y and their bytes, or null) at zoom z. */
        fun build(tiles: List<Triple<Int, Int, ByteArray?>>, z: Int = Z): Graph {
            val g = Graph(z)
            val ids = HashMap<Long, Int>()
            val edgeNodes = ArrayList<Int>()
            fun node(x: Int, y: Int, onEdge: Boolean): Int {
                val key = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
                return ids.getOrPut(key) { g.addNode(x, y).also { if (onEdge) edgeNodes += it } }
            }
            for ((tx, ty, bytes) in tiles) {
                if (bytes == null) continue
                for (name in LAYERS) {
                    val layer = VectorTile.layer(bytes, name) ?: continue
                    val ext = layer.extent
                    g.extent = ext
                    for (f in layer.features) {
                        if (f.type != VectorTile.Feature.LINESTRING) continue
                        // Bike-only and closed trails are drawn, but this plans walks.
                        if (footForbidden(f.properties) || closed(f.properties)) continue
                        for (line in f.parts()) {
                            var prev = -1
                            for (k in 0 until line.size / 2) {
                                val px = line[2 * k]
                                val py = line[2 * k + 1]
                                val onEdge = px <= 0 || px >= ext || py <= 0 || py >= ext
                                val i = node(tx * ext + px, ty * ext + py, onEdge)
                                if (prev >= 0 && prev != i) g.addEdge(prev, i, ElevationProfiler.haversine(g.lngLat(prev), g.lngLat(i)))
                                prev = i
                            }
                        }
                    }
                }
            }
            joinEdgeNodes(g, edgeNodes)
            return g
        }

        /** Vertices on tile edges within 3 units of each other get a 0 m edge. */
        private fun joinEdgeNodes(g: Graph, nodes: List<Int>) {
            val cell = 4
            val buckets = HashMap<Long, MutableList<Int>>()
            fun key(bx: Int, by: Int) = (bx.toLong() shl 32) or (by.toLong() and 0xffffffffL)
            for (i in nodes) buckets.getOrPut(key(Math.floorDiv(g.xs[i], cell), Math.floorDiv(g.ys[i], cell))) { ArrayList() } += i
            for (i in nodes) {
                val bx = Math.floorDiv(g.xs[i], cell)
                val by = Math.floorDiv(g.ys[i], cell)
                for (dx in -1..1) for (dy in -1..1) {
                    for (j in buckets[key(bx + dx, by + dy)] ?: continue) {
                        if (j <= i) continue
                        if (abs(g.xs[i] - g.xs[j]) <= 3 && abs(g.ys[i] - g.ys[j]) <= 3) g.addEdge(i, j, 0.0)
                    }
                }
            }
        }

        /** The node nearest a point, among nodes with edges (and in component [only], if given). */
        fun nearestNode(g: Graph, p: List<Double>, comp: IntArray? = null, only: Int = -1): Int {
            val (tx, ty) = ElevationProfiler.lngLatToTile(p[0], p[1], g.z)
            val wx = tx * g.extent
            val wy = ty * g.extent
            var best = -1
            var bestD = Double.MAX_VALUE
            for (i in 0 until g.size) {
                if (g.degree(i) == 0) continue
                if (comp != null && comp[i] != only) continue
                val dx = g.xs[i] - wx
                val dy = g.ys[i] - wy
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = i }
            }
            return best
        }

        /** Connected components: each node's, and the largest's id. */
        fun components(g: Graph): Pair<IntArray, Int> {
            val comp = IntArray(g.size) { -1 }
            val sizes = ArrayList<Int>()
            val stack = IntArray(g.size)
            for (i in 0 until g.size) {
                if (comp[i] >= 0) continue
                val id = sizes.size
                var n = 0
                var top = 0
                stack[top++] = i
                comp[i] = id
                while (top > 0) {
                    val u = stack[--top]
                    n++
                    for (k in 0 until g.degree(u)) {
                        val v = g.to(u, k)
                        if (comp[v] < 0) { comp[v] = id; stack[top++] = v }
                    }
                }
                sizes += n
            }
            val largest = sizes.indices.maxByOrNull { sizes[it] } ?: 0
            return comp to largest
        }

        /**
         * The two nodes to route between: the nearest to each point, unless
         * those aren't connected (one is on a stray bit of path); then the
         * nearest on the largest connected network.
         */
        fun snapPair(g: Graph, a: List<Double>, b: List<Double>): Pair<Int, Int> {
            val s = nearestNode(g, a)
            val t = nearestNode(g, b)
            if (s < 0 || t < 0) return s to t
            val (comp, largest) = components(g)
            if (comp[s] == comp[t]) return s to t
            return nearestNode(g, a, comp, largest) to nearestNode(g, b, comp, largest)
        }

        class Path(val nodes: IntArray, val metres: Double)

        /** A* from s to t; the heuristic is the straight-line distance, in metres. */
        fun shortestPath(g: Graph, s: Int, t: Int): Path? {
            if (s < 0 || t < 0) return null
            val target = g.lngLat(t)
            fun h(i: Int) = ElevationProfiler.haversine(g.lngLat(i), target)
            val dist = DoubleArray(g.size) { Double.POSITIVE_INFINITY }
            val prev = IntArray(g.size) { -1 }
            val heap = MinHeap()
            dist[s] = 0.0
            heap.push(h(s), s)
            while (heap.size > 0) {
                val u = heap.pop()
                if (u == t) break
                for (k in 0 until g.degree(u)) {
                    val v = g.to(u, k)
                    val d = dist[u] + g.metres(u, k)
                    if (d < dist[v]) {
                        dist[v] = d
                        prev[v] = u
                        heap.push(d + h(v), v)
                    }
                }
            }
            if (dist[t].isInfinite()) return null
            val path = ArrayList<Int>()
            var cur = t
            path += cur
            while (cur != s) { cur = prev[cur]; path += cur }
            return Path(path.reversed().toIntArray(), dist[t])
        }
    }

    class TooFarException(tiles: Long) : Exception("Those points are too far apart to route ($tiles tiles)")

    /** A binary min-heap of (key, node). */
    private class MinHeap {
        private var k = DoubleArray(256)
        private var v = IntArray(256)
        var size = 0
            private set

        fun push(key: Double, value: Int) {
            if (size == k.size) { k = k.copyOf(size * 2); v = v.copyOf(size * 2) }
            var i = size++
            k[i] = key; v[i] = value
            while (i > 0) {
                val p = (i - 1) shr 1
                if (k[p] <= k[i]) break
                swap(p, i); i = p
            }
        }

        fun pop(): Int {
            val top = v[0]
            size--
            if (size > 0) {
                k[0] = k[size]; v[0] = v[size]
                var i = 0
                while (true) {
                    val l = 2 * i + 1
                    val r = l + 1
                    var m = i
                    if (l < size && k[l] < k[m]) m = l
                    if (r < size && k[r] < k[m]) m = r
                    if (m == i) break
                    swap(m, i); i = m
                }
            }
            return top
        }

        private fun swap(a: Int, b: Int) {
            val tk = k[a]; k[a] = k[b]; k[b] = tk
            val tv = v[a]; v[a] = v[b]; v[b] = tv
        }
    }
}
