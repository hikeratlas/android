package com.hikeratlas.android.track

import androidx.test.core.app.ApplicationProvider
import com.hikeratlas.android.elevation.ElevationProfiler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TrackRecorderTest {
    private val store = TrackStore(ApplicationProvider.getApplicationContext(), name = null)
    private var now = 1_000_000L

    @After
    fun tearDown() = store.close()

    // A slope: 1 m up per DEM pixel eastwards, so the climb is measurable.
    private val slope = object : ElevationProfiler.DemSource {
        override val minZoom = 0
        override val maxZoom = 14
        override fun tile(z: Int, x: Int, y: Int) = FloatArray(512 * 512) { 1000f + (x * 512 + it % 512) * 0.5f }
    }

    private fun recorder(profiler: ElevationProfiler? = null) = TrackRecorder(store, profiler, clock = { now }, background = { it.run() })

    private fun fix(lat: Double, lon: Double = -123.0, alt: Double? = 812.5, msl: Double? = 830.1) =
        Fix(now, lat, lon, 4f, altitude = alt, mslAltitude = msl, verticalAccuracy = 6f, speed = 1.2f)

    @Test
    fun recordsKeptFixesWithGpsAltitude() {
        val r = recorder()
        val id = r.start().trackId
        r.onFix(fix(49.0))
        now += 5_000
        r.onFix(fix(49.00002)) // jitter: dropped
        now += 5_000
        r.onFix(fix(49.0002))
        val saved = store.points(id).map { it.second }
        assertEquals(2, saved.size)
        // GPS's own altitudes are kept, for checking the DEM against.
        assertEquals(812.5, saved[0].altitude!!, 0.0)
        assertEquals(830.1, saved[0].mslAltitude!!, 0.0)
        assertEquals(6f, saved[0].verticalAccuracy!!, 0f)
        assertEquals(22.2, r.state!!.metres, 0.2)
        assertEquals(listOf(2), r.state!!.segments.map { it.size })
    }

    @Test
    fun pausedTimeIsntCountedAndPausedFixesArentKept() {
        val r = recorder()
        val id = r.start().trackId
        r.onFix(fix(49.0))
        now += 60_000
        r.pause()
        now += 600_000
        r.onFix(fix(49.001))
        assertEquals(60_000, r.state!!.elapsed(now))
        r.resume()
        now += 30_000
        r.onFix(fix(49.002))
        assertEquals(90_000, r.state!!.elapsed(now))
        assertEquals(listOf(0, 1), store.points(id).map { it.first })
        assertEquals(id, r.stop())
        assertNull(r.state)
        assertNotNull(store.track(id)!!.ended)
        assertNull(store.unfinished())
    }

    @Test
    fun aTrackLeftUnfinishedComesBackPausedFromItsLastFix() {
        val first = recorder()
        val id = first.start().trackId
        first.onFix(fix(49.0))
        now += 120_000
        first.onFix(fix(49.001))
        // The app is killed; it opens again an hour later.
        now += 3_600_000
        val again = recorder()
        assertTrue(again.resumeUnfinished(paused = true))
        val s = again.state!!
        assertEquals(id, s.trackId)
        assertTrue(s.paused)
        assertEquals(120_000, s.elapsed(now))
        assertEquals(111.2, s.metres, 0.5)
        // Resumed: carries on in a new segment, the hour not counted.
        again.resume()
        now += 10_000
        again.onFix(fix(49.002))
        assertEquals(130_000, again.state!!.elapsed(now))
        assertEquals(listOf(2, 1), again.state!!.segments.map { it.size })
    }

    @Test
    fun theClimbIsMeasuredFromTheDem() {
        val r = recorder(ElevationProfiler(listOf(slope)))
        r.start()
        r.onFix(fix(49.0, lon = -123.0))
        r.onFix(fix(49.0, lon = -122.99))
        assertTrue("climb ${r.state!!.gain}", (r.state!!.gain ?: 0.0) > 0)
    }
}
