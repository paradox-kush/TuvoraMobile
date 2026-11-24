package com.nuvio.app.features.player

import com.nuvio.app.features.common.lifecycle.Disposable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.Network.nw_interface_get_name
import platform.Network.nw_path_enumerate_interfaces
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_queue_create
import kotlin.time.TimeSource

/**
 * Apple's [PlaybackNetworkSwitchCoordinator] (iPhone/iPad, and Apple TV through tvosCore). libmpv opens its own
 * BSD sockets, so there is no shared pool to flush; each player re-opens its own stream. One per process.
 */
object ApplePlaybackNetworkSwitch {
    private val clockOrigin = TimeSource.Monotonic.markNow()

    val coordinator: PlaybackNetworkSwitchCoordinator by lazy {
        PlaybackNetworkSwitchCoordinator(
            monitor = PathDefaultNetworkMonitor(),
            pool = PlaybackConnectionPool.None,
            nowMs = { clockOrigin.elapsedNow().inWholeMilliseconds },
        )
    }
}

/**
 * The default path's preferred interface, from `NWPathMonitor` (Network framework). The monitor reports the current
 * path on start, then every change, on a private serial queue (never Main). The id is the name of the path's first
 * interface — the one new connections use — so Wi-Fi handing over to cellular (`en0` -> `pdp_ip0`) is a change even
 * while Wi-Fi stays associated; an unsatisfied path is none.
 */
@OptIn(ExperimentalForeignApi::class)
internal class PathDefaultNetworkMonitor : DefaultNetworkMonitor {
    override fun start(onDefaultNetwork: (String?) -> Unit): Disposable {
        val monitor = nw_path_monitor_create() ?: return Disposable {}
        nw_path_monitor_set_update_handler(monitor) { path ->
            onDefaultNetwork(if (path != null && nw_path_get_status(path) == nw_path_status_satisfied) path.firstInterfaceName() else null)
        }
        nw_path_monitor_set_queue(monitor, dispatch_queue_create("com.tuvora.playback-net-switch", null))
        nw_path_monitor_start(monitor)
        return Disposable { nw_path_monitor_cancel(monitor) }
    }

    private fun platform.darwin.NSObject.firstInterfaceName(): String? {
        var name: String? = null
        nw_path_enumerate_interfaces(this) { iface ->
            name = iface?.let { nw_interface_get_name(it)?.toKString() }
            false // the first interface is the preferred one; stop there
        }
        return name ?: "satisfied"
    }
}
