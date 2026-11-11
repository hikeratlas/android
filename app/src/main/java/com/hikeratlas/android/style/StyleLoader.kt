package com.hikeratlas.android.style

import com.hikeratlas.android.pmtiles.HttpRangeSource
import com.hikeratlas.android.pmtiles.TileServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Fetches the basemap style and readies it for MapLibre Native. */
class StyleLoader(private val client: OkHttpClient) {
    suspend fun load(url: String = STYLE_URL): String = withContext(Dispatchers.IO) {
        val style = client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} fetching $url")
            r.body.string()
        }
        StyleRewriter.rewrite(style) { archiveUrl ->
            val (archive, template) = TileServer.register(HttpRangeSource(archiveUrl, client))
            val h = archive.header()
            StyleRewriter.TileSource(template, h.minZoom, h.maxZoom)
        }
    }

    companion object {
        const val TILES_ROOT = "https://public.hikeratlas.com"
        const val STYLE_URL = "$TILES_ROOT/style.json"
    }
}
