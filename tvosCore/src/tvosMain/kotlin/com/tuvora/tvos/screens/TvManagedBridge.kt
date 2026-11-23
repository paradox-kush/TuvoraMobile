package com.tuvora.tvos.screens

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.servers.ManagedAddonBridge
import com.nuvio.app.features.servers.ManagedBridgeEmptyStateEffects
import com.nuvio.app.features.servers.ManagedBridgeEmptyStatePolicy
import com.nuvio.app.features.servers.managedNoticeText
import com.nuvio.app.features.streams.StreamsEmptyStateReason
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.servers_managed_empty_title
import org.jetbrains.compose.resources.getString

/** What the empty sources list says about Tuvora Connect (B246), as plain text for SwiftUI. */
data class TvManagedBridgeExplanation(val title: String, val message: String)

/**
 * Apple TV's view of the phone's Tuvora Connect empty-state (B246). The decisions are the shared policies
 * (ManagedBridgeEmptyStatePolicy, ManagedBridgeEmptyStateEffects); this object only hands their answers to SwiftUI.
 * No I/O in the view: Retry and the open-time refresh are user events, never timers.
 */
object TvManagedBridge {
    /** The Connect explanation for an empty list, or null when the generic empty state is the honest one. */
    suspend fun explanation(reason: StreamsEmptyStateReason?): TvManagedBridgeExplanation? {
        val notice = ManagedBridgeEmptyStatePolicy.explanation(
            AppFeaturePolicy.addonStreamSourcesEnabled,
            ManagedAddonBridge.notice.value,
            reason,
        ) ?: return null
        return TvManagedBridgeExplanation(
            title = getString(Res.string.servers_managed_empty_title),
            message = getString(managedNoticeText(notice)),
        )
    }

    /** The empty list was just shown: ask the bridge once if it was not ready, and report the state (codes only). */
    suspend fun onShown() = ManagedBridgeEmptyStateEffects.onShown()

    /** The viewer pressed Retry: re-ask the bridge with a fresh exchange. The caller then reloads the sources. */
    suspend fun retry() = ManagedBridgeEmptyStateEffects.retry()
}
