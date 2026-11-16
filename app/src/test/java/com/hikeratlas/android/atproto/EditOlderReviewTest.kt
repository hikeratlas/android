package com.hikeratlas.android.atproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Editing a review posted before photos' aspectRatio was required
 * (website#177): its photos are measured from their blobs on the PDS and
 * get it, as the website's EditReviewDialog does, or the edit isn't saved.
 * Under Robolectric, for Android's decoder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class EditOlderReviewTest {
    private lateinit var fake: FakeAtproto
    private lateinit var account: Account
    private val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun blob(cid: String) = """{"${'$'}type":"blob","ref":{"${'$'}link":"$cid"},"mimeType":"image/jpeg","size":1}"""
    private fun bytes(name: String) = javaClass.getResource("/photos/$name")!!.readBytes()
    private val key = "${Posts.REVIEW}/3old"

    @Before
    fun setUp() {
        fake = FakeAtproto().start()
        account = Account(http, MemorySecretStore(), MemoryDpopKeys(), fake.config(), scope)
        runBlocking {
            account.beginSignIn(fake.authOrigin)
            account.finishSignIn(fake.callback(fake.pars.last()["state"]!!))
        }
        Indexer.notifier = { _, done -> done() }
    }

    @After
    fun tearDown() {
        Indexer.notifier = { _, done -> done() }
        scope.cancel()
        fake.close()
    }

    /** A review whose photos have no aspectRatio (one has, already), as the profile reads it. */
    private fun older(): Profiles.Review {
        fake.records[key] = obj(
            """{"${'$'}type":"${Posts.REVIEW}","text":"Muddy","createdAt":"2026-11-01T10:00:00.000Z",
            "photos":[{"image":${blob("a")},"alt":"one","takenAt":"2026-10-31T15:00:00.000Z"},{"image":${blob("b")}},
            {"image":${blob("c")},"aspectRatio":{"width":5,"height":4}}]}""",
        )
        return runBlocking { Profiles.loadDid(http, Identity(http, fake.config()), fake.did) }.reviews.single()
    }

    private fun puts() = fake.pdsRequests.filter { it.url.encodedPath.endsWith("putRecord") }
    private fun blobReads() = fake.pdsRequests.filter { it.url.encodedPath.endsWith("getBlob") }.map { it.url.queryParameter("cid") }

    @Test
    fun photosWithoutTheirSizeAreMeasuredFromTheirBlobs() {
        // As stored, 32 × 24: one turned a quarter (Orientation 6), one a half (3).
        fake.blobsByCid["a"] = bytes("gps-offset.jpg")
        fake.blobsByCid["b"] = bytes("local.jpg")
        val r = older()
        val photos = Posts.photoEntries(r.value)
        val saved = runBlocking {
            Posts.editReview(account.xrpc, fake.did, r, Posts.ReviewEdit("Muddy, then snow", photos = listOf(Posts.withAlt(photos[0], "The bridge"), photos[1], photos[2])))
        }
        val written = fake.records[key]!!
        assertEquals(written, saved.record)
        val out = (written["photos"] as JsonArray).map { it.jsonObject }
        assertEquals(
            listOf(
                obj("""{"image":${blob("a")},"alt":"The bridge","takenAt":"2026-10-31T15:00:00.000Z","aspectRatio":{"width":24,"height":32}}"""),
                obj("""{"image":${blob("b")},"aspectRatio":{"width":32,"height":24}}"""),
                obj("""{"image":${blob("c")},"aspectRatio":{"width":5,"height":4}}"""),
            ),
            out,
        )
        // Added after what the entry had, as the website's { ...p, aspectRatio }.
        assertEquals(listOf("image", "alt", "takenAt", "aspectRatio"), out[0].keys.toList())
        // Only those without it were read.
        assertEquals(listOf("a", "b"), blobReads())
        // The profile shows it from what was written, sizes included.
        val shown = Profiles.parseReview(obj("""{"uri":"${saved.ref.uri}","cid":"${saved.ref.cid}","value":${saved.record}}"""))!!
        assertEquals(Photos.AspectRatio(24, 32), shown.photos[0].aspectRatio)
    }

    @Test
    fun aPhotoThatCantBeMeasuredStopsTheEdit() {
        fake.blobsByCid["a"] = bytes("gps-offset.jpg")
        // "b": gone from the PDS.
        val r = older()
        try {
            runBlocking { Posts.editReview(account.xrpc, fake.did, r, Posts.ReviewEdit("x", photos = Posts.photoEntries(r.value))) }
            fail("saved")
        } catch (e: PostException) {
            assertEquals("Photo 2 couldn't be read from your account to record its size, which photos now need. Remove it and save again.", e.message)
        }
        // Not an image: the same.
        fake.blobsByCid["b"] = "not a photo".toByteArray()
        try {
            runBlocking { Posts.editReview(account.xrpc, fake.did, r, Posts.ReviewEdit("x", photos = Posts.photoEntries(r.value))) }
            fail("saved")
        } catch (e: PostException) {
            assertTrue(e.message!!, e.message!!.startsWith("Photo 2 couldn't be read"))
        }
        assertTrue(puts().isEmpty())
        assertEquals("Muddy", fake.records[key]!!["text"]!!.jsonPrimitive.content)

        // Removed, the edit saves.
        val kept = Posts.photoEntries(r.value).filterIndexed { i, _ -> i != 1 }
        runBlocking { Posts.editReview(account.xrpc, fake.did, r, Posts.ReviewEdit("x", photos = kept)) }
        assertEquals(2, (fake.records[key]!!["photos"] as JsonArray).size)
    }

    @Test
    fun withoutSignalToReadAPhotoItSaysSo() {
        val r = older()
        fake.fault = { if (it.url.encodedPath.endsWith("getBlob")) FakeAtproto.json("""{"error":"Unavailable"}""", 503) else null }
        try {
            runBlocking { Posts.editReview(account.xrpc, fake.did, r, Posts.ReviewEdit("x", photos = Posts.photoEntries(r.value))) }
            fail("saved")
        } catch (e: PostException) {
            assertTrue(e.message!!, e.message!!.startsWith("Couldn't reach your account's server"))
        }
        assertTrue(puts().isEmpty())
    }
}
