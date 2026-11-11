package com.hikeratlas.android.track

import androidx.test.core.app.ApplicationProvider
import com.hikeratlas.android.routes.Regions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TrackLibraryTest {
    private val store = TrackStore(ApplicationProvider.getApplicationContext(), name = null)

    @After
    fun tearDown() = store.close()

    private val raging = Regions(
        listOf(
            Regions.Country(
                "United States", "united-states", null,
                listOf(Regions.State("Washington", "washington", null, listOf(Regions.Area("9", "Raging River State Forest", "rr", listOf(-122.0, 47.4, -121.8, 47.6))))),
            ),
        ),
    )

    private fun recorded(): Long {
        val id = store.start(1_700_000_000_000)
        store.add(id, 0, Fix(1_700_000_010_000, 47.5, -121.9, 4f))
        store.add(id, 0, Fix(1_700_000_020_000, 47.5001, -121.9, 4f))
        store.end(id, 1_700_000_060_000)
        return id
    }

    @Test
    fun aStoppedTrackIsNamedForItsParkAndGetsItsNumbers() = runBlocking {
        val lib = TrackLibrary(store, null, regions = { raging }, io = Dispatchers.Unconfined)
        val id = recorded()
        val d = lib.detail(id)!!
        val date = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(1_700_000_000_000))
        assertEquals("Raging River State Forest · $date", d.name)
        assertEquals(60_000, d.stats.elapsedMs)
        assertEquals(11.1, d.stats.metres, 0.1)
        // Kept: the list shows them without working them out again.
        assertEquals(d.stats, store.track(id)!!.stats)
        assertEquals(d.name, store.track(id)!!.name)
    }

    @Test
    fun withoutTheParksItsNamedForTheDate() = runBlocking {
        val lib = TrackLibrary(store, null, regions = { error("offline") }, io = Dispatchers.Unconfined)
        val d = lib.detail(recorded())!!
        assertEquals(SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(1_700_000_000_000)), d.name)
    }

    @Test
    fun renameDeleteAndReopenUpdateTheList() = runBlocking {
        val lib = TrackLibrary(store, null, regions = { null }, io = Dispatchers.Unconfined)
        val a = recorded()
        val b = recorded()
        lib.refresh()
        assertEquals(2, lib.tracks.size)
        lib.rename(a, "  ")
        assertEquals("Track", store.track(a)!!.name)
        lib.delete(b)
        assertEquals(listOf(a), lib.tracks.map { it.id })
        // Reopened: no longer saved, and the recorder picks it up paused where it stopped.
        lib.reopen(a)
        assertTrue(lib.tracks.isEmpty())
        val r = TrackRecorder(store, null, clock = { 1_700_000_100_000 }, background = { it.run() })
        assertTrue(r.resumeUnfinished(paused = false))
        assertTrue(r.state!!.paused)
        r.resume()
        assertEquals(60_000, r.state!!.elapsed(1_700_000_100_000))
    }

    /** Tracks saved before names and numbers were kept get them. */
    @Test
    fun fillsInOlderTracks() = runBlocking {
        val lib = TrackLibrary(store, null, regions = { raging }, io = Dispatchers.Unconfined)
        val id = recorded()
        lib.refresh()
        assertEquals(null, lib.tracks.single().stats)
        lib.fillMissing()
        val t = lib.tracks.single()
        assertEquals(id, t.id)
        assertEquals(11.1, t.stats!!.metres, 0.1)
        assertTrue(t.name!!.startsWith("Raging River State Forest"))
    }
}
