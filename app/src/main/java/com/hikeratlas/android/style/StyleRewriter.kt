package com.hikeratlas.android.style

import org.json.JSONArray
import org.json.JSONObject

/**
 * Adapts the basemap's MapLibre GL JS style for MapLibre Native.
 *
 * - pmtiles:// sources become tile URL templates served by
 *   [com.hikeratlas.android.pmtiles.TileServer], with the zoom range from the
 *   archive's header.
 * - Sources on protocols only the website's JavaScript provides (contours
 *   from maplibre-contour's dem-contour://) are dropped, with their layers.
 */
object StyleRewriter {
    /** What [rewrite] needs to know about an archive. */
    data class TileSource(val template: String, val minZoom: Int, val maxZoom: Int)

    fun rewrite(style: String, openArchive: (url: String) -> TileSource): String {
        val json = JSONObject(style)
        val sources = json.getJSONObject("sources")
        val dropped = mutableSetOf<String>()

        for (name in sources.keys().asSequence().toList()) {
            val source = sources.getJSONObject(name)
            val url = source.optString("url")
            if (url.startsWith(PMTILES)) {
                val archive = openArchive(url.removePrefix(PMTILES))
                source.remove("url")
                source.put("tiles", JSONArray().put(archive.template))
                source.put("minzoom", archive.minZoom)
                source.put("maxzoom", archive.maxZoom)
            } else if (!servable(url) || !allServable(source.optJSONArray("tiles"))) {
                dropped += name
            }
        }
        dropped.forEach { sources.remove(it) }

        val layers = json.getJSONArray("layers")
        val kept = JSONArray()
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            if (layer.optString("source") !in dropped) kept.put(layer)
        }
        json.put("layers", kept)
        return json.toString()
    }

    private const val PMTILES = "pmtiles://"

    private fun servable(url: String) = url.isEmpty() || url.startsWith("https://") || url.startsWith("http://")

    private fun allServable(tiles: JSONArray?): Boolean {
        if (tiles == null) return true
        return (0 until tiles.length()).all { servable(tiles.getString(it)) }
    }
}
