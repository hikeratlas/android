package com.hikeratlas.android.routes

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class ReportsTest {
    private val server = MockWebServer()

    @After
    fun tearDown() = server.close()

    private fun gz(s: String) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(s.toByteArray()) } }.toByteArray()

    private fun repo(index: String, bundle: ByteArray): RoutesRepository {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/index.json" -> MockResponse.Builder().body(index).build()
                "/reports.bin" -> {
                    // As R2: the range asked for.
                    val (a, b) = request.headers["Range"]!!.removePrefix("bytes=").split('-').map { it.toInt() }
                    MockResponse.Builder().code(206).body(Buffer().write(bundle.copyOfRange(a, b + 1))).build()
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
        return RoutesRepository(OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build(), server.url("/").toString().trimEnd('/'))
    }

    @Test
    fun aParksReportsByRouteKeyWithThumbnailsAsUrls() = runBlocking {
        val other = gz("""{"x":{"reports":[]}}""")
        val mine = gz(
            """{"7|day|a|lake|loop":{"reports":[{"url":"https://blog.example/lake","title":"Lake day","site":"www.blog.example","type":"trip_report","date":"2018-08","thumb":"thumbs/ab.webp"}],
               "about":[{"url":"https://guide.example/lake","title":"","site":"guide.example","thumb":"https://cdn.example/t.jpg"}]}}""",
        )
        val bundle = other + mine
        val r = repo("""{"bundle":"b.bin","parks":{},"reports":{"bundle":"reports.bin","parks":{"7":[${other.size},${mine.size}]}}}""", bundle)
        val got = r.parkReports("7").getValue("7|day|a|lake|loop")
        val page = got.reports.single()
        assertEquals("Lake day", page.title)
        assertEquals(server.url("/thumbs/ab.webp").toString(), page.thumb)
        // Already a URL: kept.
        assertEquals("https://cdn.example/t.jpg", got.about.single().thumb)
        // A park with none, and kept once read.
        assertEquals(emptyMap<String, RouteReports>(), r.parkReports("8"))
        val reads = server.requestCount
        r.parkReports("7")
        assertEquals(reads, server.requestCount)
    }

    @Test
    fun aBuildWithoutReportsHasNone() = runBlocking {
        val r = repo("""{"bundle":"b.bin","parks":{}}""", ByteArray(0))
        assertTrue(r.parkReports("7").isEmpty())
    }

    @Test
    fun routesAreFiledByKeyElseId() {
        val doc = Json { ignoreUnknownKeys = true }.decodeFromString(
            ParkDoc.serializer(),
            """{"park":{"id":"7","name":"P"},
               "day_hikes":[{"from":"A","dest":"Lake","kind":"loop","key":"k-day","route":{"km":1,"gain":1}},{"from":"A","dest":"Peak","kind":"loop","route":{"km":1,"gain":1}}],
               "base_camps":[{"camp":"Camp","from":"A","key":"k-camp","approach":{"km":1,"gain":1},"hikes":[{"from":"Camp","dest":"Col","kind":"loop","key":"k-col","route":{"km":1,"gain":1}}]}],
               "point_to_points":[{"from":"A","to":"B","kind":"point-to-point","key":"k-trip","route":{"km":1,"gain":1}}]}""",
        )
        assertEquals("k-day" to "Lake", reportsKey(doc, "7/day/0"))
        assertEquals("7/day/1" to "Peak", reportsKey(doc, "7/day/1"))
        assertEquals("k-camp" to "Camp", reportsKey(doc, "7/base/0"))
        assertEquals("k-col" to "Col", reportsKey(doc, "7/base/0/0"))
        assertEquals("k-trip" to null, reportsKey(doc, "7/multi/0"))
        assertEquals("7/day/9" to null, reportsKey(doc, "7/day/9"))
    }
}
