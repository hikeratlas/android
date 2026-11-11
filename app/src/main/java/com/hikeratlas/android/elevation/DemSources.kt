package com.hikeratlas.android.elevation

import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import com.hikeratlas.android.pmtiles.HttpRangeSource
import com.hikeratlas.android.pmtiles.Pmtiles
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Decodes a Terrarium WebP/PNG tile to elevations. */
private fun decode(bytes: ByteArray): FloatArray {
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
    override val maxZoom = 14

    override fun tile(z: Int, x: Int, y: Int): FloatArray? {
        val req = Request.Builder().url("https://tiles.mapterhorn.com/$z/$x/$y.webp").build()
        client.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw IOException("Mapterhorn ${r.code} for $z/$x/$y")
            return decode(r.body.bytes())
        }
    }
}

/**
 * hikeratlas/nl-dem: 5 m tiles over Newfoundland, up to z13. Only its z13
 * tiles are used, as the website does: past those, Mapterhorn's are better.
 */
class NlDemSource(client: OkHttpClient) : ElevationProfiler.DemSource {
    private val archive = Pmtiles(HttpRangeSource("https://public.hikeratlas.com/nl-dem/nl-dem.pmtiles", client))
    override val minZoom = 13
    override val maxZoom = 13

    override fun tile(z: Int, x: Int, y: Int): FloatArray? = try {
        lookup(z, x, y)
    } catch (e: IOException) {
        // As the website: if nl-dem can't be read, Mapterhorn will do.
        null
    }

    private fun lookup(z: Int, x: Int, y: Int): FloatArray? {
        // Outside Newfoundland (its header's bounds), skip the lookup.
        val h = archive.header()
        val n = (1 shl z).toDouble()
        val west = x / n * 360 - 180
        val east = (x + 1) / n * 360 - 180
        val north = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y / n))))
        val south = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (y + 1) / n))))
        if (east < h.minLon || west > h.maxLon || north < h.minLat || south > h.maxLat) return null
        return archive.tile(z, x, y)?.let(::decode)
    }
}
