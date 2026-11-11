package com.hikeratlas.android.elevation

import com.hikeratlas.android.routes.ParkDoc
import com.hikeratlas.android.routes.RoutesRepository
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Profiles a real route from live Mapterhorn tiles (decoded by Android's
 * BitmapFactory, under Robolectric) and checks it against what
 * hikeratlas/routes measured. Opt in with HIKERATLAS_LIVE=1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LiveProfileTest {
    @Test
    fun blackTuskMatchesTheRoutesData() {
        assumeTrue(System.getenv("HIKERATLAS_LIVE") == "1")
        val doc = RoutesRepository.JSON.decodeFromString(
            ParkDoc.serializer(),
            javaClass.getResource("/fixtures/garibaldi-park.json")!!.readText(),
        )
        val route = doc.day_hikes[5].route // The Black Tusk, with its whole line
        val p = ElevationProfiler(listOf(MapterhornSource(OkHttpClient()))).profile(route.line)
        assertEquals(route.max_ele, p.high, 40.0)
        assertEquals(584.0, p.low, 40.0) // the routes data's min_ele
        assertEquals(route.km * 1000, p.length, 300.0)
        assertEquals(route.gain, p.gain, route.gain * 0.15)
    }
}
