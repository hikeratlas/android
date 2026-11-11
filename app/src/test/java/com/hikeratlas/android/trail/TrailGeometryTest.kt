package com.hikeratlas.android.trail

import com.hikeratlas.android.contours.MvtLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TrailGeometryTest {
    private fun tile(vararg ways: Pair<String, List<IntArray>>): ByteArray {
        val layer = MvtLayer("path")
        for ((name, lines) in ways) layer.add(mapOf("name" to name), lines)
        return layer.encode()
    }

    @Test
    fun joinsAWayCutAtATileEdge() {
        // One trail, west to east across the edge between tiles 0 and 1;
        // its edge vertex lands a unit apart in the two tiles.
        val west = tile("Black Tusk Trail" to listOf(intArrayOf(1000, 2000, 3000, 2000, 4096, 2000)))
        val east = tile("Black Tusk Trail" to listOf(intArrayOf(-1, 2001, 1500, 2100)), "Other" to listOf(intArrayOf(0, 0, 10, 10)))
        val g = TrailGeometry.build("black tusk trail ", listOf(Triple(100, 200, west), Triple(101, 200, east)))
        assertEquals(2, g.lines.size)
        val single = assertNotNull(g.single).let { g.single!! }
        assertEquals(4, single.size) // 3 in the west, the shared edge vertex, 1 more east
        assertNull(g.longest)
        // In order, west to east (or east to west).
        val lons = single.map { it[0] }
        assertEquals(lons.sorted(), if (lons.first() < lons.last()) lons else lons.reversed().also { }.reversed())
    }

    @Test
    fun joinsAWayThatRunsIntoTheNextTilesBuffer() {
        // One line in world units, (1000, 2000) (3000, 2000) (4160, 2032)
        // (5596, 2100), as the basemap's tiles have it: each tile's copy
        // runs on 64 units past the shared edge (x = 4096), so the two
        // overlap rather than meet.
        val west = tile("Garibaldi Lake Trail" to listOf(intArrayOf(1000, 2000, 3000, 2000, 4160, 2032)))
        val east = tile("Garibaldi Lake Trail" to listOf(intArrayOf(-64, 2028, 64, 2032, 1500, 2100)))
        val g = TrailGeometry.build("Garibaldi Lake Trail", listOf(Triple(100, 200, west), Triple(101, 200, east)))
        val single = g.single
        assertNotNull("one line", single)
        // 2 in the west, the edge vertex, 2 in the east.
        assertEquals(5, single!!.size)
    }

    @Test
    fun clipsToTheTile() {
        val pieces = TrailGeometry.clip(intArrayOf(-100, 100, 100, 100, 200, -100, 300, 100), 4096.0)
        assertEquals(2, pieces.size)
        assertEquals(0.0 to 100.0, pieces[0].first())
        assertEquals(150.0 to 0.0, pieces[0].last())
        assertEquals(250.0 to 0.0, pieces[1].first())
    }

    @Test
    fun aBranchedTrailHasItsLongestStretch() {
        // A stem from (0, 0) to (100, 0), a loop from there round to (100, 0),
        // and a short spur: branched, in one piece.
        val t = tile(
            "Loop" to listOf(
                intArrayOf(100, 2000, 1100, 2000),
                intArrayOf(1100, 2000, 1600, 1500, 2100, 2000, 1600, 2500, 1100, 2000),
                intArrayOf(1600, 1500, 1600, 1450),
            ),
        )
        val g = TrailGeometry.build("Loop", listOf(Triple(0, 0, t)))
        assertNull(g.single)
        val longest = g.longest!!
        // From the stem's start round to the far side of the loop.
        assertEquals(setOf(100.0 / 4096 / (1 shl 14) * 360 - 180), setOf(longest.first()[0], longest.last()[0]).filter { it < -179.999 }.toSet())
    }

    @Test
    fun aTrailInPiecesHasOnlyItsLines() {
        val t = tile("Bits" to listOf(intArrayOf(0, 0, 100, 100), intArrayOf(2000, 2000, 2100, 2100)))
        val g = TrailGeometry.build("Bits", listOf(Triple(0, 0, t)))
        assertEquals(2, g.lines.size)
        assertNull(g.single)
        assertNull(g.longest)
    }

    @Test
    fun tooManyTilesIsNull() {
        assertNull(TrailGeometry.tilesFor(doubleArrayOf(-124.0, 49.0, -122.0, 51.0)))
        assertEquals(1, TrailGeometry.tilesFor(doubleArrayOf(-123.042, 49.97, -123.041, 49.971))!!.size)
    }
}
