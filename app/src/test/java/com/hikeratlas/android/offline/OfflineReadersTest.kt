package com.hikeratlas.android.offline

import com.hikeratlas.android.elevation.MapterhornSource
import com.hikeratlas.android.pmtiles.Compression
import com.hikeratlas.android.pmtiles.MemoryOffline
import com.hikeratlas.android.pmtiles.Offline
import com.hikeratlas.android.pmtiles.StoredTile
import com.hikeratlas.android.routes.RoutesRepository
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

/** Readers check what's kept on the phone first, and fall back to it offline. */
class OfflineReadersTest {
    private val kept = MemoryOffline()
    private val server = MockWebServer()

    /** A client that's offline: every request fails as a dropped connection would. */
    private val offline = OkHttpClient.Builder().addInterceptor(Interceptor { throw IOException("offline") }).build()
    private val direct = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()

    @Before
    fun setUp() {
        Offline.tiles = kept
        Offline.resources = kept
    }

    @After
    fun tearDown() {
        Offline.tiles = null
        Offline.resources = null
        server.close()
    }

    @Test
    fun keptTerrainIsServedWithoutAsking() {
        val webp = byteArrayOf(1, 2, 3)
        kept.put(MapterhornSource.KEY, 13, 1300, 2800, StoredTile(Compression.NONE, webp))
        val src = MapterhornSource(offline)
        assertArrayEquals(webp, src.raw(13, 1300, 2800))
        // Not kept, and offline: the failure shows, so it's retried later.
        try {
            src.raw(13, 1301, 2800)
            throw AssertionError("expected a network error")
        } catch (e: IOException) {
            assertEquals("offline", e.message)
        }
    }

    private fun gzip(s: String) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(s.toByteArray()) } }.toByteArray()

    @Test
    fun routesIndexAndRegionsAreKeptForOffline() = runBlocking {
        val index = """{"bundle":"b.bin","parks":{"1":[0,10]}}"""
        val regions = """{"countries":[{"name":"Canada","slug":"canada","states":[]}]}"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/index.json" -> MockResponse.Builder().body(index).build()
                "/regions.json" -> MockResponse.Builder().body(regions).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
        val root = server.url("/").toString().trimEnd('/')
        RoutesRepository(direct, root).apply { index(); regions() }
        assertEquals(index, kept.resources["$root/index.json"]!!.decodeToString())

        // Later, offline: the kept copies.
        val again = RoutesRepository(offline, root)
        assertEquals("b.bin", again.index().bundle)
        assertEquals("canada", again.regions().countries.single().slug)
    }

    @Test
    fun aDownloadedParksRoutesAreReadFromThePhone() = runBlocking {
        val root = "https://public.hikeratlas.com/routes/north-america"
        val repo = RoutesRepository(offline, root)
        kept.putResource(repo.parkKey("2226817"), gzip("""{"park":{"id":"2226817","name":"Garibaldi Provincial Park"}}"""))
        // No index needed, and no network.
        assertEquals("Garibaldi Provincial Park", repo.park("2226817")!!.park!!.name)
        // A park not downloaded, offline: no index either, so it fails.
        try {
            repo.park("99")
            throw AssertionError("expected a network error")
        } catch (e: IOException) {
            assertEquals("offline", e.message)
        }
    }

    @Test
    fun fontsAndIconsAreKeptForGood() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/fonts/Hubot%20Sans-Regular/0-255.pbf" -> MockResponse.Builder().body("glyphs").build()
                else -> MockResponse.Builder().code(404).body("nope").build()
            }
        }
        server.start()
        val fonts = server.url("/fonts/").toString()
        val client = direct.newBuilder().addInterceptor(ResourceCache(listOf(fonts))).build()
        fun get(c: OkHttpClient, path: String) = c.newCall(Request.Builder().url(server.url(path)).build()).execute().use { it.code to it.body.string() }

        assertEquals(200 to "glyphs", get(client, "/fonts/Hubot%20Sans-Regular/0-255.pbf"))
        assertEquals(1, server.requestCount)
        // Kept: answered from the phone, without asking again.
        assertEquals(200 to "glyphs", get(client, "/fonts/Hubot%20Sans-Regular/0-255.pbf"))
        assertEquals(1, server.requestCount)
        // A failure isn't kept.
        assertEquals(404, get(client, "/fonts/Missing/0-255.pbf").first)
        assertNull(kept.resources[server.url("/fonts/Missing/0-255.pbf").toString()])
        // Outside the prefixes: passed through, not kept.
        get(client, "/style.json")
        assertNull(kept.resources[server.url("/style.json").toString()])
    }

    /** What MapLibre asks for: a kept tile is drawn though the archive can't be reached. */
    @Test
    fun theMapIsServedKeptTilesOffline() {
        val source = com.hikeratlas.android.pmtiles.OfflineSource("https://public.hikeratlas.com/test-offline.pmtiles")
        kept.put(source.key, 14, 2600, 5600, StoredTile(Compression.GZIP, gzip("mvt bytes")))
        val (_, template) = com.hikeratlas.android.pmtiles.TileServer.register(source)
        val client = OkHttpClient.Builder().addInterceptor(com.hikeratlas.android.pmtiles.TileServer).build()
        val url = template.replace("{z}", "14").replace("{x}", "2600").replace("{y}", "5600")
        client.newCall(Request.Builder().url(url).build()).execute().use {
            assertEquals(200, it.code)
            assertEquals("mvt bytes", it.body.string())
        }
    }
}
