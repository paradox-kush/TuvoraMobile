package com.tuvora.tvos.screens

import com.nuvio.app.core.sources.DeviceSetupSummary
import com.nuvio.app.core.sources.DeviceSourceView
import com.nuvio.app.core.sources.DeviceSourceInputs
import com.nuvio.app.core.sources.DeviceSourcePolicy

/** Swift receives display state, never source references (which may contain configuration secrets). */
data class TvDeviceSourceRow(
    val id: String, val name: String, val kind: String,
    val enabled: Boolean, val accountEnabled: Boolean, val conflict: Boolean,
)

data class TvDeviceSourceState(
    val mode: String = "loading", val ready: Boolean = false,
    val label: String = "Apple TV", val message: String? = null,
    val busy: Boolean = false, val localOnly: Boolean = false,
    val sources: List<TvDeviceSourceRow> = emptyList(),
    val setups: List<DeviceSetupSummary> = emptyList(),
)

object TvDeviceSourcePresentation {
    fun state(view: DeviceSourceView, profile: Int, localOnly: Boolean): TvDeviceSourceState =
        TvDeviceSourceState(
            mode = view.mode, ready = view.ready,
            label = view.disk.binding?.label ?: "Apple TV",
            message = view.message ?: if (view.disk.pending.isNotEmpty()) "Saved on this device · waiting to sync" else null,
            busy = view.busy, localOnly = localOnly, setups = view.setups,
            sources = view.disk.sources[profile].orEmpty().map { source ->
                val pending = view.disk.pending.firstOrNull { it.profile == profile && it.source == source.sourceId }
                val acknowledged = view.disk.values.firstOrNull { it.profileId == profile && it.sourceId == source.sourceId }
                TvDeviceSourceRow(source.sourceId, source.name, source.kind,
                    enabled = source.accountEnabled && DeviceSourcePolicy.effectiveChoice(DeviceSourceInputs(
                        true, true, source.accountEnabled, true,
                        acknowledgedChoice = acknowledged?.enabled, pendingChoice = pending?.desired,
                        hasPendingChoice = pending != null, pendingConflict = pending?.conflict == true)),
                    accountEnabled = source.accountEnabled, conflict = pending?.conflict == true)
            },
        )
}
