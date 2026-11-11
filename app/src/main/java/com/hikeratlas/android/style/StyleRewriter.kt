package com.hikeratlas.android.style

import org.json.JSONArray
import org.json.JSONObject

/**
 * Adapts the basemap's MapLibre GL JS style for MapLibre Native.
 *
 * - pmtiles:// sources become tile URL templates served by
 *   [com.hikeratlas.android.pmtiles.TileServer], with the zoom range from the
 *   archive's header.
 * - Contours (maplibre-contour's dem-contour:// on the website) come from
 *   [com.hikeratlas.android.contours.ContourServer], with the same options.
 * - Mapterhorn's terrain tiles go through
 *   [com.hikeratlas.android.elevation.DemServer], for nl-dem over
 *   Newfoundland and no holes past Mapterhorn's coverage.
 * - Sources on any other protocol the app can't serve are dropped, with
 *   their layers.
 */
object StyleRewriter {
    /**
     * Bump when the rewriting changes, so a style saved by an older build
     * (see StyleLoader) isn't used: it would lack what the change adds.
     * 2: contours and terrain from the app's own servers.
     */
    const val VERSION = 2

    /** What [rewrite] needs to know about an archive. */
    data class TileSource(val template: String, val minZoom: Int, val maxZoom: Int)

    fun rewrite(style: String, openArchive: (url: String) -> TileSource): String {
        val json = JSONObject(style)
        val sources = json.getJSONObject("sources")
        val dropped = mutableSetOf<String>()
        val archives = JSONArray()

        for (name in sources.keys().asSequence().toList()) {
            val source = sources.getJSONObject(name)
            val url = source.optString("url")
            if (url.startsWith(PMTILES)) {
                archives.put(url.removePrefix(PMTILES))
                val archive = openArchive(url.removePrefix(PMTILES))
                source.remove("url")
                source.put("tiles", JSONArray().put(archive.template))
                source.put("minzoom", archive.minZoom)
                source.put("maxzoom", archive.maxZoom)
            } else {
                source.optJSONArray("tiles")?.let { tiles ->
                    for (k in 0 until tiles.length()) tiles.put(k, local(tiles.getString(k)))
                }
                if (!servable(url) || !allServable(source.optJSONArray("tiles"))) dropped += name
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
        // Which archives the tile URLs serve, for starting from a saved copy.
        val meta = json.optJSONObject("metadata") ?: JSONObject().also { json.put("metadata", it) }
        meta.put(ARCHIVES, archives)
        return json.toString()
    }

    /** The archives a rewritten style's tiles come from. */
    fun archivesOf(rewritten: String): List<String> {
        val a = JSONObject(rewritten).optJSONObject("metadata")?.optJSONArray(ARCHIVES) ?: return emptyList()
        return (0 until a.length()).map { a.getString(it) }
    }

    private const val ARCHIVES = "hikeratlas:archives"

    /** The archives a style's pmtiles:// sources name. */
    fun pmtilesUrls(style: String): List<String> {
        val sources = JSONObject(style).getJSONObject("sources")
        return sources.keys().asSequence().mapNotNull { sources.getJSONObject(it).optString("url").takeIf { u -> u.startsWith(PMTILES) } }
            .map { it.removePrefix(PMTILES) }.distinct().toList()
    }

    private const val PMTILES = "pmtiles://"
    private const val CONTOURS = "dem-contour://{z}/{x}/{y}"
    private const val MAPTERHORN = "https://tiles.mapterhorn.com/{z}/{x}/{y}.webp"

    /** A tile URL template the app serves itself, in place of [url]. */
    private fun local(url: String): String = when {
        url.startsWith(CONTOURS) -> "https://${com.hikeratlas.android.contours.ContourServer.HOST}/{z}/{x}/{y}" + url.removePrefix(CONTOURS)
        url == MAPTERHORN -> "https://${com.hikeratlas.android.elevation.DemServer.HOST}/{z}/{x}/{y}"
        else -> url
    }

    private fun servable(url: String) = url.isEmpty() || url.startsWith("https://") || url.startsWith("http://")

    private fun allServable(tiles: JSONArray?): Boolean {
        if (tiles == null) return true
        return (0 until tiles.length()).all { servable(tiles.getString(it)) }
    }
}
