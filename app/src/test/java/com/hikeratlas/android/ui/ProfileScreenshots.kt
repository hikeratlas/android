package com.hikeratlas.android.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.hikeratlas.android.atproto.FakeAtproto
import com.hikeratlas.android.atproto.Identity
import com.hikeratlas.android.atproto.Posts
import com.hikeratlas.android.atproto.Profiles
import com.hikeratlas.android.atproto.Xrpc
import com.hikeratlas.android.map.Thumbs
import com.hikeratlas.android.routes.KeyedHike
import com.hikeratlas.android.routes.KeyedSearch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Renders a profile with a review, its track and a photo, read from the
 * fake PDS, to build/screenshots/profile.png, to look at. Opt in with
 * SCREENSHOTS=1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h1000dp-xxhdpi", application = android.app.Application::class)
class ProfileScreenshots {
    @get:Rule val compose = createComposeRule()
    private val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
    private var fake: FakeAtproto? = null

    @After
    fun tearDown() {
        Thumbs.client = null
        fake?.close()
    }

    private fun photo(c1: Int, c2: Int): ByteArray {
        val b = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        Canvas(b).drawRect(0f, 0f, 300f, 300f, Paint().apply { shader = LinearGradient(0f, 0f, 300f, 300f, c1, c2, Shader.TileMode.CLAMP) })
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    @Test
    fun profile() {
        assumeTrue(System.getenv("SCREENSHOTS") == "1")
        val f = FakeAtproto().start().also { fake = it }
        Thumbs.client = http
        val gpx = buildString {
            append("<gpx><trk><trkseg>")
            for (i in 0..60) {
                val a = i / 60.0 * Math.PI * 1.6
                append("<trkpt lat=\"${47.49 + 0.01 * Math.sin(a) + i * 0.0002}\" lon=\"${-121.72 + 0.012 * Math.cos(a)}\"><ele>${200 + i * 15}</ele><time>2026-07-01T${10 + i / 30}:${"%02d".format(i * 2 % 60)}:00Z</time></trkpt>")
            }
            append("</trkseg></trk></gpx>")
        }
        f.blobsByCid["bafk-gpx"] = Posts.gzip(gpx)
        f.blobsByCid["bafk-p1"] = photo(0xFF2F855A.toInt(), 0xFF90CDF4.toInt())
        f.blobsByCid["bafk-p2"] = photo(0xFFDD6B20.toInt(), 0xFF744210.toInt())
        fun put(k: String, v: String) { f.records[k] = Json.parseToJsonElement(v).jsonObject }
        val key = "1E57E06gbDEBBBICDGF.F55FRcODEp_"
        put(
            "${Posts.TRACK}/t1",
            """{"gpx":{"${'$'}type":"blob","ref":{"${'$'}link":"bafk-gpx"},"mimeType":"application/gzip","size":1},"createdAt":"2026-07-01T18:00:00.000Z",
            "startedAt":"2026-07-01T10:00:00.000Z","duration":7500,"distance":7300,"gain":612,"routeKey":"$key","title":"Mount Si","license":"CC0-1.0"}""",
        )
        put(
            "${Posts.REVIEW}/r1",
            """{"text":"Steep and steady the whole way, but the views from the haystack are worth every step. Snow above 1,100 m.",
            "createdAt":"2026-07-01T18:01:00.000Z","hikedAt":"2026-07-01T12:00:00.000Z","routeKey":"$key","tags":["views","snow","crowded"],
            "track":{"uri":"at://${f.did}/${Posts.TRACK}/t1","cid":"x"},"license":"CC0-1.0",
            "photos":[{"image":{"ref":{"${'$'}link":"bafk-p1"},"mimeType":"image/jpeg"},"alt":"Haystack"},{"image":{"ref":{"${'$'}link":"bafk-p2"},"mimeType":"image/jpeg"}}]}""",
        )
        put(
            "${Posts.TRACK}/t2",
            """{"gpx":{"${'$'}type":"blob","ref":{"${'$'}link":"bafk-gpx"},"mimeType":"application/gzip","size":1},"createdAt":"2026-06-10T18:00:00.000Z",
            "startedAt":"2026-06-10T10:00:00.000Z","duration":3600,"distance":4100,"gain":210,"title":"Evening loop","license":"hikeratlas-only"}""",
        )
        put("com.hikeratlas.temp.fave/$key", """{"createdAt":"2026-06-01T00:00:00.000Z"}""")
        val actions = ProfileActions(
            load = { t -> Profiles.loadDid(http, Identity(http, f.config()), t.did, t.handle) },
            findHikes = { KeyedSearch(mapOf(key to KeyedHike("p", "Mount Si NRCA", "p/day/0", "Mount Si")), emptySet()) },
            canFind = true,
            gpx = { p, t -> Profiles.gpx(Xrpc.public(http, p.pds), p.did, t.gpx) },
            delete = {},
            openHike = {}, showTrack = {}, shareGpx = { _, _ -> }, openPhotos = {},
        )
        compose.setContent {
            HikerAtlasTheme {
                ProfileScreen(ProfileTarget(f.did, f.handle), actions, onBack = {}, settings = {})
            }
        }
        compose.waitUntil(10_000) { Thumbs.cached(Profiles.blobUrl(f.pdsOrigin, f.did, "bafk-p2"), Thumbs.GRID_PX) != null }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = File("build/screenshots/profile.png").apply { parentFile!!.mkdirs() }
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
