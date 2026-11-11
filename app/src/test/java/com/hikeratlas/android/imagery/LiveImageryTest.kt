package com.hikeratlas.android.imagery

import android.graphics.BitmapFactory
import org.robolectric.RuntimeEnvironment
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fetches real imagery through ImageryServer. Opt in with HIKERATLAS_LIVE=1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LiveImageryTest {
    private val client by lazy {
        val app = RuntimeEnvironment.getApplication()
        OkHttpClient.Builder().addInterceptor(ImageryServer(app.assets, OkHttpClient())).build()
    }

    private fun get(path: String) = client.newCall(Request.Builder().url("https://${ImageryServer.HOST}/$path").build()).execute()

    /** The z/x/y of the tile holding a point. */
    private fun tile(lon: Double, lat: Double, z: Int): String {
        val n = 1 shl z
        val x = ((lon + 180) / 360 * n).toInt()
        val r = Math.toRadians(lat)
        val y = ((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * n).toInt()
        return "$z/$y/$x"
    }

    @Test
    fun skipsServicesOutsideTheirAreas() {
        assumeTrue(System.getenv("HIKERATLAS_LIVE") == "1")
        // USGS has nothing in Ontario; Sentinel-2 isn't needed deep in Utah.
        get("usgs/" + tile(-78.4, 45.6, 10)).use { assertEquals(204, it.code) }
        get("s2/" + tile(-111.9, 40.8, 10)).use { assertEquals(204, it.code) }
    }

    @Test
    fun passesSentinelTilesThrough() {
        assumeTrue(System.getenv("HIKERATLAS_LIVE") == "1")
        get("s2/" + tile(-123.0, 49.9, 10)).use { r ->
            assertEquals(200, r.code)
            val bmp = BitmapFactory.decodeStream(r.body.byteStream())
            assertEquals(256, bmp.width)
        }
    }

    @Test
    fun clipsUsgsAtTheBorder() {
        assumeTrue(System.getenv("HIKERATLAS_LIVE") == "1")
        // A z10 tile across the 49th parallel near Waterton: Canada (north) is
        // clipped away, the US side (south) kept.
        get("usgs/" + tile(-113.9, 49.0, 10)).use { r ->
            assertEquals(200, r.code)
            assertEquals("image/png", r.body.contentType().toString())
            val bmp = BitmapFactory.decodeStream(r.body.byteStream())
            val w = bmp.width
            val top = bmp.getPixel(w / 2, 2) ushr 24
            val bottom = bmp.getPixel(w / 2, bmp.height - 3) ushr 24
            assertTrue("north of the border is transparent (alpha $top)", top == 0)
            assertTrue("south of the border is kept (alpha $bottom)", bottom == 255)
        }
    }
}
