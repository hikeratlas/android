package com.hikeratlas.android.style

import com.hikeratlas.android.pmtiles.HttpRangeSource
import com.hikeratlas.android.pmtiles.TileServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Fetches the basemap style and readies it for MapLibre Native.
 *
 * The last style is kept in [cacheDir], so the map can start drawing at once
 * from it (its tile URLs depend only on the archives' URLs) while a fresh
 * copy is fetched for next time.
 */
class StyleLoader(private val client: OkHttpClient, private val cacheDir: File) {
    // Named for the rewriting's version: an older build's copy is ignored.
    private val cached get() = File(cacheDir, "style-v${StyleRewriter.VERSION}.json")

    /** The style from last time, if any. */
    fun cached(): String? = try {
        cached.takeIf { it.exists() }?.readText()
    } catch (e: IOException) {
        null
    }

    /** Fetches and rewrites the current style, and keeps it for next time. */
    suspend fun load(url: String = STYLE_URL): String = withContext(Dispatchers.IO) {
        val style = client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} fetching $url")
            r.body.string()
        }
        // Read every archive's header at once, rather than one after another.
        val urls = StyleRewriter.pmtilesUrls(style)
        val sources = coroutineScope {
            urls.map { u ->
                async {
                    val (archive, template) = TileServer.register(HttpRangeSource(u, client))
                    val h = archive.header()
                    u to StyleRewriter.TileSource(template, h.minZoom, h.maxZoom)
                }
            }.awaitAll().toMap()
        }
        StyleRewriter.rewrite(style) { sources.getValue(it) }.also { json ->
            try {
                cached.writeText(json)
            } catch (e: IOException) {
                // Next launch just waits for the network, as this one did.
            }
        }
    }

    /** Registers the archives a saved style's tiles come from, so TileServer can serve them. */
    fun registerArchives(rewritten: String) {
        for (u in StyleRewriter.archivesOf(rewritten)) TileServer.register(HttpRangeSource(u, client))
    }

    companion object {
        const val TILES_ROOT = "https://public.hikeratlas.com"
        const val STYLE_URL = "$TILES_ROOT/style.json"
    }
}
