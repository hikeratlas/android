package com.hikeratlas.android.offline

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.hikeratlas.android.logs.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the phone has a network with internet, kept up to date from the
 * default network's callbacks, between [start] and [close].
 *
 * Worked out from what each callback is given, never by asking
 * [ConnectivityManager.getActiveNetwork] from inside one: as Android's docs
 * warn, during a handover (Wi-Fi to cellular, say) that can still be null
 * or the old network, and being offline then stuck until the next callback,
 * which might not come for a long while (#83).
 *
 * Online means the network has [NetworkCapabilities.NET_CAPABILITY_INTERNET],
 * not that Android has validated it: validation comes a while after a
 * network connects, and never on a captive portal or some slow networks,
 * where the map's own tiles and a post may still get through. A request
 * that then fails says so itself.
 */
class Connectivity(private val cm: ConnectivityManager?) : AutoCloseable {
    // The network the callbacks say is the default now, if any. Callbacks
    // come one at a time, on Android's own thread.
    @Volatile private var current: Network? = cm?.activeNetwork

    private val _online = MutableStateFlow(current?.let { cm?.getNetworkCapabilities(it) }?.hasInternet() == true)
    val online: StateFlow<Boolean> = _online

    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        // A new default network: online, until its capabilities (which follow
        // at once) say otherwise.
        override fun onAvailable(network: Network) {
            current = network
            set(true, "network $network available")
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            current = network
            set(caps.hasInternet(), "network $network ${if (caps.hasInternet()) "has" else "has no"} internet")
        }

        // An old network going after a new one took over changes nothing.
        override fun onLost(network: Network) {
            if (network == current) {
                current = null
                set(false, "network $network lost")
            } else {
                Log.d(LOG, "Network $network lost, not the default: no change")
            }
        }
    }

    /** Starts following the default network: it's told at once what that is, if there's one. */
    fun start(): Connectivity {
        if (!registered && cm != null) {
            cm.registerDefaultNetworkCallback(callback)
            registered = true
            Log.d(LOG, "Following the default network: ${if (_online.value) "online" else "offline"}")
        }
        return this
    }

    override fun close() {
        if (registered) {
            cm?.unregisterNetworkCallback(callback)
            registered = false
        }
    }

    /** Only changes are logged: capabilities come often, mostly the same. */
    private fun set(online: Boolean, why: String) {
        if (_online.value != online) Log.i(LOG, "${if (online) "Online" else "Offline"}: $why")
        _online.value = online
    }

    private fun NetworkCapabilities.hasInternet() = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

    companion object {
        private const val LOG = "network"
        fun of(context: android.content.Context) = Connectivity(context.getSystemService(ConnectivityManager::class.java))
    }
}
