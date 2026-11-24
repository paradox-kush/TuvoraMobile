package com.nuvio.app.features.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.nuvio.app.features.common.lifecycle.Disposable
import kotlin.time.TimeSource

/**
 * Android's [PlaybackNetworkSwitchCoordinator]: the default network from `registerDefaultNetworkCallback`, and the
 * shared playback OkHttp stack as the pool. One per process; players register while they hold a stream.
 */
internal object AndroidPlaybackNetworkSwitch {
    private val clockOrigin = TimeSource.Monotonic.markNow()

    @Volatile
    private var instance: PlaybackNetworkSwitchCoordinator? = null

    fun coordinator(context: Context): PlaybackNetworkSwitchCoordinator =
        instance ?: synchronized(this) {
            instance ?: PlaybackNetworkSwitchCoordinator(
                monitor = ConnectivityDefaultNetworkMonitor(context.applicationContext),
                pool = PlayerPlaybackNetworking.connectionPool,
                nowMs = { clockOrigin.elapsedNow().inWholeMilliseconds },
            ).also { instance = it }
        }
}

/**
 * The default network's id, from `ConnectivityManager.registerDefaultNetworkCallback`. The callback reports the
 * current default on registration, then every change; `onLost` of the default means none (until the next
 * `onAvailable`). Callbacks arrive on a private thread, never Main. A network that is only re-validated or
 * re-scored keeps its id, so capability changes are not switches.
 */
internal class ConnectivityDefaultNetworkMonitor(private val context: Context) : DefaultNetworkMonitor {
    override fun start(onDefaultNetwork: (String?) -> Unit): Disposable {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return Disposable {}
        val thread = HandlerThread("playback-net-switch").apply { start() }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onDefaultNetwork(network.idOf())
            override fun onLost(network: Network) = onDefaultNetwork(null)
            // Some OEM builds skip onAvailable on a quick switch; the first capabilities of the new default carry it.
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                onDefaultNetwork(network.idOf())
        }
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.registerDefaultNetworkCallback(callback, Handler(thread.looper))
            } else {
                // API 24-25: no Handler overload; callbacks come on ConnectivityManager's own thread (not Main).
                manager.registerDefaultNetworkCallback(callback)
            }
        }.isSuccess
        if (!registered) {
            thread.quitSafely()
            return Disposable {}
        }
        return Disposable {
            runCatching { manager.unregisterNetworkCallback(callback) }
            thread.quitSafely()
        }
    }

    private fun Network.idOf(): String = networkHandle.toString()
}
