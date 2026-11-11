package com.hikeratlas.android.pmtiles

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves PMTiles archives to MapLibre as ordinary {z}/{x}/{y} tile URLs.
 *
 * MapLibre Native can't decode the basemap's zstd tiles (or merged tiles of
 * several zstd frames), so the style's pmtiles:// sources are rewritten to
 * https://tiles.hikeratlas.invalid/<archive>/{z}/{x}/{y} and this interceptor,
 * installed on MapLibre's OkHttp client, answers those requests from a
 * [Pmtiles] reader with the decompressed tile. Nothing goes over the network
 * for that host (.invalid can't resolve); the reader makes its own range
 * requests, or reads a file on disk.
 */
object TileServer : Interceptor {
    const val HOST = "tiles.hikeratlas.invalid"

    private val archives = ConcurrentHashMap<String, Pmtiles>()

    /**
     * Registers an archive and returns the tile URL template for it. The URL
     * depends only on the source's key, so MapLibre's tile cache stays valid
     * across launches.
     */
    fun register(source: RangeSource): Pair<Pmtiles, String> {
        val id = shortHash(source.key)
        val archive = archives.getOrPut(id) { Pmtiles(source) }
        return archive to "https://$HOST/$id/{z}/{x}/{y}"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != HOST) return chain.proceed(request)

        val parts = request.url.pathSegments
        val archive = parts.getOrNull(0)?.let { archives[it] }
        val z = parts.getOrNull(1)?.toIntOrNull()
        val x = parts.getOrNull(2)?.toIntOrNull()
        val y = parts.getOrNull(3)?.toIntOrNull()
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        if (archive == null || z == null || x == null || y == null) {
            return builder.code(404).message("Not Found").body(ByteArray(0).toResponseBody()).build()
        }
        val tile = try {
            archive.tile(z, x, y)
        } catch (e: IllegalArgumentException) {
            null
        }
        // An IOException propagates: MapLibre treats it as a connection
        // error and retries later, rather than caching an empty tile.
        return if (tile == null) {
            // 204: MapLibre draws nothing here and doesn't retry.
            builder.code(204).message("No Content").body(ByteArray(0).toResponseBody()).build()
        } else {
            builder.code(200).message("OK")
                // Archives are rebuilt daily; a day's caching is about right.
                .header("Cache-Control", "max-age=86400")
                .body(tile.toResponseBody(MVT))
                .build()
        }
    }

    private val MVT = "application/vnd.mapbox-vector-tile".toMediaType()

    private fun shortHash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
}
