package com.nuvio.app.core.contracts

import com.nuvio.app.features.home.MetaPreview

/** A live channel's display + playback fields, as the registry holds them. */
data class LiveChannelInfo(
    val name: String,
    val logo: String?,
    val poster: String?,
    val streamUrl: String?,
)

/**
 * The title a live launch shows when nothing knows the channel's name. A placeholder for the player
 * chrome — never a channel's name to SAVE: a recent / favourite / re-keyed copy written with it read
 * "Live TV" everywhere afterwards (B64 device pass T3).
 */
const val GENERIC_LIVE_CHANNEL_NAME = "Live TV"

/** Pure: which of several candidate names of one channel is a real one (see [GENERIC_LIVE_CHANNEL_NAME]). */
object LiveChannelNames {
    fun isKnown(name: String?): Boolean = !name.isNullOrBlank() && name.trim() != GENERIC_LIVE_CHANNEL_NAME

    /** The first known name in preference order, or null when none is. */
    fun best(vararg candidates: String?): String? = candidates.firstOrNull(::isKnown)
}

/**
 * Firewall port for the App.kt live-TV launch. The fork resolves live URLs and channel identity;
 * App.kt navigates. No-op default (nulls) so a build without IPTV simply has no live channels —
 * the launch paths short-circuit exactly as they did when the registry returned nothing.
 */
interface LivePlaybackProvider {
    fun accountNameFor(contentId: String): String?
    fun liveStreamUrlFor(contentId: String): String?
    suspend fun liveStreamUrlForAsync(contentId: String): String?
    /**
     * True when resolving [contentId] spends a single-use link (a Stalker `create_link`): the launch must not
     * resolve it ahead of the Live TV screen, whose own resolve is the one this tune plays (B257).
     */
    fun liveLinkIsSingleUse(contentId: String): Boolean = false
    /**
     * name/logo/poster/streamUrl for a channel id: the registered one, else what a saved recent /
     * favourite knows (streamUrl null — resolved at play), or null.
     */
    fun channelInfoFor(contentId: String): LiveChannelInfo?
    /** Home poster for a registered recording (VOD) id, or null. */
    fun recordingPreview(contentId: String): MetaPreview?
}

object LivePlaybackAccess {
    private val noOp = object : LivePlaybackProvider {
        override fun accountNameFor(contentId: String): String? = null
        override fun liveStreamUrlFor(contentId: String): String? = null
        override suspend fun liveStreamUrlForAsync(contentId: String): String? = null
        override fun liveLinkIsSingleUse(contentId: String): Boolean = false
        override fun channelInfoFor(contentId: String): LiveChannelInfo? = null
        override fun recordingPreview(contentId: String): MetaPreview? = null
    }
    private var provider: LivePlaybackProvider? = null

    fun register(p: LivePlaybackProvider) {
        provider = p
    }

    fun current(): LivePlaybackProvider = provider ?: noOp

    fun resetForTest() {
        provider = null
    }
}
