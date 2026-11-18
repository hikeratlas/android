package com.hikeratlas.android

import android.app.Application
import com.hikeratlas.android.contours.ContourServer
import com.hikeratlas.android.elevation.DemServer
import com.hikeratlas.android.elevation.DemTiles
import com.hikeratlas.android.elevation.ElevationProfiler
import com.hikeratlas.android.imagery.ImageryServer
import com.hikeratlas.android.elevation.MapterhornSource
import com.hikeratlas.android.elevation.NlDemSource
import com.hikeratlas.android.pmtiles.TileServer
import com.hikeratlas.android.pmtiles.Offline
import com.hikeratlas.android.offline.OfflineStore
import com.hikeratlas.android.offline.MapConnectivity
import com.hikeratlas.android.offline.OfflineAreas
import com.hikeratlas.android.offline.OfflineHost
import com.hikeratlas.android.pmtiles.HttpRangeSource
import com.hikeratlas.android.style.StyleLoader
import com.hikeratlas.android.offline.ResourceCache
import com.hikeratlas.android.routes.RoutesRepository
import com.hikeratlas.android.search.RecentSearches
import com.hikeratlas.android.search.SearchApi
import com.hikeratlas.android.track.RecorderHost
import com.hikeratlas.android.track.TrackLibrary
import com.hikeratlas.android.track.TrackRecorder
import com.hikeratlas.android.track.TrackStore
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

class HikerAtlasApp : Application(), RecorderHost, OfflineHost, com.hikeratlas.android.outbox.OutboxHost {
    /** For our own requests: the style, and PMTiles range reads. */
    lateinit var http: OkHttpClient
        private set

    val routes by lazy { RoutesRepository(http) }
    /** Terrain: nl-dem over Newfoundland first, then Mapterhorn. */
    private val demSources by lazy { listOf(NlDemSource(http), MapterhornSource(http)) }
    val dem by lazy { DemTiles(demSources) }
    val profiler by lazy { ElevationProfiler(demSources) }
    val search by lazy { SearchApi(http) }
    val recentSearches by lazy { RecentSearches(this) }
    val tracks by lazy { TrackStore(this) }
    override val recorder by lazy {
        TrackRecorder(
            tracks, profiler, steps = com.hikeratlas.android.track.SensorStepSource(this),
            countSteps = { com.hikeratlas.android.track.StepsSetting.on(this) },
        )
    }
    val library by lazy { TrackLibrary(tracks, profiler, regions = { routes.regions() }) }

    /** Search with no network: the downloaded parks and hikes. */
    val offlineSearch by lazy {
        com.hikeratlas.android.search.OfflineSearch(
            regions = { routes.regions() },
            saved = { offlineAreas.areas.filter { it.ready }.map { OfflineAreas.Target(it.kind, it.ref, it.name) } },
            park = { routes.park(it) },
        )
    }

    /** For work that outlives a screen: finishing a sign-in, refreshing its tokens. */
    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    /**
     * The AT Protocol account signed in, if any: its tokens encrypted with a
     * Keystore key, its DPoP key in the Keystore.
     */
    val account by lazy {
        com.hikeratlas.android.atproto.Account(
            http, com.hikeratlas.android.atproto.EncryptedSecretStore(this),
            com.hikeratlas.android.atproto.KeystoreDpopKeys(), scope = appScope,
        )
    }

    /** Photos prepared for the post form, kept until it's done with them. */
    val photoStore by lazy { com.hikeratlas.android.atproto.Photos.Store(java.io.File(filesDir, "photos")) }

    /** Posts waiting on the phone to go up (every post goes this way: see ui/PostFlow.kt). */
    override val outbox by lazy { com.hikeratlas.android.outbox.Outbox(this, account, library, photoStore) }

    /** The units distances and heights show in: the profile's when signed in, else this phone's. */
    val units by lazy {
        com.hikeratlas.android.units.UnitsSetting(getSharedPreferences("units", MODE_PRIVATE), account, appScope)
    }

    /** What's kept for offline use: areas' tiles, routes data, fonts and icons. */
    val offline by lazy { OfflineStore(this) }
    override val offlineAreas by lazy {
        OfflineAreas(
            this, offline, routes,
            basemap = { TileServer.register(HttpRangeSource(StyleLoader.BASEMAP_URL, http)).first },
            routesArchive = { TileServer.register(HttpRangeSource(StyleLoader.ROUTES_URL, http)).first },
            dem = { z, x, y -> MapterhornSource(http).fetch(z, x, y) },
            currentBuild = {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    OfflineAreas.Build(etag(StyleLoader.BASEMAP_URL), etag(StyleLoader.ROUTES_URL))
                }
            },
        )
    }

    /** An archive's ETag, which changes when it's rebuilt. */
    private fun etag(url: String): String =
        http.newCall(okhttp3.Request.Builder().url(url).head().build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code} for $url")
            r.header("ETag") ?: throw java.io.IOException("No ETag for $url")
        }

    override fun onCreate() {
        super.onCreate()
        // First, so everything after can log, and a crash anywhere after is kept.
        com.hikeratlas.android.logs.Log.install(this)
        com.hikeratlas.android.logs.CrashLog.install()
        com.hikeratlas.android.logs.Log.i("app", "Started: ${BuildConfig.VERSION_NAME} ${BuildConfig.COMMIT.take(7)}")
        Offline.tiles = offline
        Offline.resources = offline
        // OkHttp allows 5 requests at a time per host by default. Map tiles
        // all go to one host (TileServer's), so that throttled loading: 24
        // fetched a 6x6 block of tiles about 3x faster than 5 did.
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 24
        }
        http = Http.builder()
            .dispatcher(dispatcher)
            .build()
        com.hikeratlas.android.map.Thumbs.client = http
        // Tells the indexer about each post, fave or delete right after it's written.
        com.hikeratlas.android.atproto.Indexer.notifier = com.hikeratlas.android.atproto.Indexer.http(http)
        MapLibre.getInstance(this)
        // Offline areas answer MapLibre's requests with no network too.
        MapConnectivity.install(this)
        // MapLibre's own requests (tiles, glyphs, sprites) go through this
        // client, so TileServer can answer the rewritten pmtiles sources.
        HttpRequestUtil.setOkHttpClient(
            http.newBuilder()
                .addInterceptor(ResourceCache(ResourceCache.MAP_RESOURCES))
                .addInterceptor(TileServer)
                .addInterceptor(DemServer(dem, demSources))
                .addInterceptor(ContourServer(dem))
                .addInterceptor(ImageryServer(assets, http))
                .build(),
        )
        // Room for a few areas' tiles, so going back somewhere is quick.
        OfflineManager.getInstance(this).setMaximumAmbientCacheSize(AMBIENT_CACHE_BYTES, null)
        // The saved session, restored (in the background) at launch, and its units.
        account
        units
        // Waiting posts: cleaned up, rescheduled, and resumed on sign-in.
        outbox.start(appScope)
    }

    private companion object {
        const val AMBIENT_CACHE_BYTES = 200L * 1024 * 1024
    }
}
