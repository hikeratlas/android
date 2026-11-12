package com.hikeratlas.android.offline

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.hikeratlas.android.elevation.MapterhornSource
import com.hikeratlas.android.pmtiles.BytesSource
import com.hikeratlas.android.pmtiles.Compression
import com.hikeratlas.android.pmtiles.Pmtiles
import com.hikeratlas.android.pmtiles.TestArchive
import com.hikeratlas.android.pmtiles.TileId
import com.hikeratlas.android.routes.RoutesRepository
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An app with offline areas of its own, over test archives and a local server. */
class OfflineTestApp : Application(), OfflineHost {
    override lateinit var offlineAreas: OfflineAreas
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = OfflineTestApp::class)
class AreaWorkerTest {
    private val app get() = ApplicationProvider.getApplicationContext<OfflineTestApp>()
    private val server = MockWebServer()
    private lateinit var store: OfflineStore
    /** The routes index answers 500. */
    @Volatile private var failing = false
    /** The archives' builds, as HEAD requests would say. */
    private var build = OfflineAreas.Build("\"b1\"", "\"r1\"")

    // A park: a small box near Squamish, one day hike across it.
    private val box = listOf(-123.06, 49.95, -123.0, 49.99)
    private val park = """{"park":{"id":"1","name":"Test Park"},"day_hikes":[{"from":"A","dest":"B","kind":"day",
        "route":{"km":3.0,"gain":100.0,"line":[[-123.05,49.96],[-123.02,49.97],[-123.01,49.98]]}}]}"""

