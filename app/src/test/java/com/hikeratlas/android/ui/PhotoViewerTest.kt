package com.hikeratlas.android.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import com.hikeratlas.android.atproto.Indexer
import com.hikeratlas.android.atproto.Photos
import com.hikeratlas.android.atproto.Profiles
import com.hikeratlas.android.map.Thumbs
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * The photo viewer: which photo of how many, its caption and licence, Show
 * on map, Download, and closing; the photo through the indexer (full size)
 * else its PDS, in a placeholder of its aspectRatio while it loads; and
 * posts' grids of thumbnails through the indexer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi", application = android.app.Application::class)
class PhotoViewerTest {
    @get:Rule val compose = createComposeRule()

    private val did = "did:plc:abcdefghijklmnopqrstuvwx"
    private fun photo(cid: String, alt: String? = null, lat: Double? = null, lon: Double? = null) =
        Profiles.Photo(Profiles.Blob(cid, "image/jpeg"), alt, lat = lat, lon = lon)

    private val three = listOf(
        photo("bafk-falls", "The falls"),
        photo("bafk-bare"),
        photo("bafk-lake", "The lake", lat = 47.512345, lon = -121.734567),
    )

    private fun set(photos: List<Profiles.Photo> = three, start: Int = 0, license: String? = "CC-BY-4.0", handle: String? = "alice.test") =
        PhotoSet("https://pds.example", did, handle, license, photos, start)

    private var open by mutableStateOf(true)
    private lateinit var back: OnBackPressedDispatcher

    private fun show(
        s: PhotoSet = set(),
        pins: MutableList<PhotoPin> = mutableListOf(),
        download: suspend (String, Profiles.Photo) -> String? = { _, _ -> null },
    ) {
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            HikerAtlasTheme {
                if (open) PhotoViewer(s, PhotoViewerActions(download, showOnMap = { pins += it }), onClose = { open = false })
            }
        }
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    // Set by the tests that load photos: a MockWebServer each for the indexer and a PDS.
    private var servers = emptyList<MockWebServer>()

    // Released at the end: until then the servers made by stall() don't answer.
    private val release = java.util.concurrent.CountDownLatch(1)

    @After
    fun tearDown() {
        release.countDown()
        Thumbs.client = null
        Indexer.photoBase = Indexer.BASE
        servers.forEach { it.close() }
    }

