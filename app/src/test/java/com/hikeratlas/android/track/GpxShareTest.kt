package com.hikeratlas.android.track

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GpxShareTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val store = TrackStore(context, name = null)

    @After
    fun tearDown() = store.close()

    @Test
    fun aSavedTrackIsSharedAsAGpxFile() = runBlocking {
        val id = store.start(1_763_000_000_000)
        store.add(id, 0, Fix(1_763_000_001_000, 47.5, -121.9, 4f, altitude = 812.5))
        store.add(id, 0, Fix(1_763_000_011_000, 47.5002, -121.9, 4f, altitude = 815.0))
        store.end(id, 1_763_000_020_000)
        store.rename(id, "Upward Mobility · 14 Nov")
        val lib = TrackLibrary(store, null, regions = { null }, io = Dispatchers.Unconfined)

        val stale = File(context.cacheDir, "gpx/Old.gpx").apply { parentFile!!.mkdirs(); writeText("old") }
        val file = lib.gpx(id, context.cacheDir)!!
        assertEquals("Upward Mobility - 14 Nov.gpx", file.name)
        assertFalse("only the one being shared is kept", stale.exists())
        assertEquals(2, Regex("<trkpt ").findAll(file.readText()).count())

        val chooser = gpxShareIntent(context, file, "Upward Mobility · 14 Nov")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/gpx+xml", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.files", uri.authority)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)

        // The app picked can read it, through the provider.
        // As the manifest declares it.
        Robolectric.buildContentProvider(FileProvider::class.java).create(
            android.content.pm.ProviderInfo().apply {
                authority = "${context.packageName}.files"
                grantUriPermissions = true
                exported = false
            },
        )
        val read = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
        assertEquals(file.readText(), read)
    }
}
