package com.hikeratlas.android.style

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StyleRewriterTest {
    private val style = """
        {"version": 8,
         "sources": {
           "terrain": {"type": "raster-dem", "tiles": ["https://tiles.mapterhorn.com/{z}/{x}/{y}.webp"]},
           "contours": {"type": "vector", "tiles": ["dem-contour://{z}/{x}/{y}?buffer=1"]},
           "other": {"type": "vector", "tiles": ["custom://{z}/{x}/{y}"]},
           "tiles1": {"type": "vector", "url": "pmtiles://https://public.hikeratlas.com/north-america.pmtiles",
                      "attribution": "OSM"}
         },
         "layers": [
           {"id": "background", "type": "background"},
           {"id": "contour-major", "type": "line", "source": "contours"},
           {"id": "custom", "type": "line", "source": "other"},
           {"id": "roads", "type": "line", "source": "tiles1"},
           {"id": "hillshade", "type": "hillshade", "source": "terrain"}
         ]}
    """.trimIndent()

    @Test
    fun rewritesSourcesTheAppServesAndDropsOthers() {
        val opened = mutableListOf<String>()
        val out = JSONObject(StyleRewriter.rewrite(style) { url ->
            opened += url
            StyleRewriter.TileSource("https://tiles.example/{z}/{x}/{y}", 0, 15)
        })
        assertEquals(listOf("https://public.hikeratlas.com/north-america.pmtiles"), opened)

        val sources = out.getJSONObject("sources")
        assertEquals(
            "https://contours.hikeratlas.invalid/{z}/{x}/{y}?buffer=1",
            sources.getJSONObject("contours").getJSONArray("tiles").getString(0),
        )
        assertEquals(
            "https://dem.hikeratlas.invalid/{z}/{x}/{y}",
            sources.getJSONObject("terrain").getJSONArray("tiles").getString(0),
        )
        assertFalse(sources.has("other"))
        val t = sources.getJSONObject("tiles1")
        assertFalse(t.has("url"))
        assertEquals("https://tiles.example/{z}/{x}/{y}", t.getJSONArray("tiles").getString(0))
        assertEquals(15, t.getInt("maxzoom"))
        assertEquals("OSM", t.getString("attribution"))

        assertEquals(listOf("https://public.hikeratlas.com/north-america.pmtiles"), StyleRewriter.archivesOf(out.toString()))
        assertEquals(listOf("https://public.hikeratlas.com/north-america.pmtiles"), StyleRewriter.pmtilesUrls(style))

        val layers = out.getJSONArray("layers")
        assertEquals(listOf("background", "contour-major", "roads", "hillshade"), (0 until layers.length()).map { layers.getJSONObject(it).getString("id") })
    }
}
