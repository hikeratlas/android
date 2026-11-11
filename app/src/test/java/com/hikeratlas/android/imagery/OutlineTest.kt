package com.hikeratlas.android.imagery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OutlineTest {
    private fun asset(name: String) = Outline.parse(File("src/main/assets/imagery/$name").readText())

    /** The z10 tile holding a point. */
    private fun tileAt(lon: Double, lat: Double, z: Int = 10): DoubleArray {
        val n = 1 shl z
        val x = ((lon + 180) / 360 * n).toInt()
        val r = Math.toRadians(lat)
        val y = ((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * n).toInt()
        return Outline.tileBox(z, x, y)
    }

    @Test
    fun containsPoints() {
        val us = asset("us-cover.json")
        assertTrue("Utah", us.contains(-111.9, 40.8))
        assertFalse("Banff", us.contains(-115.57, 51.18))
        val on = asset("on-cover.json")
        assertTrue("Algonquin", on.contains(-78.4, 45.6))
        assertFalse("Utah", on.contains(-111.9, 40.8))
    }

    @Test
    fun tilesTouchingAndWithin() {
        val usInside = asset("us-inside.json")
        val usCover = asset("us-cover.json")
        // Deep in Utah: wholly inside, so Sentinel-2 is skipped there.
        assertTrue(usInside.within(tileAt(-111.9, 40.8)))
        // On the 49th parallel near Waterton: USGS covers part of it, but not all.
        val border = tileAt(-113.9, 49.0)
        assertTrue(usCover.touches(border))
        assertFalse(usCover.within(border))
        // Garibaldi: USGS has nothing.
        assertFalse(usCover.touches(tileAt(-123.0, 49.9)))
    }

    @Test
    fun tileBoxes() {
        val b = Outline.tileBox(1, 0, 0)
        assertEquals(-180.0, b[0], 1e-9)
        assertEquals(0.0, b[1], 1e-9)
        assertEquals(0.0, b[2], 1e-9)
        assertEquals(85.0511, b[3], 1e-3)
    }

    @Test
    fun exportUrlsGetTheTilesBbox() {
        val sk = Imagery.service("sk")!!
        val url = Imagery.tileUrl(sk, 1, 0, 0)
        assertTrue(url, url.contains("bbox=-2.0037508342789244E7,0.0,0.0,2.0037508342789244E7"))
    }
}
