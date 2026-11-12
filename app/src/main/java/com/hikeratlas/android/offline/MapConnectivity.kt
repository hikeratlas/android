package com.hikeratlas.android.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import org.maplibre.android.MapLibre

/**
 * Keeps MapLibre asking for tiles with no network.
 *
 * MapLibre holds back every request while Android says there's no network,
 * serving only its own cache. But the app answers most of its requests
 * itself (tiles, terrain, fonts and icons, from offline areas), so with the
 * cache cleared an offline map was blank. Told it's always connected, it
 * asks, and what isn't kept fails and is retried later.
 *
 * Told that, it no longer notices a network coming back, so it's nudged
 * then: off and on again, which makes it retry what failed at once.
 */
object MapConnectivity {
    fun install(context: Context, setConnected: (Boolean) -> Unit = { MapLibre.setConnected(it) }) {
        setConnected(true)
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val main = Handler(Looper.getMainLooper())
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post {
                    setConnected(false)
                    setConnected(true)
                }
            }
        })
    }
}
