package com.hikeratlas.android.map

import com.hikeratlas.android.atproto.Indexer
import com.hikeratlas.android.atproto.Profiles
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Pictures decoded no bigger than they're shown (grid thumbnails small, the
 * viewer's at most 2048 px), and posts' photos through the indexer's
 * getPhoto with the PDS as the fallback: both MockWebServers here, never the
 * real indexer. Under Robolectric, for Android's decoders (WebP included).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ThumbsTest {
    private val did = "did:plc:abcdefghijklmnopqrstuvwx"
    private val indexer = MockWebServer()
    private val pds = MockWebServer()
    private val asked = CopyOnWriteArrayList<String>()
    private fun bytes(name: String) = javaClass.getResource("/photos/$name")!!.readBytes()
    private fun image(b: ByteArray) = MockResponse.Builder().code(200).body(okio.Buffer().write(b)).build()

    @Before
    fun setUp() {
        // The indexer has "indexed" (thumbnail: a WebP; fullsize: the JPEG); anything else is NotFound, as getPhoto says.
        indexer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                asked += "indexer ${request.url.queryParameter("cid")} ${request.url.queryParameter("size") ?: "thumbnail"}"
                return when {
                    request.url.encodedPath != "/xrpc/com.hikeratlas.temp.getPhoto" -> MockResponse.Builder().code(500).build()
                    request.url.queryParameter("cid") != "indexed" ->
                        MockResponse.Builder().code(404).addHeader("Content-Type", "application/json")
                            .body("""{"error":"NotFound","message":"No such photo"}""").build()
                    request.url.queryParameter("size") == "fullsize" -> image(bytes("gps-time-be.jpg"))
                    else -> image(bytes("thumbnail.webp"))
                }
            }
        }
        pds.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                asked += "pds ${request.url.queryParameter("cid")}"
                return when (request.url.queryParameter("cid")) {
                    "turned" -> image(bytes("orientation-8.jpg"))
                    "new" -> image(bytes("no-exif.jpg"))
                    else -> MockResponse.Builder().code(400).body("""{"error":"BlobNotFound"}""").build()
                }
            }
        }
        indexer.start()
        pds.start()
        Indexer.photoBase = indexer.url("/").toString()
        Thumbs.client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
    }

    @After
    fun tearDown() {
        Thumbs.client = null
        Indexer.photoBase = Indexer.BASE
        indexer.close()
        pds.close()
    }

    private val pdsOrigin get() = pds.url("/").toString().trimEnd('/')

    @Test
    fun sampleSizeKeepsTheLongerSideWithinTheLimit() {
        assertEquals(1, Thumbs.sampleSize(2048, 1536, Thumbs.FULL_PX))
        assertEquals(2, Thumbs.sampleSize(4000, 3000, Thumbs.FULL_PX))
        assertEquals(4, Thumbs.sampleSize(2048, 1536, Thumbs.GRID_PX))
        assertEquals(1, Thumbs.sampleSize(300, 300, Thumbs.GRID_PX))
        assertEquals(2, Thumbs.sampleSize(900, 1280, Thumbs.GRID_PX))
    }

    @Test
    fun getPhotoUrlsAreTheWebsitesThumbnailUnlessAskedForFullSize() {
        Indexer.photoBase = Indexer.BASE
        assertEquals("https://indexer.hikeratlas.com/xrpc/com.hikeratlas.temp.getPhoto?did=did%3Aplc%3Ax&cid=bafk", Indexer.photoUrl("did:plc:x", "bafk"))
        assertEquals(
            "https://indexer.hikeratlas.com/xrpc/com.hikeratlas.temp.getPhoto?did=did%3Aplc%3Ax&cid=bafk&size=fullsize",
            Indexer.photoUrl("did:plc:x", "bafk", fullsize = true),
        )
    }

    @Test
    fun aThumbnailComesFromTheIndexerAsAWebp() = runBlocking {
        val url = Indexer.photoUrl(did, "indexed")
        val img = Thumbs.load(url, Thumbs.GRID_PX, fallback = Profiles.blobUrl(pdsOrigin, did, "indexed"))
        assertNotNull(img)
        assertEquals(40, img!!.width)
        assertEquals(30, img.height)
        assertEquals(listOf("indexer indexed thumbnail"), asked.toList())
        assertSame(img, Thumbs.cached(url, Thumbs.GRID_PX))
    }

    @Test
    fun aPhotoTheIndexerHasntGotComesFromThePds() = runBlocking {
        // Just posted: the indexer hasn't caught up (404 NotFound).
        val url = Indexer.photoUrl(did, "new")
        val img = Thumbs.load(url, Thumbs.GRID_PX, fallback = Profiles.blobUrl(pdsOrigin, did, "new"))
        assertEquals(32, img!!.width)
        assertEquals(listOf("indexer new thumbnail", "pds new"), asked.toList())
        // Kept under the indexer's URL: not asked again this session.
        assertSame(img, Thumbs.cached(url, Thumbs.GRID_PX))
    }

    @Test
    fun theViewersPhotoIsTheIndexersFullSizeElseThePds() = runBlocking {
        val full = Thumbs.loadPhoto(Indexer.photoUrl(did, "indexed", fullsize = true), Profiles.blobUrl(pdsOrigin, did, "indexed"))
        assertEquals(32, full!!.width)
        assertEquals(listOf("indexer indexed fullsize"), asked.toList())
        asked.clear()
        assertNotNull(Thumbs.loadPhoto(Indexer.photoUrl(did, "new", fullsize = true), Profiles.blobUrl(pdsOrigin, did, "new")))
        assertEquals(listOf("indexer new fullsize", "pds new"), asked.toList())
        // Neither has it: nothing.
        assertNull(Thumbs.loadPhoto(Indexer.photoUrl(did, "gone", fullsize = true), Profiles.blobUrl(pdsOrigin, did, "gone")))
    }

    @Test
    fun aJpegIsDrawnUprightByItsOrientationAsItsAspectRatioSays() = runBlocking {
        // 32 × 24 stored, Orientation 8: 24 × 32 drawn, as the record's aspectRatio has it.
        val img = Thumbs.load(Indexer.photoUrl(did, "turned"), Thumbs.GRID_PX, fallback = Profiles.blobUrl(pdsOrigin, did, "turned"))
        assertEquals(24, img!!.width)
        assertEquals(32, img.height)
    }
}
