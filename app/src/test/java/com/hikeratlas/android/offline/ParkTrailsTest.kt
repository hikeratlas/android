package com.hikeratlas.android.offline

import com.hikeratlas.android.contours.MvtLayer
import org.junit.Assert.assertEquals
import org.junit.Test

class ParkTrailsTest {
    private val line = intArrayOf(0, 0, 4096, 4096)

    private fun tile(vararg features: Map<String, Any>) =
        MvtLayer("path").apply { features.forEach { add(it, listOf(line)) } }.encode()

    @Test
    fun keepsParkTrailsOnly() {
        val t = tile(
            mapOf("kind" to "path", "in_park" to true),
            mapOf("kind" to "footway", "in_park" to true, "foot" to "designated"),
            mapOf("kind" to "cycleway", "in_park" to true),
            mapOf("kind" to "path", "in_park" to true, "closed" to true),
            mapOf("kind" to "path", "in_park" to true, "foot" to "no"),
            mapOf("kind" to "footway", "in_park" to false),
        )
        assertEquals(2, ParkTrails.lines(t, 14, 0, 0).size)
    }

    @Test
    fun linesAreLongitudeAndLatitude() {
        val z = 14
        val (x, y) = TileCover.tile(-123.03, 49.97, z)
        val l = ParkTrails.lines(tile(mapOf("kind" to "path", "in_park" to true)), z, x, y).single()
        // From the tile's north-west corner to its south-east corner.
        assertEquals(x to y, TileCover.tile(l[0][0] + 1e-9, l[0][1] - 1e-9, z))
        assertEquals((x + 1) to (y + 1), TileCover.tile(l[1][0] + 1e-9, l[1][1] - 1e-9, z))
    }

    @Test
    fun aTileWithoutTrailsHasNone() {
        assertEquals(emptyList<Any>(), ParkTrails.lines(MvtLayer("road").apply { add(mapOf("kind" to "track"), listOf(line)) }.encode(), 14, 0, 0))
    }
}
