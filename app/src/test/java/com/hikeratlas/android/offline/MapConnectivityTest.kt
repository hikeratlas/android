package com.hikeratlas.android.offline

import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MapConnectivityTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    @Test
    fun mapLibreIsToldItsConnectedEvenWithNoNetwork() {
        shadowOf(cm).setDefaultNetworkActive(false)
        val calls = mutableListOf<Boolean>()
        MapConnectivity.install(context) { calls += it }
        assertEquals(listOf(true), calls)
    }

    @Test
    fun aNetworkComingBackNudgesItToRetry() {
        val calls = mutableListOf<Boolean>()
        MapConnectivity.install(context) { calls += it }
        val callbacks = shadowOf(cm).networkCallbacks
        assertEquals(1, callbacks.size)
        callbacks.first().onAvailable(ShadowNetwork.newInstance(1))
        ShadowLooper.idleMainLooper()
        // Left connected: off, then on again.
        assertEquals(listOf(true, false, true), calls)
    }
}
