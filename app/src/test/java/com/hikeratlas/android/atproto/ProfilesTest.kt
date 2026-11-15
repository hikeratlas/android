package com.hikeratlas.android.atproto

import androidx.test.core.app.ApplicationProvider
import com.hikeratlas.android.track.Fix
import com.hikeratlas.android.track.TrackLibrary
import com.hikeratlas.android.track.TrackStore
import com.hikeratlas.android.ui.PostDraft
import com.hikeratlas.android.ui.PostHike
import com.hikeratlas.android.ui.deletePost
import com.hikeratlas.android.ui.postDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.LocalDate

/** Profiles read from a PDS: records listed, parsed (malformed ones skipped), joined, and your own posts deleted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ProfilesTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
    private lateinit var fake: FakeAtproto

    @Before
    fun setUp() {
        fake = FakeAtproto().start()
    }

    @After
    fun tearDown() {
        fake.close()
    }

    private fun put(collection: String, rkey: String, json: String) {
        fake.records["$collection/$rkey"] = Json.parseToJsonElement(json).jsonObject
    }

    private fun track(rkey: String, extra: String = "", gpx: String = """{"${'$'}type":"blob","ref":{"${'$'}link":"bafk-$rkey"},"mimeType":"application/gzip","size":10}""") =
        put(Posts.TRACK, rkey, """{"${'$'}type":"${Posts.TRACK}","gpx":$gpx,"createdAt":"2026-07-01T10:00:00.000Z","distance":7300,"license":"CC0-1.0"$extra}""")

    private fun review(rkey: String, extra: String = "") =
        put(Posts.REVIEW, rkey, """{"${'$'}type":"${Posts.REVIEW}","text":"Review $rkey","createdAt":"2026-07-01T12:00:00.000Z"$extra}""")

    private fun uri(collection: String, rkey: String) = "at://${fake.did}/$collection/$rkey"
    private fun ref(collection: String, rkey: String) = """{"uri":"${uri(collection, rkey)}","cid":"bafy"}"""

    private fun load() = runBlocking { Profiles.loadDid(http, Identity(http, fake.config()), fake.did) }

    @Test
    fun reviewsJoinTheirTracksAndTracksNoReviewUsesStandAlone() {
        track("t1", ""","startedAt":"2026-06-01T08:00:00.000Z"""")
        track("t2", ""","startedAt":"2026-06-20T08:00:00.000Z","title":"Evening loop"""")
        review("r1", ""","track":${ref(Posts.TRACK, "t1")},"hikedAt":"2026-06-01T12:00:00.000Z","tags":["views","mud",3],"routeKey":"k1"""")
        // Its track is gone: the review alone.
        review("r2", ""","track":${ref(Posts.TRACK, "gone")},"hikedAt":"2026-05-01T12:00:00.000Z"""")
        // Its track is someone else's: not joined, even with the same rkey as one of ours.
        review("r3", ""","track":{"uri":"at://did:plc:zzzzzzzzzzzzzzzzzzzzzzzz/${Posts.TRACK}/t2","cid":"x"}""")
        val p = load()
        assertEquals(fake.handle, p.handle)
        assertEquals(fake.pdsOrigin, p.pds)
        // Newest hike first: r3 (posted 1 Jul), t2 (started 20 Jun), r1 (hiked 1 Jun), r2 (1 May).
        assertEquals(
            listOf(uri(Posts.REVIEW, "r3"), uri(Posts.TRACK, "t2"), uri(Posts.REVIEW, "r1"), uri(Posts.REVIEW, "r2")),
            p.posts.map { it.uri },
        )
        val r1 = p.posts[2]
        assertEquals(uri(Posts.TRACK, "t1"), r1.track?.uri)
        assertEquals(listOf("views", "mud"), r1.review?.tags)
        assertEquals("k1", r1.routeKey)
        assertEquals(7300.0, r1.track?.distance)
        assertNull(p.posts[0].track)
        assertNull(p.posts[3].track)
        assertEquals("Evening loop", p.posts[1].track?.title)
        assertNull(p.posts[1].review)
    }

    @Test
    fun malformedRecordsAreSkippedNotFatal() {
        track("good")
        track("nogpx", gpx = "null")
        track("nolink", gpx = """{"${'$'}type":"blob","ref":{},"mimeType":"application/gzip"}""")
        put(Posts.TRACK, "nocreated", """{"gpx":{"ref":{"${'$'}link":"x"}}}""")
        // Numbers that aren't: left out, the track kept.
        track("oddnumbers", ""","duration":"long","gain":true""")
        review("good", ""","photos":[{"image":{"ref":{"${'$'}link":"bafk-photo"},"mimeType":"image/jpeg"},"alt":"Falls"},{"image":{}},"x"]""")
        put(Posts.REVIEW, "notext", """{"createdAt":"2026-07-01T12:00:00.000Z"}""")
        put(Posts.REVIEW, "numbertext", """{"text":5,"createdAt":"2026-07-01T12:00:00.000Z"}""")
        put(Posts.REVIEW, "nocreated", """{"text":"hi"}""")
        // A review whose track isn't a strongRef, and whose hikedAt isn't a string: still a review.
        review("oddtrack", ""","track":"t","hikedAt":7""")
        put(Faves.NSID, "1E57E06gbDEBBBICDGF.F55FRcODEp_", """{"createdAt":"2026-07-03T00:00:00.000Z"}""")
        put(Faves.NSID, "spike-key", """{"createdAt":"2026-07-03T00:00:00.000Z"}""")
        val p = load()
        assertEquals(
            setOf(uri(Posts.TRACK, "good"), uri(Posts.TRACK, "oddnumbers"), uri(Posts.REVIEW, "good"), uri(Posts.REVIEW, "oddtrack")),
            p.posts.map { it.uri }.toSet(),
        )
        val odd = p.posts.single { it.uri == uri(Posts.TRACK, "oddnumbers") }.track!!
        assertNull(odd.duration)
        assertNull(odd.gain)
        val photos = p.posts.single { it.uri == uri(Posts.REVIEW, "good") }.photos
        assertEquals(listOf(Profiles.Photo(Profiles.Blob("bafk-photo", "image/jpeg"), "Falls")), photos)
        // Only route keys' faves, as the website.
        assertEquals(listOf("1E57E06gbDEBBBICDGF.F55FRcODEp_"), p.faves.map { it.key })
    }

    @Test
    fun aPdsThatsDownIsAnErrorNotAnEmptyProfile() {
        track("t1")
        fake.listFails = 502
        try {
            load()
            fail("loaded")
        } catch (_: IOException) {
        }
        fake.listFails = null
        assertEquals(1, load().posts.size)
    }

    @Test
    fun longListsArePagedAndCappedAsTheWebsite() {
        for (i in 0 until 250) track("t%03d".format(i))
        val p = load()
        assertEquals(Profiles.MAX, p.posts.size)
        val pages = fake.pdsRequests.count { it.url.encodedPath.endsWith("listRecords") && it.url.queryParameter("collection") == Posts.TRACK }
        assertEquals(2, pages)
    }

    @Test
    fun aTracksGpxIsReadFromItsBlob() = runBlocking {
        val gpx = "<gpx><trk><trkseg><trkpt lat=\"47.5\" lon=\"-121.7\"/></trkseg></trk></gpx>"
        fake.blobsByCid["bafk-g"] = Posts.gzip(gpx)
        fake.blobsByCid["bafk-plain"] = gpx.toByteArray()
        val xrpc = Xrpc.public(http, fake.pdsOrigin)
        assertEquals(gpx, Profiles.gpx(xrpc, fake.did, Profiles.Blob("bafk-g", "application/gzip")))
        // Gzipped without saying so, or plain.
        assertEquals(gpx, Profiles.gpx(xrpc, fake.did, Profiles.Blob("bafk-g", null)))
        assertEquals(gpx, Profiles.gpx(xrpc, fake.did, Profiles.Blob("bafk-plain", "application/gpx+xml")))
        assertEquals(
            "${fake.pdsOrigin}/xrpc/com.atproto.sync.getBlob?did=did%3Aplc%3Aabcdefghijklmnopqrstuvwx&cid=bafk-g",
            Profiles.blobUrl(fake.pdsOrigin, fake.did, "bafk-g"),
        )
    }

    @Test
    fun deletingAReviewAndItsTrackClearsThePhonesMarkSoTheTrackCanBePostedAgain() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = "profile-delete.db"
        context.getDatabasePath(db).delete()
        val store = TrackStore(context, db)
        try {
            val account = Account(http, MemorySecretStore(), MemoryDpopKeys(), fake.config(), scope)
            runBlocking {
                account.beginSignIn(fake.authOrigin)
                account.finishSignIn(fake.callback(fake.pars.last()["state"]!!))
                val lib = TrackLibrary(store, null, regions = { null }, io = Dispatchers.Unconfined)
                val id = store.start(1_783_000_000_000)
                for (i in 0 until 20) store.add(id, 0, Fix(1_783_000_000_000 + i * 5_000L, 47.45 + i * 0.0002, -121.8, 4f, mslAltitude = 200.0 + i))
                store.end(id, 1_783_000_100_000)
                val other = store.start(1_783_100_000_000).also { store.end(it, 1_783_100_100_000) }
                store.setPosted(other, TrackStore.Posted("at://${fake.did}/${Posts.TRACK}/elsewhere", "c", null, 1))
                postDraft(account.xrpc, fake.did, lib, PostDraft(PostHike("k", "Mount Si"), "Mount Si", "Lovely.", emptyList(), LocalDate.of(2026, 7, 2), id), {})
                assertNotNull(lib.posted(id))

                val post = Profiles.loadDid(http, Identity(http, fake.config()), fake.did).posts.single()
                assertNotNull("joined", post.track)
                deletePost(account.xrpc, fake.did, lib, post)
                assertTrue("both records gone", fake.records.keys.none { it.startsWith(Posts.TRACK) || it.startsWith(Posts.REVIEW) })
                assertNull("can be posted again", lib.posted(id))
                assertNotNull("another track's mark is kept", lib.posted(other))
                assertTrue(Profiles.loadDid(http, Identity(http, fake.config()), fake.did).posts.isEmpty())
            }
        } finally {
            scope.cancel()
            store.close()
        }
    }

    @Test
    fun deletingOnlyAReviewKeepsItsTrackPosted() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = "profile-delete-review.db"
        context.getDatabasePath(db).delete()
        val store = TrackStore(context, db)
        try {
            val lib = TrackLibrary(store, null, regions = { null }, io = Dispatchers.Unconfined)
            val id = store.start(1).also { store.end(it, 2) }
            val trackUri = uri(Posts.TRACK, "t1")
            val reviewUri = uri(Posts.REVIEW, "r1")
            store.setPosted(id, TrackStore.Posted(trackUri, "c", reviewUri, 1))
            runBlocking { lib.postDeleted(null, reviewUri) }
            assertEquals(TrackStore.Posted(trackUri, "c", null, 1), store.track(id)!!.posted)
            runBlocking { lib.postDeleted(trackUri, null) }
            assertNull(store.track(id)!!.posted)
        } finally {
            scope.cancel()
            store.close()
        }
    }

    @Test
    fun deletingSomeoneElsesPostIsRefusedBeforeAsking() = runBlocking {
        val post = Profiles.Post("x", Profiles.Review("at://did:plc:zzzzzzzzzzzzzzzzzzzzzzzz/${Posts.REVIEW}/r", "c", "t", "2026"), null)
        try {
            Profiles.delete(Xrpc.public(http, fake.pdsOrigin), fake.did, post)
            fail("deleted")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(fake.pdsRequests.none { it.url.encodedPath.endsWith("deleteRecord") })
    }
}
