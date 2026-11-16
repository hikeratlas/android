package com.hikeratlas.android.ui

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.hikeratlas.android.atproto.Profiles
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * The viewer's Download: into Pictures/Hiker Atlas through MediaStore on
 * Android 10+, the share sheet on 8 and 9. Robolectric's ContentResolver
 * records the insert and update; nothing reaches real storage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PhotoDownloadTest {
    private val server = MockWebServer()
    private val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
    private val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
    private val photo = Profiles.Photo(Profiles.Blob("bafkreiabcdefghijklmnopqrstuvwxyz234567", "image/jpeg"), "The lake")

    @After
    fun tearDown() = server.close()

    private fun serve(contentType: String = "image/jpeg"): String {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", contentType).body(Buffer().write(bytes)).build())
        server.start()
        return server.url("/xrpc/com.atproto.sync.getBlob?did=x&cid=y").toString()
    }

    @Test
    @Config(sdk = [29])
    fun android10SavesToPicturesThroughMediaStore() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val resolver = shadowOf(activity.contentResolver)
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // Robolectric's resolver numbers inserted rows from 1.
        val row = ContentUris.withAppendedId(collection, 1)
        val written = ByteArrayOutputStream()
        resolver.registerOutputStream(row, written)
        val url = serve()

        val said = runBlocking { PhotoDownload.download(activity, http, url, photo) }

        assertEquals("Saved to Pictures/Hiker Atlas", said)
        val insert = resolver.insertStatements.single()
        assertEquals(collection, insert.uri)
        val v = insert.contentValues
        assertEquals("hikeratlas-qrstuvwxyz234567.jpg", v.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals("image/jpeg", v.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("Pictures/Hiker Atlas", v.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals(1, v.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        // The bytes as posted, then shown in the gallery.
        assertArrayEquals(bytes, written.toByteArray())
        val update = resolver.updateStatements.single()
        assertEquals(row, update.uri)
        assertEquals(0, update.contentValues.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        // Nothing to share: it's saved.
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    @Config(sdk = [28])
    fun android9GetsTheShareSheet() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val url = serve()

        val said = runBlocking { PhotoDownload.download(activity, http, url, photo) }

        assertNull(said)
        assertTrue(shadowOf(activity.contentResolver).insertStatements.isEmpty())
        val chooser = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/jpeg", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION")
        val stream = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", stream.scheme)
        assertEquals("${activity.packageName}.files", stream.authority)
        assertTrue(stream.path!!.startsWith("/photos/hikeratlas-"))
        // The file shared is the photo as posted.
        val file = java.io.File(activity.cacheDir, "photos/${stream.lastPathSegment}")
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun aPngIsSavedAsAPng() {
        assertEquals("image/png", PhotoDownload.mimeType("image/png", "image/jpeg"))
        assertEquals("image/webp", PhotoDownload.mimeType(null, "image/webp; charset=binary"))
        assertEquals("image/jpeg", PhotoDownload.mimeType("text/html", "application/octet-stream"))
        assertEquals("hikeratlas-bafkpng.png", PhotoDownload.fileName("bafk-png", "image/png"))
    }
}
