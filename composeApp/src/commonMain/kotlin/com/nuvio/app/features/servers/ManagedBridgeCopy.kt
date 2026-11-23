package com.nuvio.app.features.servers

import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.servers_managed_failed_generic
import nuvio.composeapp.generated.resources.servers_managed_failed_none
import nuvio.composeapp.generated.resources.servers_managed_failed_slow
import nuvio.composeapp.generated.resources.servers_managed_not_connected
import nuvio.composeapp.generated.resources.servers_managed_preparing
import org.jetbrains.compose.resources.StringResource

/** The words for a managed-bridge notice. A plain file (no Compose UI) so Compose and Apple TV share it. */
internal fun managedNoticeText(notice: ManagedBridgeNotice): StringResource = when (notice) {
    ManagedBridgeNotice.Preparing -> Res.string.servers_managed_preparing
    ManagedBridgeNotice.NotConnected -> Res.string.servers_managed_not_connected
    is ManagedBridgeNotice.Failed -> when (notice.code) {
        "provisioning_timeout" -> Res.string.servers_managed_failed_slow
        "no_supported_addons", "no_playback_addons" -> Res.string.servers_managed_failed_none
        else -> Res.string.servers_managed_failed_generic
    }
}
