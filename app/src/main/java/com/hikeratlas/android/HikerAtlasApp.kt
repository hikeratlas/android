package com.hikeratlas.android

import android.app.Application
import com.hikeratlas.android.pmtiles.TileServer
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import java.util.concurrent.TimeUnit

class HikerAtlasApp : Application() {
    /** For our own requests: the style, and PMTiles range reads. */
    lateinit var http: OkHttpClient
        private set

    override fun onCreate() {
        super.onCreate()
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        MapLibre.getInstance(this)
        // MapLibre's own requests (tiles, glyphs, sprites) go through this
        // client, so TileServer can answer the rewritten pmtiles sources.
        HttpRequestUtil.setOkHttpClient(http.newBuilder().addInterceptor(TileServer).build())
    }
}
