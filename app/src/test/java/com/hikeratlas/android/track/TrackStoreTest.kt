package com.hikeratlas.android.track

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TrackStoreTest {
    @Test
    fun keepsTracksAndTheirFixes() {
        TrackStore(ApplicationProvider.getApplicationContext(), name = null).use { store ->
            val id = store.start(100)
            val f = Fix(200, 49.1, -123.2, 4.5f, altitude = 780.0, mslAltitude = 797.5, verticalAccuracy = 7f, speed = 1.4f)
            store.add(id, 0, f)
            store.add(id, 1, Fix(300, 49.2, -123.3, 3f))
            assertEquals(listOf(0 to f, 1 to Fix(300, 49.2, -123.3, 3f)), store.points(id))
            store.setPaused(id, 5_000, 250)
            assertEquals(TrackStore.Track(id, 100, null, null, 5_000, 250), store.unfinished())
            store.end(id, 400)
            assertNull(store.unfinished())
            assertEquals(400L, store.track(id)!!.ended)
        }
    }
}
