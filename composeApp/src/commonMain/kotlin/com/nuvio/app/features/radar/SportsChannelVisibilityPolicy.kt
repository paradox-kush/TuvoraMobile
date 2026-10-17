package com.nuvio.app.features.radar

import com.nuvio.app.features.iptv.CONTENT_TYPE_LIVE
import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.XtreamChannel
import com.nuvio.app.features.iptv.allowsCategory
import com.nuvio.app.features.iptv.typeEnabled
import com.nuvio.app.features.iptv.identity.IptvIdentity
import com.nuvio.app.features.iptv.overlay.IptvHiddenItemsPolicy
import com.nuvio.app.features.iptv.overlay.OverlaySnapshot

/** Apply the active profile's visibility to raw lineups before any Sports evidence or probes. */
internal object SportsChannelVisibilityPolicy {
    fun filter(
        account: XtreamAccount,
        channels: List<XtreamChannel>,
        categories: List<IptvHiddenItemsPolicy.NamedCategory>,
        overlay: OverlaySnapshot,
    ): List<XtreamChannel> {
        if (!account.enabled || !account.typeEnabled(CONTENT_TYPE_LIVE)) return emptyList()
        val selection = account.categorySelections.live
        if (selection != null && selection.isEmpty()) return emptyList()
        val selected = if (selection == null) channels else {
            val names = categories.associate { it.id to it.name }
            channels.filter { account.allowsCategory(CONTENT_TYPE_LIVE, it.categoryId, names[it.categoryId]) }
        }
        return IptvHiddenItemsPolicy.visibleHits(
            items = selected,
            hiddenCategoryIds = IptvHiddenItemsPolicy.hiddenCategoryIds(
                account.id, CONTENT_TYPE_LIVE, categories, overlay.categories,
            ),
            channelOverlay = overlay.channels,
            categoryOf = { it.categoryId },
            entityOf = { IptvIdentity.entityId(account.id, it.name, it.epgChannelId) },
        )
    }

    fun needsCategoryNames(account: XtreamAccount, overlay: OverlaySnapshot): Boolean =
        overlay.categories.values.any { it.hidden } || !account.categorySelections.live.isNullOrEmpty()
}
