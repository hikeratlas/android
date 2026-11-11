package com.hikeratlas.android.contours

import com.hikeratlas.android.elevation.DemTiles
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Contour tiles made on the device from the terrain tiles, at
 * https://contours.hikeratlas.invalid/{z}/{x}/{y}?<options>: what the
 * website's maplibre-contour makes in the browser for the style's
 * dem-contour:// source, with the same options, layer and properties, so the
 * basemap style's contour layers work unchanged.
 */
class ContourServer(private val dem: DemTiles) : Interceptor {
    /** maplibre-contour's options, from the source URL's query. */
    data class Options(
        /** zoom -> [minor, major, ...] intervals, in metres. */
        val thresholds: Map<Int, List<Double>>,
        val layer: String = "contours",
        val elevationKey: String = "ele",
        val levelKey: String = "level",
        val extent: Int = 4096,
        val buffer: Int = 1,
        val overzoom: Int = 0,
    ) {
        /** The intervals at a zoom: those of the deepest zoom up to it. */
        fun at(z: Int): List<Double>? = thresholds.filterKeys { it <= z }.maxByOrNull { it.key }?.value

        companion object {
            fun parse(url: HttpUrl) = Options(
                thresholds = (url.queryParameter("thresholds") ?: "").split('~').filter { it.isNotEmpty() }.associate { t ->
                    val parts = t.split('*')
                    parts[0].toInt() to parts.drop(1).map { it.toDouble() }
                },
                layer = url.queryParameter("contourLayer") ?: "contours",
                elevationKey = url.queryParameter("elevationKey") ?: "ele",
                levelKey = url.queryParameter("levelKey") ?: "level",
                extent = url.queryParameter("extent")?.toIntOrNull() ?: 4096,
                buffer = url.queryParameter("buffer")?.toIntOrNull() ?: 1,
                overzoom = url.queryParameter("overzoom")?.toIntOrNull() ?: 0,
            )
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != HOST) return chain.proceed(request)
        val p = request.url.pathSegments.mapNotNull { it.toIntOrNull() }
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        if (p.size != 3) return builder.code(404).message("Not Found").body(ByteArray(0).toResponseBody()).build()
        val tile = tile(p[0], p[1], p[2], Options.parse(request.url))
        return if (tile == null) {
            builder.code(204).message("No Content").body(ByteArray(0).toResponseBody()).build()
        } else {
            builder.code(200).message("OK").header("Cache-Control", "max-age=604800")
                .body(tile.toResponseBody(MVT)).build()
        }
    }

    /** The contour tile, or null if it has no lines. */
    fun tile(z: Int, x: Int, y: Int, o: Options): ByteArray? {
        val intervals = o.at(z) ?: return null
        val minor = intervals.first()
        val dz = z - o.overzoom
        if (dz < 0) return null
        // This tile's DEM pixels at dz, plus a buffer from its neighbours so
        // lines run on past the edge and meet the next tile's.
        val span = DemTiles.SIZE shr o.overzoom
        val ox = x * span - o.buffer
        val oy = y * span - o.buffer
        val n = span + 2 * o.buffer
        val size = DemTiles.SIZE
        val tx0 = Math.floorDiv(ox, size)
        val ty0 = Math.floorDiv(oy, size)
        val tx1 = Math.floorDiv(ox + n - 1, size)
        val ty1 = Math.floorDiv(oy + n - 1, size)
        val limit = (1 shl dz) - 1
        // The few DEM tiles this touches, fetched once. Past the world's
        // edge (north and south), the edge tile stands in.
        val tiles = HashMap<Long, FloatArray>()
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            tiles[(tx.toLong() shl 32) or (ty.toLong() and 0xffffffffL)] =
                dem.heights(dz, Math.floorMod(tx, 1 shl dz), ty.coerceIn(0, limit)).h
        }
        val grid = FloatArray(n * n)
        for (j in 0 until n) {
            val gy = oy + j
            val ty = Math.floorDiv(gy, size)
            val ry = Math.floorMod(gy, size) * size
            for (i in 0 until n) {
                val gx = ox + i
                val h = tiles.getValue((Math.floorDiv(gx, size).toLong() shl 32) or (ty.toLong() and 0xffffffffL))
                grid[j * n + i] = h[ry + Math.floorMod(gx, size)]
            }
        }
        val mvt = MvtLayer(o.layer, o.extent)
        // Grid point i is the centre of DEM pixel ox + i.
        val scale = o.extent.toDouble() / span
        for ((ele, lines) in Isolines.trace(grid, n, n, minor)) {
            val level = intervals.indices.last { k -> isMultiple(ele, intervals[k]) }
            val coords = lines.map { line ->
                IntArray(line.size) { k ->
                    val g = line[k] + 0.5 - o.buffer // DEM pixels from the tile's edge
                    (g * scale).roundToInt()
                }
            }
            mvt.add(mapOf(o.elevationKey to ele.roundToLong(), o.levelKey to level.toLong()), coords)
        }
        return if (mvt.isEmpty) null else mvt.encode()
    }

    private fun isMultiple(v: Double, step: Double): Boolean {
        val r = v / step
        return kotlin.math.abs(r - Math.rint(r)) < 1e-6
    }

    companion object {
        const val HOST = "contours.hikeratlas.invalid"
        private val MVT = "application/vnd.mapbox-vector-tile".toMediaType()
    }
}
