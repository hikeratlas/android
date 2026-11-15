package com.hikeratlas.android.ui

import androidx.test.core.app.ApplicationProvider
import com.hikeratlas.android.atproto.Account
import com.hikeratlas.android.atproto.FakeAtproto
import com.hikeratlas.android.atproto.MemoryDpopKeys
import com.hikeratlas.android.atproto.MemorySecretStore
import com.hikeratlas.android.atproto.PostException
import com.hikeratlas.android.atproto.Posts
import com.hikeratlas.android.track.Fix
import com.hikeratlas.android.track.TrackLibrary
import com.hikeratlas.android.track.TrackStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** A recorded track is posted once: the phone remembers what it was posted as, until that post is deleted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PostFlowTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var fake: FakeAtproto
    private lateinit var account: Account
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = "posted.db"
    private lateinit var store: TrackStore

    @Before
    fun setUp() {
        context.getDatabasePath(db).delete()
        store = TrackStore(context, db)
        fake = FakeAtproto().start()
        account = Account(OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build(), MemorySecretStore(), MemoryDpopKeys(), fake.config(), scope)
        runBlocking {
            account.beginSignIn(fake.authOrigin)
            account.finishSignIn(fake.callback(fake.pars.last()["state"]!!))
        }
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        java.io.File(context.filesDir, "outbox").deleteRecursively()
    }

    private fun outbox(lib: TrackLibrary, now: () -> Long = System::currentTimeMillis) =
        com.hikeratlas.android.outbox.Outbox(context, account, lib, now = now)

    private suspend fun post(lib: TrackLibrary, d: PostDraft, progress: (String) -> Unit = {}, now: () -> Long = System::currentTimeMillis) =
        sendDraft(outbox(lib, now), fake.did, fake.handle, lib, d, progress)

    @After
    fun tearDown() {
        scope.cancel()
        fake.close()
        store.close()
    }

    private fun library() = TrackLibrary(store, null, regions = { null }, io = Dispatchers.Unconfined)

    private fun recorded(): Long {
        val id = store.start(1_783_000_000_000)
        for (i in 0 until 20) store.add(id, 0, Fix(1_783_000_000_000 + i * 5_000L, 47.45 + i * 0.0002, -121.8, 4f, mslAltitude = 200.0 + i))
        store.end(id, 1_783_000_100_000)
        return id
    }

    private fun draft(id: Long, text: String = "") = PostDraft(PostHike("keyA", "Mount Si"), "Mount Si", text, emptyList(), LocalDate.of(2026, 7, 2), id)

    @Test
    fun aPostedTrackStaysPostedAfterARestart() = runBlocking {
        val id = recorded()
        assertEquals(PostOutcome.Posted, post(library(), draft(id, "Lovely."), now = { 1_783_100_000_000 }))
        store.close()
        // The app starts again.
        store = TrackStore(context, db)
        val lib = library()
        lib.refresh()
        val posted = lib.tracks.single().posted!!
        // Under the keys the phone chose (TIDs), as the PDS has them.
        val track = fake.records.keys.single { it.startsWith(Posts.TRACK) }
        val review = fake.records.keys.single { it.startsWith(Posts.REVIEW) }
        assertEquals("at://${fake.did}/$track", posted.trackUri)
        assertEquals("bafy-$track", posted.trackCid)
        assertEquals("at://${fake.did}/$review", posted.reviewUri)
        assertEquals(1_783_100_000_000, posted.at)
    }

    @Test
    fun aReviewThatFailsAfterItsTrackWentUpPointsAtThatTrackWhenTriedAgain() = runBlocking {
        val id = recorded()
        val lib = library()
        // The PDS takes the track, then refuses the review.
        val failing = { s: String ->
            if (s == "Posting your review…") fake.createFails = 400 to """{"error":"InvalidRecord","message":"no"}"""
        }
        try {
            post(lib, draft(id, "Lovely."), failing)
            fail("posted")
        } catch (_: PostException) {
        }
        assertNotNull("marked as soon as the track went up", lib.posted(id))
        fake.createFails = null
        post(lib, draft(id, "Lovely."))
        val tracks = fake.records.keys.filter { it.startsWith(Posts.TRACK) }
        assertEquals("one track record", 1, tracks.size)
        val review = fake.records.entries.single { it.key.startsWith(Posts.REVIEW) }.value
        assertEquals("at://${fake.did}/${tracks.single()}", review["track"]!!.jsonObject["uri"]!!.jsonPrimitive.content)
        assertEquals(1, fake.blobs.size)
        assertEquals(review, fake.records["${Posts.REVIEW}/${lib.posted(id)!!.reviewUri!!.substringAfterLast('/')}"])
    }

    @Test
    fun aDeletedPostClearsTheMarkSoItCanBePostedAgain() = runBlocking {
        val id = recorded()
        val lib = library()
        post(lib, draft(id))
        // Still there: kept.
        verifyPosted(account.xrpc, fake.did, lib, id)
        assertNotNull(lib.posted(id))
        // The PDS can't be reached: kept (nothing's known).
        fake.getRecordFails = 502
        verifyPosted(account.xrpc, fake.did, lib, id)
        assertNotNull(lib.posted(id))
        fake.getRecordFails = null
        // Deleted, on the website say: cleared.
        fake.records.remove(fake.records.keys.single { it.startsWith(Posts.TRACK) })
        verifyPosted(account.xrpc, fake.did, lib, id)
        assertNull(lib.posted(id))
        assertNull(lib.tracks.single().posted)
    }

    @Test
    fun theTracksNearAHike() = runBlocking {
        val near = recorded()
        // 5 km east.
        val far = store.start(1_783_200_000_000).also { id ->
            for (i in 0 until 20) store.add(id, 0, Fix(1_783_200_000_000 + i * 5_000L, 47.45 + i * 0.0002, -121.733, 4f))
            store.end(id, 1_783_200_100_000)
        }
        // 600 m west of the first, a few hundred metres long.
        val line = (0..30).map { listOf(-121.808, 47.451 + it * 0.0001) }
        assertEquals(setOf(near), library().tracksNear(line))
        assertEquals(setOf(near, far), library().tracksNear(line, within = 7_000.0))
    }

    @Test
    fun deletingAPostedTrackSaysItsPostStaysUp() {
        assertEquals("It's deleted from this phone. Your post on Hiker Atlas stays up.", com.hikeratlas.android.track.deletedText(TrackStore.Posted("u", "c", null, 1)))
        assertEquals("It's deleted from this phone.", com.hikeratlas.android.track.deletedText(null))
    }

    private fun photoFile(name: String) = java.io.File(context.cacheDir, "post-$name").apply {
        writeBytes(this@PostFlowTest.javaClass.getResource("/photos/$name")!!.readBytes())
    }.path

    @Test
    fun aPhotoWithoutGpsIsPlacedOnTheTrackByItsTime() = runBlocking {
        val id = recorded()
        // 12.5 s in: between the third and fourth points (5 s apart).
        val bare = Posts.Photo(photoFile("no-exif.jpg"), takenAt = com.hikeratlas.android.track.TrackMeasure.iso(1_783_000_012_500))
        val own = Posts.Photo(photoFile("gps-offset.jpg"), takenAt = "2026-07-02T17:15:30.000Z", lat = 47.4875, lon = -121.7234)
        val far = Posts.Photo(photoFile("local.jpg"), takenAt = "2026-03-08T07:30:00.000Z")
        post(library(), draft(id).copy(photos = listOf(bare, own, far)))
        // A track alone: its photos are on it.
        val photos = fake.records.entries.single { it.key.startsWith(Posts.TRACK) }.value["photos"]!!.jsonArray.map { it.jsonObject }
        val at = photos[0]["at"]!!.jsonObject
        assertEquals(47_450_500.0, at["lat"]!!.jsonPrimitive.content.toDouble(), 1.0)
        assertEquals(-121_800_000L, at["lon"]!!.jsonPrimitive.content.toLong())
        // Its own location kept; one taken months before isn't placed.
        assertEquals(47_487_500L, photos[1]["at"]!!.jsonObject["lat"]!!.jsonPrimitive.content.toLong())
        assertNull(photos[2]["at"])
    }

    @Test
    fun photosAreStillPlacedWhenTheReviewIsTriedAgainAfterItsTrackWentUp() = runBlocking {
        val id = recorded()
        val lib = library()
        val bare = Posts.Photo(photoFile("no-exif.jpg"), takenAt = com.hikeratlas.android.track.TrackMeasure.iso(1_783_000_012_500))
        val failing = { s: String ->
            if (s == "Posting your review…") fake.createFails = 400 to """{"error":"InvalidRecord","message":"no"}"""
        }
        try {
            post(lib, draft(id, "Lovely.").copy(photos = listOf(bare)), failing)
            fail("posted")
        } catch (_: PostException) {
        }
        fake.createFails = null
        post(lib, draft(id, "Lovely.").copy(photos = listOf(bare)))
        val review = fake.records.entries.single { it.key.startsWith(Posts.REVIEW) }.value
        assertNotNull(review["photos"]!!.jsonArray.single().jsonObject["at"])
        assertNull(fake.records.entries.single { it.key.startsWith(Posts.TRACK) }.value["photos"])
    }
}
