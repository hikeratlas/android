package com.hikeratlas.android.elevation

import android.graphics.Bitmap
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream

/**
 * Serves the hillshade's terrain tiles at
 * https://dem.hikeratlas.invalid/{z}/{x}/{y}, as the website's nldem://
 * does: nl-dem's 5 m tiles over Newfoundland, else Mapterhorn's. Where the
 * best source has no tile that deep (Mapterhorn past its coverage, nl-dem
 * past z13), the tile is cut from its nearest ancestor and upsampled, so the
 * hillshade has no holes and no blurrier Mapterhorn patches in Newfoundland.
 */
class DemServer(private val dem: DemTiles, private val sources: List<ElevationProfiler.DemSource>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != HOST) return chain.proceed(request)
        val p = request.url.pathSegments.mapNotNull { it.toIntOrNull() }
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        if (p.size != 3) return builder.code(404).message("Not Found").body(ByteArray(0).toResponseBody()).build()
        val (z, x, y) = p

        // As published, from the first source with this tile, unless a
        // source before it covers the place only at shallower zooms (nl-dem
        // past z13): then that source's ancestor, as below.
        for (s in sources) {
            if (z in s.minZoom..s.maxZoom) {
                val raw = s.raw(z, x, y) ?: continue
                return builder.code(200).message("OK").header("Cache-Control", "max-age=604800")
                    .body(raw.toResponseBody(WEBP)).build()
            }
            if (z > s.maxZoom && s.raw(s.maxZoom, x shr (z - s.maxZoom), y shr (z - s.maxZoom)) != null) break
        }
        // Otherwise, from an ancestor.
        val h = dem.heights(z, x, y)
        val bmp = Bitmap.createBitmap(DemTiles.encodeTerrarium(h.h), DemTiles.SIZE, DemTiles.SIZE, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return builder.code(200).message("OK").header("Cache-Control", "max-age=604800")
            .body(png.toResponseBody(PNG)).build()
    }

    companion object {
        const val HOST = "dem.hikeratlas.invalid"
        private val WEBP = "image/webp".toMediaType()
        private val PNG = "image/png".toMediaType()
    }
}
