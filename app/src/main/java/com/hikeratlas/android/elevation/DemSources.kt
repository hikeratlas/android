package com.hikeratlas.android.elevation

import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import com.hikeratlas.android.pmtiles.HttpRangeSource
import com.hikeratlas.android.pmtiles.Pmtiles
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Decodes a Terrarium WebP/PNG tile to elevations. */
fun decodeTerrarium(bytes: ByteArray): FloatArray {
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("undecodable DEM tile")
    val size = ElevationProfiler.SIZE
    val px = IntArray(size * size)
    val scaled = if (bmp.width == size && bmp.height == size) bmp else bmp.scale(size, size)
    scaled.getPixels(px, 0, size, 0, 0, size, size)
    return ElevationProfiler.terrarium(px)
}

/** Mapterhorn's global DEM. 404s past its coverage, where an ancestor is used. */
class MapterhornSource(private val client: OkHttpClient) : ElevationProfiler.DemSource {
    override val minZoom = 0
    // z16 on US lidar, z15 on Canadian; elsewhere it 404s past its data.
    override val maxZoom = 16

    override fun tile(z: Int, x: Int, y: Int): FloatArray? = raw(z, x, y)?.let(::decodeTerrarium)

    override fun raw(z: Int, x: Int, y: Int): ByteArray? {
        val req = Request.Builder().url("https://tiles.mapterhorn.com/$z/$x/$y.webp").build()
        client.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw IOException("Mapterhorn ${r.code} for $z/$x/$y")
            return r.body.bytes()
        }
    }
}

/**
 * hikeratlas/nl-dem: 5 m tiles over Newfoundland, z10-13. Each is complete
 * (filled from Mapterhorn at the coast), so it stitches with Mapterhorn's.
 */
class NlDemSource(client: OkHttpClient) : ElevationProfiler.DemSource {
    private val archive = Pmtiles(HttpRangeSource("https://public.hikeratlas.com/nl-dem/nl-dem.pmtiles", client))
    override val minZoom = 10
    override val maxZoom = 13

    override fun tile(z: Int, x: Int, y: Int): FloatArray? = raw(z, x, y)?.let(::decodeTerrarium)

    override fun raw(z: Int, x: Int, y: Int): ByteArray? = try {
        lookup(z, x, y)
    } catch (e: IOException) {
        // As the website: if nl-dem can't be read, Mapterhorn will do.
        null
    }

    private fun lookup(z: Int, x: Int, y: Int): ByteArray? {
        // Outside Newfoundland, skip the lookup, without reading the
        // archive's header for its bounds (a round trip).
        if (!covers(z, x, y)) return null
        return archive.tile(z, x, y)
    }

    companion object {
        /** The archive's bounds (from its header, rounded out a little): w, s, e, n. As the website's NL_BOUNDS. */
        val BOUNDS = doubleArrayOf(-59.5, 46.5, -52.5, 51.7)

        /** Whether tile z/x/y overlaps nl-dem's bounds. */
        fun covers(z: Int, x: Int, y: Int): Boolean {
            val n = (1 shl z).toDouble()
            val west = x / n * 360 - 180
            val east = (x + 1) / n * 360 - 180
            val north = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y / n))))
            val south = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (y + 1) / n))))
            return east >= BOUNDS[0] && west <= BOUNDS[2] && north >= BOUNDS[1] && south <= BOUNDS[3]
        }
    }
}
