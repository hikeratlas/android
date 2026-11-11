package com.hikeratlas.android

import android.app.Application
import com.hikeratlas.android.elevation.ElevationProfiler
import com.hikeratlas.android.elevation.MapterhornSource
import com.hikeratlas.android.elevation.NlDemSource
import com.hikeratlas.android.pmtiles.TileServer
import com.hikeratlas.android.routes.RoutesRepository
import com.hikeratlas.android.search.RecentSearches
import com.hikeratlas.android.search.SearchApi
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import java.util.concurrent.TimeUnit

class HikerAtlasApp : Application() {
    /** For our own requests: the style, and PMTiles range reads. */
    lateinit var http: OkHttpClient
        private set

    val routes by lazy { RoutesRepository(http) }
    val profiler by lazy { ElevationProfiler(listOf(NlDemSource(http), MapterhornSource(http))) }
    val search by lazy { SearchApi(http) }
    val recentSearches by lazy { RecentSearches(this) }

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
