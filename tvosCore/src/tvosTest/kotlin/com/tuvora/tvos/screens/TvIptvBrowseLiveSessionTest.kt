package com.tuvora.tvos.screens

import com.nuvio.app.features.player.PlayerLaunch
import com.tuvora.tvos.player.TvResolvedSource
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every Apple TV IPTV live launch goes through [TvIptvBrowse.liveSession]. Its re-resolver was passed
 * as a trailing lambda, which Kotlin binds to the constructor's LAST function parameter
 * (`vodReresolve`), so live sessions had no live re-resolver at all: a dropped Stalker channel replayed
 * its spent create_link (401) and Retry did the same (simulator, mock portal, 11-04).
 */
class TvIptvBrowseLiveSessionTest {

    private val launch = PlayerLaunch(
        profileId = 1,
        title = "Channel",
        sourceUrl = "http://portal.invalid/stream/S1/index.m3u8",
        streamTitle = "Channel",
        streamType = "live",
        providerName = "IPTV",
        providerAddonId = "xtream",
        contentType = "live",
        videoId = "xtream:stalker|http://portal.invalid|***:live:1",
        parentMetaId = "xtream:stalker|http://portal.invalid|***:live:1",
        parentMetaType = "tv",
    )

    @Test
    fun `a live channel session can mint a fresh link`() {
        val session = TvIptvBrowse.liveSession(launch, singleUseLink = true) {
            TvResolvedSource("http://portal.invalid/stream/S2/index.m3u8", emptyMap())
        }
        assertTrue(session.remintsLiveLink, "the live re-resolver must reach the session's live reconnect, not its VOD retry")
    }
}
