package com.ikko.aura.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the device has a network, and whether it is one the user pays by the byte. */
data class NetworkState(val online: Boolean, val metered: Boolean)

/**
 * The device's network, told by the system as it changes (a callback, never a poll), and handed to [NetGuard] so that
 * nothing is sent in airplane mode. When a network comes back, hosts that only failed for want of one are asked again.
 */
@Singleton
class NetworkMonitor @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    private val _state = MutableStateFlow(current())
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    private var started = false

    /** Registers with the system once; the callback costs nothing between changes. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        NetGuard.isOnline = { _state.value.online }
        if (NetGuard.cache == null) NetGuard.cache = DiskCache(File(context.cacheDir, "netguard"))
        runCatching {
            manager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(stateOf(caps))
                override fun onLost(network: Network) = update(NetworkState(online = false, metered = false))
            })
        }
    }

    private fun update(next: NetworkState) {
        val wasOnline = _state.value.online
        _state.value = next
        if (next.online && !wasOnline) NetGuard.onNetworkRestored()
    }

    private fun current(): NetworkState = runCatching {
        manager?.getNetworkCapabilities(manager.activeNetwork)?.let(::stateOf)
    }.getOrNull() ?: NetworkState(online = false, metered = false)

    private fun stateOf(caps: NetworkCapabilities) = NetworkState(
        online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
    )
}