    private fun archive(name: String): BytesSource {
        val tiles = (0..2).flatMap { z -> (0 until (1 shl z)).flatMap { x -> (0 until (1 shl z)).map { y -> Triple(z, x, y) } } }
            .associate { (z, x, y) -> TileId.fromZxy(z, x, y) to TestArchive.gzip("$name $z/$x/$y".toByteArray()) }
        return BytesSource(TestArchive.build(tiles, Compression.GZIP, 0, 2), key = "https://x/$name.pmtiles")
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val gz = TestArchive.gzip(park.toByteArray())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/regions.json" -> MockResponse.Builder().body(
                    """{"countries":[{"name":"Canada","slug":"canada","states":[{"name":"BC","slug":"bc","areas":[
                        {"id":"1","name":"Test Park","slug":"test-park","ids":["1","5"],"bbox":[${box.joinToString(",")}]}]}]}]}""",
                ).build()
                "/index.json" -> if (failing) MockResponse.Builder().code(500).build()
                    else MockResponse.Builder().body("""{"bundle":"b.bin","parks":{"1":[0,${gz.size}],"5":[0,${gz.size}]}}""").build()
                "/b.bin" -> MockResponse.Builder().code(206).body(Buffer().write(gz)).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
        store = OfflineStore(app, name = null)
        val basemap = Pmtiles(archive("basemap")) { null }
        val routesArchive = Pmtiles(archive("routes")) { null }
        val routes = RoutesRepository(OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build(), server.url("/").toString().trimEnd('/'))
        app.offlineAreas = OfflineAreas(app, store, routes, { basemap }, { routesArchive }, { z, x, y -> "dem $z/$x/$y".toByteArray() }, { build })
    }

    @After
    fun tearDown() {
        store.close()
        server.close()
    }

    @Test
    fun aParkDownloadsItsTilesAndRoutes() = runBlocking {
        val area = store.area(OfflineStore.PARK, "1", "Test Park")
        val worker = TestListenableWorkerBuilder<AreaWorker>(app).setInputData(workDataOf(AreaWorker.AREA to area.id)).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())

        val done = store.areaById(area.id)!!
        assertTrue(done.ready)
        assertEquals(done.total, done.done)
        assertTrue(done.bytes > 0)
        // The basemap and routes overlay, the terrain over the park, and the park's routes JSON.
        assertNotNull(store.tile("https://x/basemap.pmtiles", 2, 0, 1))
        assertNotNull(store.tile("https://x/routes.pmtiles", 0, 0, 0))
        val (x, y) = TileCover.tile(-123.03, 49.97, 12)
        assertNotNull(store.tile(MapterhornSource.KEY, 12, x, y))
        assertNotNull(store.resource(app.offlineAreas.let { server.url("/parks/1.json.gz").toString() }))
    }

    @Test
    fun downloadsWaitForWifiAndBringTheOverviewTheFirstTime() = runBlocking {
        val areas = app.offlineAreas
        areas.download(OfflineAreas.Target(OfflineStore.PARK, "1", "Test Park"))
        assertEquals(listOf(OfflineStore.PARK, OfflineStore.OVERVIEW).sorted(), store.areas().map { it.kind }.sorted())
        val wm = WorkManager.getInstance(app)
        val parkWork = { wm.getWorkInfosForUniqueWork(OfflineAreas.workName(store.findArea(OfflineStore.PARK, "1")!!.id)).get().single() }
        assertEquals(NetworkType.UNMETERED, parkWork().constraints.requiredNetworkType)

        // Wi-Fi only off: what's waiting goes on any network.
        areas.wifiOnly = false
        assertEquals(NetworkType.CONNECTED, parkWork().constraints.requiredNetworkType)
        areas.wifiOnly = true

        // A second download doesn't bring another overview.
        areas.download(OfflineAreas.Target(OfflineStore.HIKE, "1/day/0", "B from A"))
        assertEquals(1, store.areas().count { it.kind == OfflineStore.OVERVIEW })
    }

    @Test
    fun anAreaFromAnOlderBuildOffersAnUpdate() = runBlocking {
        val areas = app.offlineAreas
        val area = store.area(OfflineStore.PARK, "1", "Test Park")
        TestListenableWorkerBuilder<AreaWorker>(app).setInputData(workDataOf(AreaWorker.AREA to area.id)).build().doWork()
        assertEquals(build.key, store.areaById(area.id)!!.build)
        areas.checkUpdates()
        assertEquals(false, areas.updateAvailable(store.areaById(area.id)!!))

        // The routes archive is rebuilt: the park's out of date, the overview (basemap only) isn't.
        val overview = store.area(OfflineStore.OVERVIEW, "", "Overview").id
        store.finishArea(overview)
        store.setAreaBuild(overview, build.key)
        build = build.copy(routes = "\"r2\"")
        areas.checkUpdates()
        assertTrue(areas.updateAvailable(store.areaById(area.id)!!))
        assertEquals(false, areas.updateAvailable(store.areaById(overview)!!))

        // Updated: downloaded again from the new build.
        val again = TestListenableWorkerBuilder<AreaWorker>(app)
            .setInputData(workDataOf(AreaWorker.AREA to area.id, AreaWorker.REFRESH to true)).build()
        assertEquals(ListenableWorker.Result.success(), again.doWork())
        assertEquals(false, areas.updateAvailable(store.areaById(area.id)!!))
    }

    @Test
    fun areasFromBeforeBuildsWereKeptAreTakenAsCurrent() = runBlocking {
        val id = store.area(OfflineStore.PARK, "1", "Test Park").id
        store.finishArea(id)
        assertEquals(null, store.areaById(id)!!.build)
        app.offlineAreas.checkUpdates()
        assertEquals(build.key, store.areaById(id)!!.build)
        assertEquals(false, app.offlineAreas.updateAvailable(store.areaById(id)!!))
    }

    @Test
    fun deletingAllKeepsTheSmallSharedFiles() = runBlocking {
        val area = store.area(OfflineStore.PARK, "1", "Test Park")
        TestListenableWorkerBuilder<AreaWorker>(app).setInputData(workDataOf(AreaWorker.AREA to area.id)).build().doWork()
        store.putResource("https://x/index.json", ByteArray(10))
        app.offlineAreas.deleteAll()
        assertEquals(emptyList<OfflineStore.Area>(), store.areas())
        assertEquals(null, store.tile("https://x/basemap.pmtiles", 0, 0, 0))
        assertNotNull(store.resource("https://x/index.json"))
        assertEquals(10L, app.offlineAreas.bytes())
    }

    /** Pacific Rim's area is three parks; its West Coast Trail is one of the others (#23). */
    @Test
    fun aParkIsFoundByAnyOfItsAreasIds() = runBlocking {
        val area = store.area(OfflineStore.PARK, "5", "West Coast Trail unit")
        val w = TestListenableWorkerBuilder<AreaWorker>(app).setInputData(workDataOf(AreaWorker.AREA to area.id)).build()
        assertEquals(ListenableWorker.Result.success(), w.doWork())
        assertTrue(store.areaById(area.id)!!.ready)
    }

    @Test
    fun aParkThatCantBePlannedFailsAtOnceSayingWhy() = runBlocking {
        val area = store.area(OfflineStore.PARK, "999", "Nowhere Park")
        val w = TestListenableWorkerBuilder<AreaWorker>(app).setInputData(workDataOf(AreaWorker.AREA to area.id)).build()
        assertEquals(ListenableWorker.Result.failure(), w.doWork())
        val a = store.areaById(area.id)!!
        assertEquals(OfflineStore.FAILED, a.state)
        assertEquals("The map has no outline for Nowhere Park.", a.error)
    }

    /** A network error says it's trying again, and why: not "waiting for Wi-Fi" (#24). */
    @Test
    fun aNetworkErrorSaysItsRetrying() = runBlocking {
        failing = true
        val area = store.area(OfflineStore.HIKE, "1/day/0", "B from A")
        val w = TestListenableWorkerBuilder<AreaWorker>(app).setInputData(workDataOf(AreaWorker.AREA to area.id)).build()
        assertEquals(ListenableWorker.Result.retry(), w.doWork())
        val a = store.areaById(area.id)!!
        assertEquals(OfflineStore.RETRYING, a.state)
        assertTrue(a.error!!, a.error!!.contains("500"))
    }
}
