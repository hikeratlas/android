package com.hikeratlas.android.offline

import com.hikeratlas.android.trail.VectorTile
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * The hiking trails in a basemap tile: its "path" layer's lines, as [lng, lat],
 * for a park download's fine terrain. Only trails in parks (in_park), not
 * cycleways, closed trails or ones closed to walkers: a park's box also takes
 * in towns' footways and bike paths (Garibaldi's holds ~1,450 km of "path",
 * ~330 km of them park trails).
 */
object ParkTrails {
    fun keep(p: Map<String, Any?>): Boolean =
        p["in_park"] == true && p["kind"] != "cycleway" && p["closed"] != true && p["foot"] != "no"

    fun lines(tile: ByteArray, z: Int, x: Int, y: Int): List<List<List<Double>>> {
        val layer = VectorTile.layer(tile, "path") ?: return emptyList()
        val ext = layer.extent.toDouble()
        val n = (1 shl z).toDouble()
        return layer.features.asSequence()
            .filter { it.type == VectorTile.Feature.LINESTRING && keep(it.properties) }
            .flatMap { it.parts() }
            .map { part ->
                (0 until part.size / 2).map { k ->
                    val lng = (x + part[2 * k] / ext) / n * 360 - 180
                    val lat = Math.toDegrees(atan(sinh(PI * (1 - 2 * (y + part[2 * k + 1] / ext) / n))))
                    listOf(lng, lat)
                }
            }
            .filter { it.size >= 2 }
            .toList()
    }
}
