package com.hikeratlas.android.offline

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * Online or not, from the default network's callbacks alone (#83): a
 * handover from Wi-Fi to cellular, with the active network momentarily
 * null or the old one, never reads as offline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ConnectivityTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val shadow = shadowOf(cm)
    private lateinit var connectivity: Connectivity

    private fun caps(vararg c: Int) = ShadowNetworkCapabilities.newInstance().also { n -> c.forEach { shadowOf(n).addCapability(it) } }
    private val internet = caps(NetworkCapabilities.NET_CAPABILITY_INTERNET)

    @Before
    fun setUp() {
        shadow.setNetworkCapabilities(cm.activeNetwork, internet)
        connectivity = Connectivity(cm).start()
    }

    @After
    fun tearDown() = connectivity.close()

    private fun callbacks() = shadow.networkCallbacks.toList()

    @Test
    fun startsFromTheActiveNetwork() {
        assertTrue(connectivity.online.value)
        assertEquals(1, callbacks().size)
        connectivity.close()
        assertTrue("unregistered", callbacks().isEmpty())
    }

    @Test
    fun staysOnlineThroughAHandover() {
        val wifi = cm.activeNetwork!!
        val cell = ShadowNetwork.newInstance(9_999)
        // Mid-handover: Android has no active network to give for a moment.
        shadow.setDefaultNetworkActive(false)
        shadow.setActiveNetworkInfo(null)
        for (c in callbacks()) {
            c.onAvailable(cell)
            c.onCapabilitiesChanged(cell, internet)
        }
        assertTrue(connectivity.online.value)
        // The old network goes after the new one took over.
        for (c in callbacks()) c.onLost(wifi)
        assertTrue(connectivity.online.value)
    }

    @Test
    fun offlineWhenTheNetworkGoesWithNoneInItsPlace() {
        val wifi = cm.activeNetwork!!
        for (c in callbacks()) c.onLost(wifi)
        assertFalse(connectivity.online.value)
        // And back.
        val cell = ShadowNetwork.newInstance(9_999)
        for (c in callbacks()) c.onAvailable(cell)
        assertTrue(connectivity.online.value)
    }

    @Test
    fun aNetworkWithoutInternetIsOffline() {
        val wifi = cm.activeNetwork!!
        for (c in callbacks()) c.onCapabilitiesChanged(wifi, caps())
        assertFalse(connectivity.online.value)
        // Not yet validated (or a captive portal) still counts: requests may get through.
        for (c in callbacks()) c.onCapabilitiesChanged(wifi, internet)
        assertTrue(connectivity.online.value)
    }
}
