package com.nuvio.app.features.player

import com.nuvio.app.features.common.lifecycle.Disposable
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Port: the device's default network. [start] reports an opaque id for it (null = no network) — the current one
 * first, then each change — until the returned handle is disposed. Android: `registerDefaultNetworkCallback`;
 * Apple: `NWPathMonitor`.
 */
fun interface DefaultNetworkMonitor {
    fun start(onDefaultNetwork: (String?) -> Unit): Disposable
}

/** Port: the platform's shared playback HTTP stack (Android: the playback OkHttp clients; Apple: nothing pooled). */
interface PlaybackConnectionPool {
    /** Close pooled idle keep-alive connections, so the next request opens on the current network. */
    fun releaseIdle()

    /** Abort the reads in flight; an engine with a load retry re-opens them on the current network. */
    fun cancelInFlight()

    object None : PlaybackConnectionPool {
        override fun releaseIdle() = Unit
        override fun cancelInFlight() = Unit
    }
}

/**
 * One per process. Each live player [register]s while it holds a stream; the default-network listener runs only
 * while at least one is registered (no listener with no player), and each change is decided by
 * [PlaybackNetworkSwitchPolicy]. On [PlaybackNetworkSwitchPolicy.Action.RECONNECT] the shared HTTP stack is
 * flushed once, then every player's own re-open runs (libmpv reloads; Media3 needs nothing more than the flush).
 *
 * Thread-safe: the platform listener calls back on its own thread. Callbacks run outside the lock.
 */
class PlaybackNetworkSwitchCoordinator(
    private val monitor: DefaultNetworkMonitor,
    private val pool: PlaybackConnectionPool,
    private val nowMs: () -> Long,
) {
    private class Client(val singleUseLink: Boolean, val onReconnect: () -> Unit)

    private val lock = SynchronizedObject()
    private val clients = mutableListOf<Client>()
    private var generation = 0
    private var monitoring = false
    private var monitorHandle: Disposable? = null
    private var lastNetwork: String? = null
    private var lastReconnectAtMs: Long? = null

    /**
     * [singleUseLink]: the stream must never be re-opened by this path. [onReconnect]: re-open the stream on the
     * current network, keeping position (must not block; engines queue it on their own thread).
     */
    fun register(singleUseLink: Boolean, onReconnect: () -> Unit): Disposable {
        val client = Client(singleUseLink, onReconnect)
        val startGeneration = synchronized(lock) {
            clients += client
            if (monitoring) {
                null
            } else {
                monitoring = true
                lastNetwork = null
                lastReconnectAtMs = null
                ++generation
            }
        }
        if (startGeneration != null) {
            val handle = monitor.start { network -> onDefaultNetwork(startGeneration, network) }
            val keep = synchronized(lock) {
                (monitoring && generation == startGeneration).also { if (it) monitorHandle = handle }
            }
            if (!keep) handle.dispose()
        }
        return Disposable { unregister(client) }
    }

    private fun unregister(client: Client) {
        val handle = synchronized(lock) {
            if (clients.remove(client) && clients.isEmpty()) {
                monitoring = false
                generation++
                monitorHandle.also { monitorHandle = null }
            } else {
                null
            }
        }
        handle?.dispose()
    }

    private fun onDefaultNetwork(fromGeneration: Int, network: String?) {
        val now = nowMs()
        val decided = synchronized(lock) {
            if (!monitoring || generation != fromGeneration) return@synchronized null
            val action = PlaybackNetworkSwitchPolicy.decide(
                lastNetwork = lastNetwork,
                currentNetwork = network,
                playbackActive = clients.isNotEmpty(),
                anySingleUseLink = clients.any { it.singleUseLink },
                lastReconnectAtMs = lastReconnectAtMs,
                nowMs = now,
            )
            if (network != null) lastNetwork = network
            if (action == PlaybackNetworkSwitchPolicy.Action.RECONNECT) lastReconnectAtMs = now
            action to clients.toList()
        } ?: return
        val (action, targets) = decided
        when (action) {
            PlaybackNetworkSwitchPolicy.Action.NONE -> Unit
            PlaybackNetworkSwitchPolicy.Action.RELEASE_IDLE -> runCatching { pool.releaseIdle() }
            PlaybackNetworkSwitchPolicy.Action.RECONNECT -> {
                runCatching { pool.releaseIdle() }
                runCatching { pool.cancelInFlight() }
                // A player torn down mid-switch must not stop the others moving.
                targets.forEach { runCatching { it.onReconnect() } }
            }
        }
    }
}
