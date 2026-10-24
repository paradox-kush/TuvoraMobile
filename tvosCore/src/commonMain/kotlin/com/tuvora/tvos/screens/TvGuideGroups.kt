package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.overlay.CustomGroup
import com.nuvio.app.features.iptv.overlay.IptvCustomGroupPolicy
import com.nuvio.app.features.iptv.overlay.OverlaySnapshot
import com.nuvio.app.features.livetv.LiveGuideChannel

data class TvGuideGroup(val id: String, val name: String, val channels: List<LiveGuideChannel>)

object TvGuideGroups {
    internal fun rows(accountId: String, lineup: List<LiveGuideChannel>, overlay: OverlaySnapshot): List<TvGuideGroup> {
        val byEntity = lineup.filter { it.entityId.isNotBlank() }.associateBy { it.entityId }
        return IptvCustomGroupPolicy.groupsFor(accountId, "live", overlay.groups)
            .sortedBy { it.position }.map { group ->
                TvGuideGroup(IptvCustomGroupPolicy.rowId(group.id), group.name,
                    IptvCustomGroupPolicy.members(group, byEntity, overlay.channels))
            }
    }
}
