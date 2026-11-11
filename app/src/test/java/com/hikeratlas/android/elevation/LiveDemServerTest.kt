package com.hikeratlas.android.elevation

import android.graphics.BitmapFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Terrain tiles from DemServer, live. Opt in with HIKERATLAS_LIVE=1. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LiveDemServerTest {
    private val base = OkHttpClient()
    private val sources = listOf(NlDemSource(base), MapterhornSource(base))
    private val client = OkHttpClient.Builder().addInterceptor(DemServer(DemTiles(sources), sources)).build()

    private fun tile(lon: Double, lat: Double, z: Int): String {
        val n = 1 shl z
        val x = ((lon + 180) / 360 * n).toInt()
        val r = Math.toRadians(lat)
        val y = ((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * n).toInt()
        return "$z/$x/$y"
    }

    private fun heights(path: String): Pair<String, FloatArray> =
        client.newCall(Request.Builder().url("https://${DemServer.HOST}/$path").build()).execute().use { r ->
            assertEquals(200, r.code)
            val type = r.body.contentType().toString()
            val bmp = BitmapFactory.decodeStream(r.body.byteStream())
            val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
            type to ElevationProfiler.terrarium(px)
        }

    @Test
    fun fillsYohoPastMapterhornsCoverage() {
        assumeTrue(System.getenv("HIKERATLAS_LIVE") == "1")
        // Mapterhorn has only z12 in Yoho (as of 2026-11); z14 comes from it, upsampled.
        val (type, h) = heights(tile(-116.5, 51.4, 14))
        assertEquals("image/png", type)
        assertTrue("Yoho's heights: ${h.min()}..${h.max()}", h.min() > 1000 && h.max() < 3600)
    }

    @Test
    fun usesNlDemOverNewfoundland() {
        assumeTrue(System.getenv("HIKERATLAS_LIVE") == "1")
        // Gros Morne: nl-dem's own z13 tile, as published (WebP).
        val (t13, h13) = heights(tile(-57.75, 49.6, 13))
        assertEquals("image/webp", t13)
        // z14 is cut from nl-dem's z13, not Mapterhorn's blurrier z14.
        val (t14, h14) = heights(tile(-57.75, 49.6, 14))
        assertEquals("image/png", t14)
        assertTrue(h14.max() <= h13.max() + 1 && h14.min() >= h13.min() - 1)
    }

    @Test
    fun passesMapterhornTilesThrough() {
        assumeTrue(System.getenv("HIKERATLAS_LIVE") == "1")
        val (type, h) = heights(tile(-123.04, 49.97, 13)) // Garibaldi
        assertEquals("image/webp", type)
        assertTrue(h.max() > 2000)
    }
}
