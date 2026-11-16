package com.hikeratlas.android.outbox

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.hikeratlas.android.atproto.Account
import com.hikeratlas.android.atproto.FakeAtproto
import com.hikeratlas.android.atproto.MemoryDpopKeys
import com.hikeratlas.android.atproto.MemorySecretStore
import com.hikeratlas.android.atproto.NetworkException
import com.hikeratlas.android.atproto.OtherAccountException
import com.hikeratlas.android.atproto.Photos
import com.hikeratlas.android.atproto.PostException
import com.hikeratlas.android.atproto.Posts
import com.hikeratlas.android.atproto.ProfileRecord
import com.hikeratlas.android.atproto.ServerException
import com.hikeratlas.android.atproto.Tid
import com.hikeratlas.android.atproto.XrpcException
import com.hikeratlas.android.outbox.Outbox.Outcome
import com.hikeratlas.android.track.Fix
import com.hikeratlas.android.track.TrackLibrary
import com.hikeratlas.android.track.TrackStore
import com.hikeratlas.android.ui.waitingState
import com.hikeratlas.android.ui.waitingWhat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** An app with an outbox of its own, for its workers. */
class OutboxTestApp : Application(), OutboxHost {
    override lateinit var outbox: Outbox
}

/**
 * Posts waiting on the phone (#60), against a fake PDS: queued while
 * offline and posted once online, resumed from wherever they stopped
 * without posting anything twice, held for their own account, and their
 * files looked after.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = OutboxTestApp::class)
class OutboxTest {
    private val app get() = ApplicationProvider.getApplicationContext<OutboxTestApp>()
    private lateinit var fake: FakeAtproto
    private lateinit var account: Account
    private lateinit var store: TrackStore
    private lateinit var library: TrackLibrary
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
    private val gpx = javaClass.getResource("/gpx/app-two-segments.gpx")!!.readText()
    private val wm get() = WorkManager.getInstance(app)

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        File(app.filesDir, "outbox").deleteRecursively()
        File(app.filesDir, "photos").deleteRecursively()
        fake = FakeAtproto().start()
        account = Account(http, MemorySecretStore(), MemoryDpopKeys(), fake.config(), scope)
        signIn()
        store = TrackStore(app, null)
        library = TrackLibrary(store, null, regions = { null }, io = Dispatchers.Unconfined)
        app.outbox = newOutbox()
    }

    @After
    fun tearDown() {
        scope.cancel()
        fake.close()
        store.close()
    }

    private fun newOutbox() = Outbox(app, account, library, Photos.Store(File(app.filesDir, "photos")))

    private fun signIn() = runBlocking {
        account.beginSignIn(fake.authOrigin)
        account.finishSignIn(fake.callback(fake.pars.last()["state"]!!))
        assertEquals(fake.did, account.did)
    }

    private fun photo(name: String) = File(app.cacheDir, "form-$name").apply {
        writeBytes(this@OutboxTest.javaClass.getResource("/photos/$name")!!.readBytes())
    }.path

    /** A review with its track and two photos, as the form would send it. */
    private fun post(text: String = "Lovely.") = Posts.Post(
        text = text, hikedAt = "2026-07-02T19:00:00.000Z", tags = listOf("views"), title = "Mount Si", gpx = gpx,
        photos = listOf(Posts.Photo(photo("gps-offset.jpg"), alt = "Rainier"), Posts.Photo(photo("local.jpg"))),
    )

    private fun queue(p: Posts.Post = post(), did: String = fake.did, trackId: Long? = null) =
        runBlocking { app.outbox.add(did, fake.handle, "Mount Si", p, trackId) }

    private fun work(id: String) = runBlocking {
        TestListenableWorkerBuilder<PostWorker>(app).setInputData(workDataOf(PostWorker.POST to id)).build().doWork()
    }

    private fun entry(id: String) = runBlocking { app.outbox.entry(id) }
    private fun tracks() = fake.records.filterKeys { it.startsWith("${Posts.TRACK}/") }
    private fun reviews() = fake.records.filterKeys { it.startsWith("${Posts.REVIEW}/") }
    private fun writes() = fake.pdsRequests.filter { it.url.encodedPath.endsWith("uploadBlob") || it.url.encodedPath.endsWith("createRecord") }
    private fun unavailable() = FakeAtproto.json("""{"error":"ServiceUnavailable"}""", 503)

    /** The requests a post is made of: its licence (the profile), each blob, each record. */
    private fun isStep(r: RecordedRequest) = r.url.encodedPath.endsWith("uploadBlob") || r.url.encodedPath.endsWith("createRecord") ||
        (r.url.encodedPath.endsWith("getRecord") && r.url.queryParameter("collection") == ProfileRecord.NSID)

    private fun assertOneTrackAndOneReview() {
        assertEquals("one track", 1, tracks().size)
        assertEquals("one review", 1, reviews().size)
        val review = reviews().values.single()
        assertEquals("at://${fake.did}/${tracks().keys.single()}", review["track"]!!.jsonObject["uri"]!!.jsonPrimitive.content)
        assertEquals(2, review["photos"]!!.jsonArray.size)
        assertNull(tracks().values.single()["photos"])
    }

    private fun until(what: String, ms: Long = 10_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) {
            if (System.currentTimeMillis() > end) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun queuedWhileOfflineAndPostedOnceOnline() = runBlocking {
        // No signal: the form's try gets no answer, and it waits for a network.
        fake.fault = { r -> if (isStep(r)) FakeAtproto.dropped() else null }
        val e = queue()
        val o = app.outbox.upload(e.id)
        assertTrue(o is Outbox.Outcome.Waiting && !o.server)
        app.outbox.schedule(e.id)
        val info = wm.getWorkInfosForUniqueWork(Outbox.workName(e.id)).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertEquals(Outbox.State.WAITING, entry(e.id)!!.state)
        assertTrue(waitingState(entry(e.id)!!, null).startsWith("Waiting for signal"))
        assertEquals("Review, track and 2 photos", waitingWhat(e))
        assertTrue(tracks().isEmpty())

        // Signal: it goes up by itself.
        fake.fault = null
        WorkManagerTestInitHelper.getTestDriver(app)!!.setAllConstraintsMet(info.id)
        until("the post to go up") { wm.getWorkInfoById(info.id).get()?.state == WorkInfo.State.SUCCEEDED }
        assertOneTrackAndOneReview()
        assertNull(entry(e.id))
        assertFalse(File(app.filesDir, "outbox/${e.id}").exists())
        // Its records were made under the phone's own keys, dated when it was posted on the phone.
        val key = tracks().keys.single().substringAfter('/')
        assertTrue(key, Tid.isTid(key))
        assertEquals(e.createdAt, tracks().values.single()["createdAt"]!!.jsonPrimitive.content)
        assertEquals(e.createdAt, reviews().values.single()["createdAt"]!!.jsonPrimitive.content)
    }

    /**
     * Stopped at each step in turn (the licence, the GPX's blob, the track
     * record, each photo, the review), whether the request failed or its
     * answer was lost after the PDS did it: tried again, it carries on, and
     * there's one track and one review.
     */
    @Test
    fun resumedAfterStoppingAtEachStepWithOneTrackAndOneReview() {
        val steps = listOf("licence", "GPX blob", "track record", "photo 1", "photo 2", "review record")
        for ((k, step) in steps.withIndex()) for (lost in listOf(false, true)) {
            fake.records.clear()
            fake.blobs.clear()
            val e = queue()
            var n = 0
            val hit = { r: RecordedRequest -> isStep(r) && ++n == k + 1 }
            if (lost) fake.loseAnswer = hit else fake.fault = { r -> if (hit(r)) unavailable() else null }
            assertEquals("$step (answer lost: $lost)", ListenableWorker.Result.retry(), work(e.id))
            assertEquals(Outbox.State.WAITING, entry(e.id)!!.state)
            fake.fault = null
            fake.loseAnswer = null
            assertEquals("$step (answer lost: $lost)", ListenableWorker.Result.success(), work(e.id))
            assertOneTrackAndOneReview()
            assertNull(entry(e.id))
        }
    }

    @Test
    fun restartedInTheMiddleItCarriesOnFromWhereItWas() = runBlocking {
        val e = queue()
        var n = 0
        // The second photo's upload fails: the GPX, the track and the first photo are up.
        fake.fault = { r -> if (isStep(r) && ++n == 5) unavailable() else null }
        assertEquals(ListenableWorker.Result.retry(), work(e.id))
        val saved = entry(e.id)!!
        assertNotNull(saved.progress.gpxBlob)
        assertNotNull(saved.progress.track)
        assertEquals(setOf(0), saved.progress.photoBlobs.keys)
        assertEquals(1, tracks().size)

        // The app starts again: a new outbox, from what's on disk.
        fake.fault = null
        app.outbox = newOutbox()
        app.outbox.refresh()
        assertEquals(listOf(e.id), app.outbox.entries.value.map { it.id })
        assertEquals(ListenableWorker.Result.success(), work(e.id))
        assertOneTrackAndOneReview()
        // Nothing uploaded twice: the GPX and the two photos.
        assertEquals(listOf("application/gzip", "image/jpeg", "image/jpeg"), fake.blobs.map { it.first })
    }

    /** A blob kept from an earlier try that the PDS has dropped since: everything's uploaded again. */
    @Test
    fun blobsTheServerDroppedAreUploadedAgain() {
        val e = queue()
        var n = 0
        fake.fault = { r -> if (isStep(r) && ++n == 3) unavailable() else null }
        assertEquals(ListenableWorker.Result.retry(), work(e.id))
        val old = entry(e.id)!!.progress.gpxBlob!!["ref"]!!.jsonObject["\$link"]!!.jsonPrimitive.content
        fake.fault = { r ->
            if (r.url.encodedPath.endsWith("createRecord") && r.body!!.utf8().contains("\"$old\"")) {
                FakeAtproto.json("""{"error":"InvalidRequest","message":"Could not find blob: $old"}""", 400)
            } else {
                null
            }
        }
        assertEquals(ListenableWorker.Result.success(), work(e.id))
        assertOneTrackAndOneReview()
        assertTrue(tracks().values.single()["gpx"].toString().contains("bafkrei-2"))
    }

    @Test
    fun aRefusalFailsAtOnceAndSaysWhy() = runBlocking {
        fake.createFails = 400 to """{"error":"InvalidRecord","message":"Invalid record: no"}"""
        val e = queue()
        assertEquals(ListenableWorker.Result.failure(), work(e.id))
        val failed = entry(e.id)!!
        assertEquals(Outbox.State.FAILED, failed.state)
        assertEquals("createRecord failed (400): Invalid record: no", failed.error)
        assertEquals("Failed: createRecord failed (400): Invalid record: no", waitingState(failed, null))
        assertEquals("tried once", 1, fake.pdsRequests.count { it.url.encodedPath.endsWith("createRecord") })
        assertTrue(wm.getWorkInfosForUniqueWork(Outbox.workName(e.id)).get().isEmpty())

        // Retry, once it would be taken: it goes up, from where it was.
        fake.createFails = null
        app.outbox.retry(e.id)
        assertEquals(Outbox.State.WAITING, entry(e.id)!!.state)
        assertEquals(WorkInfo.State.ENQUEUED, wm.getWorkInfosForUniqueWork(Outbox.workName(e.id)).get().single().state)
        assertEquals(ListenableWorker.Result.success(), work(e.id))
        assertOneTrackAndOneReview()
        assertEquals(3, fake.blobs.size)
    }

    @Test
    fun anExpiredSessionKeepsThePostUntilSignedInAgain() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.outbox.start(scope)
        val e = queue()
        // Past its two weeks: refused, and the refresh token too.
        fake.accessExpired = true
        fake.refreshFails = true
        assertEquals(ListenableWorker.Result.success(), work(e.id))
        assertNull(account.did)
        val held = entry(e.id)!!
        assertEquals(Outbox.State.NEEDS_SIGN_IN, held.state)
        assertEquals("Needs you to sign in again: your session ended", waitingState(held, null))
        assertTrue(writes().isEmpty())
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        assertEquals("Sign in to post Mount Si", nm.allNotifications.single().extras.getCharSequence("android.title").toString())
        // Asked once, not again each try.
        work(e.id)
        assertEquals(1, nm.allNotifications.size)

        // Signed in again: it's tried again, and goes up.
        fake.refreshFails = false
        fake.accessExpired = false
        signIn()
        until("the post to wait again") { entry(e.id)?.state == Outbox.State.WAITING }
        assertEquals(WorkInfo.State.ENQUEUED, wm.getWorkInfosForUniqueWork(Outbox.workName(e.id)).get().single().state)
        assertEquals(ListenableWorker.Result.success(), work(e.id))
        assertOneTrackAndOneReview()
        assertEquals("Posted: Mount Si", nm.allNotifications.single().extras.getCharSequence("android.title").toString())
    }

    @Test
    fun aPostMadeAsAnotherAccountIsHeldForIt() = runBlocking {
        val other = "did:plc:zzzzzzzzzzzzzzzzzzzzzzzz"
        // (Signing in read the handle.)
        fake.pdsRequests.clear()
        val e = queue(did = other)
        assertEquals(ListenableWorker.Result.success(), work(e.id))
        val held = entry(e.id)!!
        assertEquals(Outbox.State.OTHER_ACCOUNT, held.state)
        assertTrue(waitingState(held, null).startsWith("Held: made as @${fake.handle}"))
        // Nothing went to this account's PDS: not even its tokens.
        assertTrue(fake.pdsRequests.isEmpty())
        // And calls pinned to an account refuse to go as another.
        try {
            account.xrpcAs(other).query("com.atproto.repo.describeRepo", mapOf("repo" to other))
            fail("went as the wrong account")
        } catch (_: OtherAccountException) {
        }
        assertTrue(fake.pdsRequests.isEmpty())
    }

    @Test
    fun discardingDeletesItsFilesButNotTheForms() = runBlocking {
        val p = post()
        val e = queue(p)
        val folder = File(app.filesDir, "outbox/${e.id}")
        assertTrue(File(folder, "track.gpx").isFile)
        assertEquals(2, e.post.photos.size)
        for (ph in e.post.photos) assertEquals(folder.path, File(ph.file).parent)
        app.outbox.schedule(e.id)
        app.outbox.discard(e.id)
        assertFalse(folder.exists())
        assertTrue(app.outbox.entries.value.isEmpty())
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfosForUniqueWork(Outbox.workName(e.id)).get().single().state)
        // The form's own photos are the form's to drop.
        for (ph in p.photos) assertTrue(File(ph.file).isFile)
    }

    @Test
    fun filesNothingNeedsAreCleanedUp() = runBlocking {
        val photos = Photos.Store(File(app.filesDir, "photos"))
        app.outbox = Outbox(app, account, library, photos)
        val old = System.currentTimeMillis() - 3_600_000
        // A form's photo, still open; one left by a form the app was killed with.
        val live = photos.save(javaClass.getResource("/photos/local.jpg")!!.readBytes())
        File(live.file).setLastModified(old)
        val stray = File(app.filesDir, "photos/stray.jpg").apply { writeBytes(ByteArray(10)); setLastModified(old) }
        // A post never saved (killed as it was), and one that was.
        val half = File(app.filesDir, "outbox/half").apply { mkdirs(); File(this, "track.gpx").writeText(gpx) }
        val e = queue()
        // The camera's leftovers: from days ago, and one being taken now.
        val camera = File(app.filesDir, "camera").apply { mkdirs() }
        val gone = File(camera, "old.jpg").apply { writeBytes(ByteArray(1)); setLastModified(old - 2 * 86_400_000) }
        val taking = File(camera, "now.jpg").apply { writeBytes(ByteArray(1)) }

        app.outbox.cleanOrphans()
        assertTrue(File(live.file).isFile)
        assertFalse(stray.exists())
        assertFalse(half.exists())
        assertNotNull(entry(e.id))
        assertEquals(2, File(app.filesDir, "outbox/${e.id}").listFiles()!!.count { it.name.startsWith("photo-") })
        assertFalse(gone.exists())
        assertTrue(taking.exists())
        // Dropped from the form: then it's the clean-up's too.
        photos.discard(live)
        assertFalse(File(live.file).exists())
    }

    /** Two posts of one recorded track: its record is made once, and the second points at it. */
    @Test
    fun aRecordedTrackIsPostedOnceEvenFromTwoPosts() = runBlocking {
        val id = store.start(1_783_000_000_000)
        for (i in 0 until 20) store.add(id, 0, Fix(1_783_000_000_000 + i * 5_000L, 47.45 + i * 0.0002, -121.8, 4f, mslAltitude = 200.0 + i))
        store.end(id, 1_783_000_100_000)
        val text = library.gpxText(id)!!
        val first = queue(Posts.Post(title = "Mount Si", gpx = text), trackId = id)
        val second = queue(Posts.Post(text = "Again.", title = "Mount Si", gpx = text), trackId = id)
        assertEquals(ListenableWorker.Result.success(), work(second.id))
        assertEquals(ListenableWorker.Result.success(), work(first.id))
        assertEquals(1, tracks().size)
        val mark = library.posted(id)!!
        assertEquals("at://${fake.did}/${tracks().keys.single()}", mark.trackUri)
        assertEquals("at://${fake.did}/${reviews().keys.single()}", mark.reviewUri)
        assertEquals(mark.trackUri, (reviews().values.single()["track"] as JsonObject)["uri"]!!.jsonPrimitive.content)
        assertEquals(1, fake.blobs.size)
    }

    /** A counted track's steps survive waiting in the outbox: they're in its GPX, and go in its record. */
    @Test
    fun aQueuedTracksStepsGoUpWithIt() = runBlocking {
        val id = store.start(1_783_000_000_000)
        for (i in 0 until 20) store.add(id, 0, Fix(1_783_000_000_000 + i * 5_000L, 47.45 + i * 0.0002, -121.8, 4f, mslAltitude = 200.0 + i))
        store.setSteps(id, 2_345, null)
        store.end(id, 1_783_000_100_000)
        fake.fault = { r -> if (isStep(r)) unavailable() else null }
        val e = queue(Posts.Post(title = "Mount Si", gpx = library.gpxText(id)!!), trackId = id)
        assertTrue(app.outbox.upload(e.id) is Outbox.Outcome.Waiting)
        fake.fault = null
        // Picked up again later, as if after the app was restarted.
        app.outbox = newOutbox()
        assertEquals(ListenableWorker.Result.success(), work(e.id))
        assertEquals("2345", tracks().values.single()["steps"]!!.jsonPrimitive.content)
    }

    /** A track recorded without counting has no steps in its record (not 0). */
    @Test
    fun anUncountedTrackPostsNoSteps() = runBlocking {
        val id = store.start(1_783_000_000_000)
        for (i in 0 until 20) store.add(id, 0, Fix(1_783_000_000_000 + i * 5_000L, 47.45 + i * 0.0002, -121.8, 4f))
        store.end(id, 1_783_000_100_000)
        val e = queue(Posts.Post(title = "Mount Si", gpx = library.gpxText(id)!!), trackId = id)
        assertEquals(ListenableWorker.Result.success(), work(e.id))
        assertNull(tracks().values.single()["steps"])
    }

    @Test
    fun notificationsAreLeftOutWhenNotAllowed() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val e = queue()
        assertEquals(ListenableWorker.Result.success(), work(e.id))
        assertTrue(shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    @Test
    fun tidsAreSortableAndValid() {
        val a = Tid.next(1_700_000_000_000_000)
        val b = Tid.next(1_700_000_000_000_000)
        assertTrue(a, Tid.isTid(a))
        assertTrue(b > a)
        assertEquals("2222222222222", Tid.encode(0))
    }

    /** #83: only the network and the server's own trouble wait; anything else fails where it can be seen. */
    @Test
    fun classifyWaitsOnlyForTheNetworkAndTheServer() {
        val o = app.outbox
        fun waiting(x: Throwable) = o.classify(x) as? Outcome.Waiting
        // OkHttp's transport failures, wrapped (as Xrpc does) or not, and inside a PostException.
        for (x in listOf(
            java.net.UnknownHostException("Unable to resolve host"), java.net.ConnectException("Failed to connect"),
            java.net.SocketTimeoutException("timeout"), java.net.NoRouteToHostException("No route to host"),
            javax.net.ssl.SSLHandshakeException("connection closed"), java.io.EOFException(),
            NetworkException(java.io.IOException("unexpected end of stream on http://pds")),
            PostException("Couldn't reach your account's server", NetworkException(java.net.SocketException("Connection reset"))),
        )) {
            val w = waiting(x)
            assertNotNull("$x waits", w)
            assertFalse("$x is the network's", w!!.server)
        }
        assertEquals("unexpected end of stream on http://pds", waiting(NetworkException(java.io.IOException("unexpected end of stream on http://pds")))!!.error)
        // A 5xx: waits, as the server's problem.
        assertEquals(Outcome.Waiting("HTTP 502", server = true), o.classify(PostException("x", ServerException(502, "com.atproto.repo.createRecord"))))
        assertEquals(Outcome.Waiting("HTTP 429", server = true), o.classify(XrpcException(429, "RateLimitExceeded", null)))
        // Not the network: a bad answer, a parse error, an IOException of the app's own.
        assertEquals(Outcome.Failed("getRecord gave no uri"), o.classify(java.io.IOException("getRecord gave no uri")))
        assertEquals(Outcome.Failed("Couldn't save the post"), o.classify(java.io.IOException("Couldn't save the post")))
        assertTrue(o.classify(kotlinx.serialization.SerializationException("Unexpected JSON token")) is Outcome.Failed)
        assertEquals(Outcome.Failed("createRecord failed (400): no"), o.classify(PostException("createRecord failed (400): no", XrpcException(400, "InvalidRecord", "no"))))
    }

    /** A 5xx waits and tries again, said as the server's problem rather than no signal. */
    @Test
    fun aServerProblemWaitsAndSaysSo() = runBlocking {
        fake.fault = { r -> if (r.url.encodedPath.endsWith("createRecord")) FakeAtproto.json("""{"error":"BadGateway"}""", 502) else null }
        val e = queue()
        assertEquals(Outcome.Waiting("HTTP 502", server = true), app.outbox.upload(e.id))
        val saved = entry(e.id)!!
        assertEquals(Outbox.State.WAITING, saved.state)
        assertEquals("Trying again soon: your account's server had a problem (HTTP 502)", waitingState(saved, null))
        assertEquals(ListenableWorker.Result.retry(), work(e.id))
        fake.fault = null
        assertEquals(ListenableWorker.Result.success(), work(e.id))
        assertOneTrackAndOneReview()
    }

    /** An answer that makes no sense fails (Retry or Discard), rather than waiting for a signal the phone has. */
    @Test
    fun aBadAnswerFailsRatherThanWaiting() = runBlocking {
        fake.fault = { r -> if (r.url.encodedPath.endsWith("uploadBlob")) FakeAtproto.json("not json") else null }
        val e = queue()
        assertTrue(app.outbox.upload(e.id) is Outcome.Failed)
        assertEquals(Outbox.State.FAILED, entry(e.id)!!.state)
    }

    /**
     * #83: a review with a 0 km track goes up, whether the track has one
     * point, several in one place, or none.
     */
    @Test
    fun aPostQueuedWithoutPhotoSizesGetsThemAsItGoesUp() = runBlocking {
        // As an older app queued it: photos without aspectRatio (post() makes them so).
        val e = queue()
        assertTrue(e.post.photos.all { it.aspectRatio == null })
        // Stopped after the first photo went up: measured once, and saved with the post.
        var uploads = 0
        fake.fault = { r -> if (r.url.encodedPath.endsWith("uploadBlob") && r.headers["Content-Type"] == "image/jpeg" && ++uploads == 2) unavailable() else null }
        assertTrue(app.outbox.upload(e.id) is Outcome.Waiting)
        val saved = entry(e.id)!!.post.photos
        assertEquals(Photos.AspectRatio(24, 32), saved[0].aspectRatio)
        fake.fault = null
        assertEquals(Outcome.Posted, app.outbox.upload(e.id))
        val photos = reviews().values.single()["photos"]!!.jsonArray.map { it.jsonObject }
        // 32 × 24 as stored: the first turned a quarter (Orientation 6), the second a half (3).
        assertEquals(listOf(24 to 32, 32 to 24), photos.map { it["aspectRatio"]!!.jsonObject.let { r -> r["width"]!!.jsonPrimitive.content.toInt() to r["height"]!!.jsonPrimitive.content.toInt() } })
        // In the website's order: image, alt, takenAt, aspectRatio, at.
        assertEquals(listOf("image", "alt", "aspectRatio"), photos[0].keys.toList())
    }

    @Test
    fun aPostQueuedFromTheFormKeepsItsPhotosSizes() = runBlocking {
        val form = Photos.Store(File(app.filesDir, "photos"))
        val p = form.save(javaClass.getResource("/photos/gps-offset.jpg")!!.readBytes())
        val e = queue(Posts.Post(title = "Mount Si", gpx = gpx, photos = listOf(p)))
        assertEquals(Photos.AspectRatio(24, 32), e.post.photos.single().aspectRatio)
        assertEquals(Outcome.Posted, app.outbox.upload(e.id))
        // A track posted alone carries them.
        val photo = tracks().values.single()["photos"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("image", "takenAt", "aspectRatio", "at"), photo.keys.toList())
        assertEquals("24", photo["aspectRatio"]!!.jsonObject["width"]!!.jsonPrimitive.content)
    }

    @Test
    fun aReviewWithAZeroKmTrackGoesUp() = runBlocking {
        val t = 1_783_000_000_000
        val shapes = listOf(
            "one point" to listOf(Fix(t, 47.45, -121.8, 4f, mslAltitude = 200.0)),
            "standing still" to (0 until 5).map { Fix(t + it * 5_000L, 47.45, -121.8, 4f, mslAltitude = 200.0) },
            "no points" to emptyList(),
        )
        for ((what, fixes) in shapes) {
            fake.records.clear()
            val id = store.start(t)
            for (f in fixes) store.add(id, 0, f)
            store.end(id, t + 20_000)
            val e = queue(Posts.Post(text = "Didn't get far.", title = "Mount Si", gpx = library.gpxText(id)!!), trackId = id)
            assertEquals(what, Outcome.Posted, app.outbox.upload(e.id))
            val track = tracks().values.single()
            assertEquals(what, "0", track["distance"]!!.jsonPrimitive.content)
            if (fixes.isNotEmpty()) assertEquals(what, if (fixes.size == 1) "0" else "20", track["duration"]!!.jsonPrimitive.content)
            assertEquals(what, 1, reviews().size)
            assertNotNull(what, library.posted(id)?.reviewUri)
        }
    }
}
