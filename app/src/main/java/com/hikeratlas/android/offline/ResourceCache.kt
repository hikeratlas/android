package com.hikeratlas.android.offline

import com.hikeratlas.android.pmtiles.LocalResources
import com.hikeratlas.android.pmtiles.Offline
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Keeps the map's fonts and icons on the phone for good, so the map can draw
 * its labels and symbols offline. MapLibre's own cache would do while there's
 * room, but it evicts. They don't change at a URL (the icons are pinned to a
 * commit; a new font is a new URL), so a kept copy is answered without asking.
 */
class ResourceCache(
    /** URLs starting with one of these are kept. */
    private val prefixes: List<String>,
    private val store: () -> LocalResources? = { Offline.resources },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val s = store()
        if (s == null || request.method != "GET" || prefixes.none { url.startsWith(it) }) return chain.proceed(request)
        s.resource(url)?.let { kept ->
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(kept.toResponseBody(typeOf(url).toMediaTypeOrNull()))
                .build()
        }
        val response = chain.proceed(request)
        if (response.code != 200) return response
        val bytes = response.body.bytes()
        s.putResource(url, bytes)
        return response.newBuilder().body(bytes.toResponseBody(response.body.contentType())).build()
    }

    private fun typeOf(url: String) = when {
        url.endsWith(".png") -> "image/png"
        url.endsWith(".json") -> "application/json"
        url.endsWith(".pbf") -> "application/x-protobuf"
        else -> "application/octet-stream"
    }

    companion object {
        /** The style's glyphs (public.hikeratlas.com/fonts/...) and sprites (hikeratlas/sprites at a commit). */
        val MAP_RESOURCES = listOf(
            "https://public.hikeratlas.com/fonts/",
            "https://raw.githubusercontent.com/hikeratlas/sprites/",
        )
    }
}
