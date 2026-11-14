package com.hikeratlas.android.routes

import com.hikeratlas.android.pmtiles.MemoryOffline
import com.hikeratlas.android.pmtiles.Offline
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * A fave in a park downloaded for offline use from a build before route
 * keys: looked up in the build online, as the app's RoutesRepository has
 * it; with no network, unsure, never "no longer on Hiker Atlas".
 */
class KeyedHikesOfflineTest {
    private val server = MockWebServer()
    private val kept = MemoryOffline()
    @Volatile private var down = false
    private val nugget = "1E57E06gbDEBBBICDGF.F55FRcODEp_"

    private fun gz(s: String) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(s.toByteArray()) } }.toByteArray()
    private fun doc(key: String?) = """{"park":{"id":"200","name":"Juneau Trails"},"day_hikes":[{"from":"Lot","dest":"Nugget Falls","kind":"out-and-back",
        "route":{"km":3.0,"gain":100.0${if (key != null) ""","route_key":"$key"""" else ""}}}]}"""
    private val regions = """{"countries":[{"name":"US","states":[{"name":"Alaska","areas":[{"id":"200","name":"Juneau Trails","bbox":[-134.6,58.4,-134.5,58.45]}]}]}]}"""

    private lateinit var repo: RoutesRepository

    @Before
    fun setUp() {
        val bundle = gz(doc(nugget))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                down -> MockResponse.Builder().code(503).build()
                request.url.encodedPath == "/index.json" -> MockResponse.Builder().body("""{"bundle":"b.bin","parks":{"200":[0,${bundle.size}]}}""").build()
                request.url.encodedPath == "/regions.json" -> MockResponse.Builder().body(regions).build()
                request.url.encodedPath == "/b.bin" -> MockResponse.Builder().body(Buffer().write(bundle)).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
        Offline.resources = kept
        repo = RoutesRepository(OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build(), server.url("/").toString().trimEnd('/'))
        // The area, downloaded from the build before keys.
        kept.resources[repo.parkKey("200")] = gz(doc(null))
    }

    @After
    fun tearDown() {
        Offline.resources = null
        server.close()
    }

    private suspend fun search() = findKeyedHikes(listOf(nugget), repo.regions(), repo.index(), { repo.park(it) }, { repo.parkOnline(it) })

    @Test
    fun foundInTheBuildOnline() = runBlocking {
        // The app opens the park from its offline copy, which has no keys.
        assertEquals(null, repo.park("200")!!.day_hikes[0].route.route_key)
        val r = search()
        assertEquals(KeyedHike("200", "Juneau Trails", "200/day/0", "Nugget Falls"), r.found[nugget])
        assertTrue(r.unsure.isEmpty())
    }

    @Test
    fun withNoNetworkItCouldntBeLookedUp() = runBlocking {
        // The index and regions are kept on the phone from an earlier visit.
        repo.index()
        repo.regions()
        down = true
        val r = search()
        assertTrue(r.found.isEmpty())
        assertEquals(setOf(nugget), r.unsure)
    }
}
