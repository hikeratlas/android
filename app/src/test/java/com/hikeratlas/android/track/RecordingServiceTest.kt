package com.hikeratlas.android.track

import android.Manifest
import android.app.Application
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** An app with a recorder of its own, without the map. */
class RecorderTestApp : Application(), RecorderHost {
    val store by lazy { TrackStore(this, name = null) }
    override val recorder by lazy { TrackRecorder(store, null) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RecorderTestApp::class)
class RecordingServiceTest {
    private val app get() = ApplicationProvider.getApplicationContext<RecorderTestApp>()
    private val lm get() = app.getSystemService(LocationManager::class.java)

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }

    private fun gps(lat: Double, t: Long) = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = lat; longitude = -123.0; accuracy = 5f; time = t
        altitude = 900.0; verticalAccuracyMeters = 8f
        elapsedRealtimeNanos = t * 1_000_000
    }

    /** A fix from GPS, delivered (the listener runs on the main looper). */
    private fun simulate(l: Location) {
        shadowOf(lm).simulateLocation(l)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun command(action: String) =
        Robolectric.buildService(RecordingService::class.java, Intent(app, RecordingService::class.java).setAction(action))

    @Test
    fun recordsGpsFixesInTheForegroundUntilStopped() {
        val service = command(RecordingService.ACTION_START).create().startCommand(0, 1)
        // A foreground service, with its notification.
        assertNotNull(shadowOf(service.get()).lastForegroundNotification)
        val id = app.recorder.state!!.trackId

        simulate(gps(49.0, 1_000))
        simulate(gps(49.0002, 3_000))
        val points = app.store.points(id).map { it.second }
        assertEquals(2, points.size)
        assertEquals(900.0, points[0].altitude!!, 0.0)
        assertEquals(8f, points[0].verticalAccuracy!!, 0f)

        // Paused: GPS isn't listened to.
        service.withIntent(Intent(app, RecordingService::class.java).setAction(RecordingService.ACTION_PAUSE)).startCommand(0, 2)
        assertTrue(app.recorder.state!!.paused)
        simulate(gps(49.0004, 5_000))
        assertEquals(2, app.store.points(id).size)
        assertTrue(shadowOf(lm).getLocationRequests(LocationManager.GPS_PROVIDER).isEmpty())

        // Resumed, then stopped: the track ends, and the service with it.
        service.withIntent(Intent(app, RecordingService::class.java).setAction(RecordingService.ACTION_START)).startCommand(0, 3)
        simulate(gps(49.0006, 7_000))
        assertEquals(3, app.store.points(id).size)
        service.withIntent(Intent(app, RecordingService::class.java).setAction(RecordingService.ACTION_STOP)).startCommand(0, 4)
        assertNull(app.recorder.state)
        assertNotNull(app.store.track(id)!!.ended)
        assertTrue(shadowOf(service.get()).isStoppedBySelf)
    }

    @Test
    fun restartedByTheSystemItCarriesOnTheUnfinishedTrack() {
        val id = app.store.start(500)
        app.store.add(id, 0, Fix(600, 49.0, -123.0, 5f))
        // As the system restarts a sticky service: no intent.
        val service = Robolectric.buildService(RecordingService::class.java, null).create()
        service.get().onStartCommand(null, 0, 1)
        assertEquals(id, app.recorder.state!!.trackId)
        simulate(gps(49.0003, 2_000))
        assertEquals(2, app.store.points(id).size)
    }
}
