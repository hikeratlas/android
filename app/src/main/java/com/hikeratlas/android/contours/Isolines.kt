package com.hikeratlas.android.contours

import kotlin.math.ceil

/**
 * Contour lines from a grid of heights, by marching squares, joined into
 * polylines (so labels can follow them).
 *
 * The grid is [w] x [h] samples, row by row. Points come back in grid units:
 * (0, 0) is the first sample. A level is traced only where it lies strictly
 * between samples' heights, so a contour never runs exactly through a sample.
 */
object Isolines {
    /** Lines at each multiple of [interval], by level height. */
    fun trace(grid: FloatArray, w: Int, h: Int, interval: Double): Map<Double, List<FloatArray>> {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (v in grid) { if (v < lo) lo = v.toDouble(); if (v > hi) hi = v.toDouble() }
        val out = LinkedHashMap<Double, List<FloatArray>>()
        var level = ceil(lo / interval) * interval
        while (level <= hi) {
            val lines = traceLevel(grid, w, h, level)
            if (lines.isNotEmpty()) out[level] = lines
            level += interval
        }
        return out
    }

    /**
     * One level's lines, each as [x0, y0, x1, y1, ...]. Samples equal to the
     * level count as above it.
     */
    fun traceLevel(grid: FloatArray, w: Int, h: Int, level: Double): List<FloatArray> {
        // Edges are identified so that segments meeting on an edge share a
        // key: horizontal edge (i, j)-(i+1, j) is 2 * (j * w + i), vertical
        // edge (i, j)-(i, j+1) is 2 * (j * w + i) + 1.
        val ends = HashMap<Long, MutableList<Int>>() // edge -> segment ids
        val segA = ArrayList<Long>()
        val segB = ArrayList<Long>()
        val point = HashMap<Long, Pair<Float, Float>>()

        fun above(i: Int, j: Int) = grid[j * w + i] >= level
        fun cross(a: Float, b: Float): Float = ((level - a) / (b - a)).toFloat()
        fun hEdge(i: Int, j: Int): Long {
            val key = 2L * (j.toLong() * w + i)
            point.getOrPut(key) { (i + cross(grid[j * w + i], grid[j * w + i + 1])) to j.toFloat() }
            return key
        }
        fun vEdge(i: Int, j: Int): Long {
            val key = 2L * (j.toLong() * w + i) + 1
            point.getOrPut(key) { i.toFloat() to (j + cross(grid[j * w + i], grid[(j + 1) * w + i])) }
            return key
        }
        fun seg(a: Long, b: Long) {
            val id = segA.size
            segA += a; segB += b
            ends.getOrPut(a) { ArrayList(2) } += id
            ends.getOrPut(b) { ArrayList(2) } += id
        }

        for (j in 0 until h - 1) {
            for (i in 0 until w - 1) {
                val tl = above(i, j); val tr = above(i + 1, j)
                val br = above(i + 1, j + 1); val bl = above(i, j + 1)
                val c = (if (tl) 8 else 0) or (if (tr) 4 else 0) or (if (br) 2 else 0) or (if (bl) 1 else 0)
                if (c == 0 || c == 15) continue
                when (c) {
                    1, 14 -> seg(vEdge(i, j), hEdge(i, j + 1))
                    2, 13 -> seg(hEdge(i, j + 1), vEdge(i + 1, j))
                    3, 12 -> seg(vEdge(i, j), vEdge(i + 1, j))
                    4, 11 -> seg(hEdge(i, j), vEdge(i + 1, j))
                    6, 9 -> seg(hEdge(i, j), hEdge(i, j + 1))
                    7, 8 -> seg(vEdge(i, j), hEdge(i, j))
                    5, 10 -> {
                        // A saddle: decide by the cell's centre.
                        val centre = (grid[j * w + i] + grid[j * w + i + 1] + grid[(j + 1) * w + i] + grid[(j + 1) * w + i + 1]) / 4 >= level
                        if ((c == 5) == centre) {
                            seg(vEdge(i, j), hEdge(i, j)); seg(hEdge(i, j + 1), vEdge(i + 1, j))
                        } else {
                            seg(vEdge(i, j), hEdge(i, j + 1)); seg(hEdge(i, j), vEdge(i + 1, j))
                        }
                    }
                }
            }
        }

        // Join segments that share an edge into polylines.
        val used = BooleanArray(segA.size)
        val lines = ArrayList<FloatArray>()
        fun next(edge: Long, from: Int): Int? = ends[edge]?.firstOrNull { it != from && !used[it] }
        for (start in segA.indices) {
            if (used[start]) continue
            used[start] = true
            val chain = ArrayDeque<Long>()
            chain.addLast(segA[start]); chain.addLast(segB[start])
            // Forwards from the end, then backwards from the start.
            var seg = start
            while (true) {
                val e = chain.last()
                val n = next(e, seg) ?: break
                used[n] = true
                chain.addLast(if (segA[n] == e) segB[n] else segA[n])
                seg = n
            }
            seg = start
            while (true) {
                val e = chain.first()
                val n = next(e, seg) ?: break
                used[n] = true
                chain.addFirst(if (segA[n] == e) segB[n] else segA[n])
                seg = n
            }
            val pts = FloatArray(chain.size * 2)
            chain.forEachIndexed { k, e -> val (x, y) = point.getValue(e); pts[2 * k] = x; pts[2 * k + 1] = y }
            lines += pts
        }
        return lines
    }
}
