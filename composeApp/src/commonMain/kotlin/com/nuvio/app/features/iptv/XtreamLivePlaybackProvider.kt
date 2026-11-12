package com.nuvio.app.features.iptv

import com.nuvio.app.core.contracts.LiveChannelInfo
import com.nuvio.app.core.contracts.LiveChannelNames
import com.nuvio.app.core.contracts.LivePlaybackProvider
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository

/**
 * Fork-side [LivePlaybackProvider]: the live-TV launch resolution that used to live inline in App.kt.
 * Pure delegation to XtreamItemRegistry — behaviour identical to the code it replaced.
 */
internal object XtreamLivePlaybackProvider : LivePlaybackProvider {
    override fun accountNameFor(contentId: String): String? =
        XtreamItemRegistry.accountNameFor(contentId)

    override fun liveStreamUrlFor(contentId: String): String? =
        XtreamItemRegistry.liveStreamUrlFor(contentId)

    override suspend fun liveStreamUrlForAsync(contentId: String): String? =
        XtreamItemRegistry.liveStreamUrlForAsync(contentId)

    override fun liveLinkIsSingleUse(contentId: String): Boolean {
        val accountId = XtreamItemRegistry.parseId(contentId)?.accountId ?: return false
        return XtreamRepository.uiState.value.accounts.firstOrNull { it.id == accountId }?.sourceType == SOURCE_TYPE_STALKER
    }

    override fun channelInfoFor(contentId: String): LiveChannelInfo? {
        val registered = XtreamItemRegistry.get(contentId)?.let {
            LiveChannelInfo(name = it.name, logo = it.logo, poster = it.poster, streamUrl = it.streamUrl)
        }
        if (registered != null) return registered
        XtreamLiveRecents.ensureLoaded()
        return LiveChannelInfoFallback.of(
            registered = null,
            favourite = LibraryRepository.localItems.value.firstOrNull { it.id == contentId },
            recent = XtreamLiveRecents.recents.value.firstOrNull { it.contentId == contentId },
        )
    }

    override fun recordingPreview(contentId: String): MetaPreview? =
        XtreamItemRegistry.get(contentId)?.toMetaPreview()
}

/**
 * B64 device pass T3 — a live channel's launch info when the in-memory registry has not seen it (a
 * Favorites / Recent card of a channel whose category was never browsed this session — after the
 * re-key, every saved channel): what the saved favourite / recent knows, so the launch is not titled
 * (and the recent not re-recorded as) the generic "Live TV". Pure.
 */
internal object LiveChannelInfoFallback {
    fun of(registered: LiveChannelInfo?, favourite: LibraryItem?, recent: XtreamLiveRecent?): LiveChannelInfo? {
        if (registered != null) return registered
        val name = LiveChannelNames.best(favourite?.name, recent?.name) ?: return null
        return LiveChannelInfo(name = name, logo = favourite?.logo ?: recent?.logo, poster = favourite?.poster, streamUrl = null)
    }
}