    /** What the indexer and the PDS were asked for; the indexer has only "indexed", the PDS every photo. */
    private fun serve(): Pair<String, MutableList<String>> {
        val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
        val jpeg = javaClass.getResource("/photos/no-exif.jpg")!!.readBytes()
        val webp = javaClass.getResource("/photos/thumbnail.webp")!!.readBytes()
        fun image(b: ByteArray) = MockResponse.Builder().code(200).body(okio.Buffer().write(b)).build()
        val indexer = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val cid = request.url.queryParameter("cid")
                    val size = request.url.queryParameter("size") ?: "thumbnail"
                    asked += "indexer $cid $size"
                    return if (cid != "indexed") MockResponse.Builder().code(404).body("""{"error":"NotFound"}""").build()
                    else image(if (size == "fullsize") jpeg else webp)
                }
            }
            start()
        }
        val pds = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    asked += "pds ${request.url.queryParameter("cid")}"
                    return image(jpeg)
                }
            }
            start()
        }
        servers = listOf(indexer, pds)
        Indexer.photoBase = indexer.url("/").toString()
        Thumbs.client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
        return pds.url("/").toString().trimEnd('/') to asked
    }

    private fun waitForAsked(asked: List<String>, what: String) = compose.waitUntil(5_000) { what in asked }

    @Test
    fun theViewerShowsTheIndexersFullSizePhotoElseThePds() {
        val (pdsOrigin, asked) = serve()
        // Each test its own CIDs: the caches outlive a test.
        val photos = listOf(photo("indexed", "Indexed"), photo("new-${System.nanoTime()}", "Just posted"))
        show(PhotoSet(pdsOrigin, did, "alice.test", null, photos))
        waitForAsked(asked, "indexer indexed fullsize")
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Indexed")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(asked.toString(), asked.none { it.startsWith("pds") })
        swipe()
        // Not indexed yet (404): from the PDS.
        val cid = photos[1].image.cid
        waitForAsked(asked, "indexer $cid fullsize")
        waitForAsked(asked, "pds $cid")
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Just posted")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aGridsThumbnailsComeThroughTheIndexerElseThePds() {
        val (pdsOrigin, asked) = serve()
        val cid = "new-${System.nanoTime()}"
        val s = PhotoSet(pdsOrigin, did, "alice.test", null, listOf(photo("indexed"), photo(cid)))
        compose.setContent { HikerAtlasTheme { PhotoGrid(s) {} } }
        waitForAsked(asked, "indexer indexed thumbnail")
        waitForAsked(asked, "pds $cid")
        assertTrue(asked.toString(), "indexer $cid thumbnail" in asked)
        assertTrue(asked.toString(), "pds indexed" !in asked)
        assertTrue(asked.toString(), asked.none { it.endsWith("fullsize") })
    }

    /** An indexer and a PDS that don't answer until the test ends: photos stay loading. */
    private fun stall(): String {
        val slow = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    release.await(30, java.util.concurrent.TimeUnit.SECONDS)
                    return MockResponse.Builder().code(404).build()
                }
            }
            start()
        }
        servers = listOf(slow)
        Indexer.photoBase = slow.url("/").toString()
        Thumbs.client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
        return slow.url("/").toString().trimEnd('/')
    }

    @Test
    fun whileItLoadsAPlaceholderHasThePhotosShape() {
        val pdsOrigin = stall()
        val tall = photo("bafk-tall").copy(aspectRatio = Photos.AspectRatio(24, 32))
        val wide = photo("bafk-wide").copy(aspectRatio = Photos.AspectRatio(4000, 3000))
        show(PhotoSet(pdsOrigin, did, "alice.test", null, listOf(tall, wide, photo("bafk-old"))))
        // Fitted to the 390 × 844 dp screen, as the photo will be.
        compose.onNodeWithTag("photo-placeholder").getBoundsInRoot().let {
            assertEquals(390f, (it.right - it.left).value, 0.5f)
            assertEquals(520f, (it.bottom - it.top).value, 0.5f)
        }
        swipe()
        compose.onNodeWithTag("photo-placeholder").getBoundsInRoot().let {
            assertEquals(390f, (it.right - it.left).value, 0.5f)
            assertEquals(292.5f, (it.bottom - it.top).value, 0.5f)
        }
        swipe()
        // Posted before aspectRatio: no shape to give, as before.
        assertEquals(0, compose.onAllNodesWithTag("photo-placeholder").fetchSemanticsNodes().size)
    }

    private fun swipe() {
        compose.onNodeWithTag("photo-pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
    }

    @Test
    fun swipingTurnsThePhotoAndItsCaption() {
        show()
        compose.onNodeWithText("1 / 3").assertIsDisplayed()
        compose.onNodeWithText("The falls").assertIsDisplayed()
        compose.onNodeWithText("CC BY 4.0 · @alice.test").assertIsDisplayed()
        swipe()
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
        // No caption on this one.
        assertEquals(0, count("The falls"))
        swipe()
        compose.onNodeWithText("3 / 3").assertIsDisplayed()
        compose.onNodeWithText("The lake").assertIsDisplayed()
    }

    @Test
    fun opensAtTheOneTapped() {
        show(set(start = 2))
        compose.onNodeWithText("3 / 3").assertIsDisplayed()
        compose.onNodeWithText("The lake").assertIsDisplayed()
    }

    @Test
    fun oneAloneHasNoCount() {
        show(set(listOf(photo("bafk-one"))))
        compose.onNodeWithTag("photo-viewer").assertIsDisplayed()
        assertEquals(0, count("1 / 1"))
    }

    @Test
    fun noLicenceIsHikerAtlasOnlyAndNoHandleIsTheDid() {
        show(set(license = null, handle = null))
        compose.onNodeWithText("Hiker Atlas only · did:plc:abcdefgh…").assertIsDisplayed()
    }

    @Test
    fun theLicenceIsThePosts() {
        show(set(license = "CC0-1.0"))
        compose.onNodeWithText("public domain (CC0) · @alice.test").assertIsDisplayed()
    }

    @Test
    fun showOnMapOnlyWithALocation() {
        val pins = mutableListOf<PhotoPin>()
        show(pins = pins)
        assertEquals(0, count("Show on map"))
        swipe()
        assertEquals(0, count("Show on map"))
        swipe()
        compose.onNodeWithText("Show on map").performClick()
        assertEquals(
            listOf(PhotoPin(47.512345, -121.734567, Profiles.blobUrl("https://pds.example", did, "bafk-lake"), "The lake")),
            pins.toList(),
        )
    }

    @Test
    fun downloadSavesTheFullSizePhotoAndSaysWhere() {
        val asked = mutableListOf<Pair<String, String>>()
        show(set(start = 1), download = { url, p -> asked += url to p.image.cid; "Saved to Pictures/Hiker Atlas" })
        compose.onNodeWithContentDescription("Download").performClick()
        compose.waitUntil(5_000) { count("Saved to Pictures/Hiker Atlas") > 0 }
        assertEquals(listOf(Profiles.blobUrl("https://pds.example", did, "bafk-bare") to "bafk-bare"), asked.toList())
        // About that photo only.
        swipe()
        assertEquals(0, count("Saved to Pictures/Hiker Atlas"))
    }

    @Test
    fun aFailedDownloadSaysSo() {
        show(download = { _, _ -> throw IOException("HTTP 404") })
        compose.onNodeWithContentDescription("Download").performClick()
        compose.waitUntil(5_000) { count("Couldn't save the photo: HTTP 404") > 0 }
    }

    @Test
    fun closeCloses() {
        show()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        assertEquals(false, open)
    }

    @Test
    fun backCloses() {
        show()
        compose.runOnUiThread { back.onBackPressed() }
        compose.waitForIdle()
        assertEquals(false, open)
        assertEquals(0, count("1 / 3"))
    }

    @Test
    fun theMapsPanelForAPlaceClosesBack() {
        var closed = false
        compose.setContent {
            HikerAtlasTheme { PhotoPlacePanel(PhotoPin(47.5, -121.7, "https://pds.example/x", "The lake"), onClose = { closed = true }) }
        }
        compose.onNodeWithText("Where this photo was taken").assertIsDisplayed()
        compose.onNodeWithText("The lake").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").performClick()
        assertEquals(true, closed)
    }
}
