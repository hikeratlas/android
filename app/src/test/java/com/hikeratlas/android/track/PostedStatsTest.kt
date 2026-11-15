package com.hikeratlas.android.track

import androidx.test.core.app.ApplicationProvider
import com.hikeratlas.android.atproto.Account
import com.hikeratlas.android.atproto.FakeAtproto
import com.hikeratlas.android.atproto.MemoryDpopKeys
import com.hikeratlas.android.atproto.MemorySecretStore
import com.hikeratlas.android.atproto.Posts
import com.hikeratlas.android.elevation.ElevationProfiler
import com.hikeratlas.android.units.Units
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A recorded track reads the same everywhere: its screens (and the
 * recording bar while it was recorded) show the distance and climb its
 * posted record says.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PostedStatsTest {
    private val store = TrackStore(ApplicationProvider.getApplicationContext(), name = null)
    private lateinit var fake: FakeAtproto
    private lateinit var account: Account
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var now = 1_783_000_000_000L

    // The DEM: a steep slope east, so its climb would differ from GPS's.
    private val slope = object : ElevationProfiler.DemSource {
        override val minZoom = 0
        override val maxZoom = 14
        override fun tile(z: Int, x: Int, y: Int) = FloatArray(512 * 512) { 1000f + (x * 512 + it % 512) * 3f }
    }

    @Before
    fun setUp() {
        Units.current = Units.METRIC
        fake = FakeAtproto().start()
        account = Account(OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build(), MemorySecretStore(), MemoryDpopKeys(), fake.config(), scope)
        runBlocking {
            account.beginSignIn(fake.authOrigin)
            account.finishSignIn(fake.callback(fake.pars.last()["state"]!!))
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        fake.close()
        store.close()
    }

    /** Records a walk east and up, with a pause; GPS altitude on each fix unless [noAltitude]. */
    private fun record(recorder: TrackRecorder, noAltitude: Boolean = false): Long {
        val id = recorder.start().trackId
        for (i in 0 until 160) {
            if (i == 90) { recorder.pause(); now += 600_000; recorder.resume() }
            now += 3_217
            val ele = 200.0 + i * 0.8 + (i % 5) * 2.3 - (if (i > 120) (i - 120) * 2.0 else 0.0)
            recorder.onFix(
                Fix(
                    now, 47.45 + i * 0.00009, -121.81 + i * 0.00006, 5f,
                    altitude = if (noAltitude) null else ele - 22.0, mslAltitude = if (noAltitude) null else ele,
                ),
            )
        }
        return id
    }

    private suspend fun posted(lib: TrackLibrary, id: Long) =
        Posts.publish(account.xrpc, fake.did, Posts.Post(routeKey = "k", title = "T", gpx = lib.gpxText(id)!!)).let {
            fake.records["${Posts.TRACK}/${it.track!!.uri.substringAfterLast('/')}"]!!
        }

    @Test
    fun theTrackScreensShowWhatThePostSays() = runBlocking {
        val profiler = ElevationProfiler(listOf(slope))
        val recorder = TrackRecorder(store, profiler, clock = { now }, background = { it.run() })
        val id = record(recorder)
        val live = recorder.state!!
        recorder.stop()
        val lib = TrackLibrary(store, profiler, regions = { null }, io = Dispatchers.Unconfined)
        val d = lib.detail(id)!!
        val record = posted(lib, id)
        val distance = record["distance"]!!.jsonPrimitive.long
        val gain = record["gain"]!!.jsonPrimitive.long
        assertEquals(distance.toDouble(), d.stats.metres, 0.0)
        assertEquals(gain.toDouble(), d.stats.gain!!, 0.0)
        // GPS's climb, not the DEM's.
        assertTrue("${d.profile?.gain} vs $gain", kotlin.math.abs(d.profile!!.gain - gain) > 10)
        // The recording bar showed the same at the end.
        assertEquals(distance.toDouble(), live.metres, 0.0)
        assertEquals(gain.toDouble(), live.gain!!, 0.0)
        // And the list's line, in the units shown.
        assertTrue(statsLine(d.stats), statsLine(d.stats).startsWith(com.hikeratlas.android.units.dist(distance / 1000.0, 1)))
        assertTrue(statsLine(d.stats), statsLine(d.stats).endsWith("↑ ${com.hikeratlas.android.units.elev(gain.toDouble())}"))
        // The post form's numbers are the record's.
        assertEquals(TrackMeasure.ofGpx(lib.gpxText(id)!!).distance, distance)
    }

    @Test
    fun withoutGpsAltitudesTheClimbIsTheDemsAndThePostHasNone() = runBlocking {
        val profiler = ElevationProfiler(listOf(slope))
        val recorder = TrackRecorder(store, profiler, clock = { now }, background = { it.run() })
        val id = record(recorder, noAltitude = true)
        recorder.stop()
        val lib = TrackLibrary(store, profiler, regions = { null }, io = Dispatchers.Unconfined)
        val d = lib.detail(id)!!
        val record = posted(lib, id)
        assertNull(record["gain"])
        assertEquals(d.profile!!.gain, d.stats.gain!!, 0.0)
        assertEquals(record["distance"]!!.jsonPrimitive.long.toDouble(), d.stats.metres, 0.0)
    }
}
