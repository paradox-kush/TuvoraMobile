package com.nuvio.app.features.livetv

import com.nuvio.app.features.epg.EpgMirrorRepository
import com.nuvio.app.features.iptv.CatchUpDialectWalk
import com.nuvio.app.features.iptv.CatchUpEpgRepository
import com.nuvio.app.features.iptv.EpgSourceLadder
import com.nuvio.app.features.iptv.CatchUpWinnerStore
import com.nuvio.app.features.iptv.IptvClient
import com.nuvio.app.features.iptv.IptvPanelGuard
import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.XtreamItemRegistry
import com.nuvio.app.features.iptv.XtreamKind
import com.nuvio.app.features.iptv.XtreamLiveRecents
import com.nuvio.app.features.iptv.XtreamRepository
import com.nuvio.app.features.iptv.XtreamProgram
import com.nuvio.app.features.iptv.XtreamSearchIndex
import com.nuvio.app.features.iptv.resolveLivePlaybackUrl
import com.nuvio.app.features.trakt.TraktPlatformClock

/** A resolved, ready-to-play live source (post DoH/IP-rewrite). */
data class LiveChannelSource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

/** A single channel row in the guide. */
data class LiveGuideChannel(
    val contentId: String,
    val name: String,
    val logo: String?,
    val streamId: Int,
    val categoryId: String?,
    /**
     * The panel's `tv_archive` flag: this channel keeps a replay archive.
     *
     * Carried on the row because it is the CHANNEL-level signal the guide shows — a small replay
     * glyph beside the name — and because [com.nuvio.app.features.iptv.XtreamCatchUp.actionFor]
     * needs it per cell. It is rare: three real panels measured 44 of 26,430, 36 of 11,429 and
     * 1,014 of 57,131 channels, so the guide must surface it per channel and never imply catch-up
     * is a mode the whole playlist is in.
     */
    val hasArchive: Boolean = false,
    /** The panel's per-channel window (days); 0 = panel silent → the permissive rules apply. */
    val catchUpDays: Int = 0,
    /** The channel's durable canon-v1 identity — the key the personalization overlay + native toggle use. */
    val entityId: String = "",
)

/**
 * Shared live-TV data access for the docked Live screen: resolves a channel's playable URL (reusing
 * the same registry + DoH path as the old direct-to-player launch) and loads guide channels + EPG
 * windows. Stateless — callers own their own coroutine scope and caches.
 */
object LiveTvData {

    /**
     * Resolves the playable source for a live channel id, mirroring `App.launchLiveChannel`:
     * registry lookup (sync then async for M3U/Stalker) → DoH-resolved live URL. Also records the
     * channel to the live "recently watched" LRU. Returns null if the URL can't be resolved.
     *
     * [forceMint] = this resolve is a RETRY after a playback failure: a Stalker static-cmd verdict
     * would replay the dead URL, so the retry demands a fresh create_link instead.
     */
    suspend fun resolveSource(contentId: String, name: String, logo: String?, forceMint: Boolean = false): LiveChannelSource? {
        val immediate = XtreamItemRegistry.liveStreamUrlFor(contentId)
            ?: XtreamItemRegistry.liveStreamUrlForAsync(contentId, forceMint)
            ?: return null
        val dnsProvider = XtreamItemRegistry.dnsProviderFor(contentId)
        val playback = resolveLivePlaybackUrl(immediate, dnsProvider)
        XtreamLiveRecents.record(contentId, name, logo)
        return LiveChannelSource(url = playback.url, headers = playback.headers)
    }

    /**
     * USER-driven Live TV retry (WP6): clears the panel breaker for the channel's account so the
     * re-resolve the user just asked for is never met with a fast-fail. The automatic one-shot
     * re-resolve must NOT call this — only a user action means "contact this host now".
     */
    fun resetPanelGuard(contentId: String) {
        val parsed = XtreamItemRegistry.parseId(contentId) ?: return
        XtreamRepository.uiState.value.accounts.firstOrNull { it.id == parsed.accountId }
            ?.let { IptvPanelGuard.resetForAccount(it) }
    }

    /**
     * Live channels for the account that owns [contentId], for the guide's channel column. The
     * current channel's own category is surfaced first so the guide opens on relevant neighbours.
     */
    suspend fun guideChannels(contentId: String): List<LiveGuideChannel> {
        val parsed = XtreamItemRegistry.parseId(contentId) ?: return emptyList()
        if (parsed.kind != XtreamKind.LIVE) return emptyList()
        XtreamRepository.ensureLoaded()
        val account = XtreamRepository.uiState.value.accounts
            .firstOrNull { it.id == parsed.accountId } ?: return emptyList()
        val channels = runCatching { XtreamSearchIndex.liveChannelsFor(account) }
            .getOrDefault(emptyList())
        if (channels.isEmpty()) return emptyList()

        val currentCategory = channels.firstOrNull { it.streamId.toString() == parsed.id }?.categoryId
        val supportsCatchUp = CatchUpEpgRepository.supportsCatchUp(account)
        val mapped = channels.map { ch ->
            LiveGuideChannel(
                contentId = XtreamItemRegistry.liveId(account.id, ch.streamId),
                name = ch.name,
                logo = ch.logo,
                streamId = ch.streamId,
                categoryId = ch.categoryId,
                hasArchive = supportsCatchUp && ch.hasArchive,
                catchUpDays = ch.catchUpDays,
                entityId = com.nuvio.app.features.iptv.identity.IptvIdentity.entityId(account.id, ch.name, ch.epgChannelId),
            )
        }
        // The channel column is the fully-materialized surface (the whole account, no paging), so the
        // personalization overlay applies in full here: hidden dropped, pinned/reordered, renamed. The
        // current channel's category is surfaced first as the provider baseline the overlay orders on top of.
        val base = if (currentCategory != null) {
            mapped.sortedByDescending { it.categoryId == currentCategory }
        } else {
            mapped
        }
        val overlay = try {
            com.nuvio.app.features.iptv.overlay.IptvOverlayStore
                .snapshot(com.nuvio.app.features.profiles.ProfileRepository.activeProfileId)
                .channels
        } catch (e: Throwable) {
            // Overlay store momentarily unavailable (e.g. first cold open) — show the unfiltered guide
            // rather than blanking it; the next recompose re-applies once the store is ready.
            return base
        }
        if (overlay.isEmpty()) return base
        val tagged = base.mapIndexed { i, ch ->
            com.nuvio.app.features.iptv.overlay.IptvChannelOverlayPolicy.Tagged(ch.entityId, i, ch)
        }
        return com.nuvio.app.features.iptv.overlay.IptvChannelOverlayPolicy.displayed(
            tagged, overlay, honorOrder = true, withName = { row, newName -> row.copy(name = newName) },
        )
    }

    /**
     * Programme window for a channel, resolved through [EpgSourceLadder]: (future) manual mapping
     * → the playlist's own short EPG if its rows pass the sanity gate → the mirrored canonical
     * EPG → nothing. The answering rung is remembered per (account, channel) for the session, so
     * a channel the mirror feeds doesn't re-ask the panel on every guide window.
     *
     * The old `.ifEmpty { mirror }` fallback lives on inside the ladder as the empty case, but
     * present-and-garbage no longer beats absent: rows that bracket nothing (the wa12 shape the
     * epoch-skew correction could not prove) fall to the mirror instead of suppressing it.
     */
    suspend fun programmes(contentId: String, limit: Int = 8): List<XtreamProgram> {
        val parsed = XtreamItemRegistry.parseId(contentId) ?: return emptyList()
        if (parsed.kind != XtreamKind.LIVE) return emptyList()
        val streamId = parsed.id.toIntOrNull() ?: return emptyList()
        val account = XtreamRepository.uiState.value.accounts
            .firstOrNull { it.id == parsed.accountId } ?: return emptyList()
        // Stalker warms its lineup + the ONE bulk get_epg_info on the client's own scope, so the
        // docked guide shows now/next as you scroll rather than only after settling (no-op for
        // Xtream/M3U — they warm via XmltvClient). See StalkerClient.warm.
        IptvClient.forAccount(account).warm(account)
        val nowMs = TraktPlatformClock.nowEpochMs()
        return EpgSourceLadder.resolveAndRemember(
            memory = EpgSourceLadder.sessionMemory,
            accountId = account.id,
            streamId = streamId,
            nowMs = nowMs,
            manual = null,   // the manual-mapping seam — see [EpgSourceLadder.ManualResolver]
            provider = {
                runCatching {
                    IptvClient.forAccount(account).shortEpg(account, streamId, limit).getOrDefault(emptyList())
                }.getOrDefault(emptyList())
            },
            mirror = {
                runCatching {
                    EpgMirrorRepository.nowNextProgrammes(account.id, streamId, nowMs)
                }.getOrDefault(emptyList())
            },
        ).programmes
    }

    // --- Catch-up ------------------------------------------------------------------------------

    /** The playlist that owns [contentId], or null when it isn't a live Xtream channel. */
    private fun accountFor(contentId: String): Pair<XtreamAccount, Int>? {
        val parsed = XtreamItemRegistry.parseId(contentId) ?: return null
        if (parsed.kind != XtreamKind.LIVE) return null
        val streamId = parsed.id.toIntOrNull() ?: return null
        val account = XtreamRepository.uiState.value.accounts
            .firstOrNull { it.id == parsed.accountId } ?: return null
        return account to streamId
    }

    /** Whether this channel's playlist can serve catch-up at all. */
    fun supportsCatchUp(contentId: String): Boolean =
        accountFor(contentId)?.let { (account, _) -> CatchUpEpgRepository.supportsCatchUp(account) } == true

    /**
     * Pulls this channel's full guide table onto disk if it isn't already there.
     *
     * The FOCUSED channel only — see [CatchUpEpgRepository] for why the guide never prefetches a
     * page's worth of these.
     */
    suspend fun ensureHistory(contentId: String) {
        val (account, streamId) = accountFor(contentId) ?: return
        CatchUpEpgRepository.ensureHistory(account, streamId)
    }

    /** Stored programmes overlapping a guide window — the only source of PAST rows. */
    suspend fun historyProgrammes(contentId: String, fromMs: Long, toMs: Long): List<XtreamProgram> {
        val (account, streamId) = accountFor(contentId) ?: return emptyList()
        return CatchUpEpgRepository.window(account, streamId, fromMs, toMs)
    }

    /** The whole synopsis for the programme sheet — truncated everywhere else. */
    suspend fun programmeDescription(contentId: String, startMs: Long): String? {
        val (account, streamId) = accountFor(contentId) ?: return null
        return CatchUpEpgRepository.fullDescription(account, streamId, startMs)
    }

    /**
     * Builds the replay ask for [CatchUpDialectWalk], or null when this channel can't be replayed.
     *
     * The panel's measured clock offset (plus any manual correction) rides along: panels interpret
     * the `start` string in THEIR timezone, so a panel in another zone replays hours off what the
     * guide showed.
     */
    suspend fun catchUpRequest(contentId: String, startMs: Long, endMs: Long): CatchUpDialectWalk.Request? {
        val (account, streamId) = accountFor(contentId) ?: return null
        if (!CatchUpEpgRepository.supportsCatchUp(account)) return null
        val facts = CatchUpEpgRepository.panelFacts(account)
        return CatchUpDialectWalk.Request(
            accountId = account.id,
            baseUrl = account.baseUrl,
            username = account.username,
            password = account.password,
            streamId = streamId,
            startMs = startMs,
            endMs = endMs,
            allowedOutputFormats = facts.allowedOutputFormats,
            preferM3u8 = account.catchUpPreferM3u8,
            serverOffsetMs = facts.serverOffsetMs,
        )
    }

    /**
     * Runs a candidate replay URL through the same DoH/IP-rewrite path live channels take.
     *
     * Panels 302-redirect catch-up to token-bearing edge hosts, so the resolution has to behave
     * exactly like the live one rather than handing the raw URL to the engine.
     */
    suspend fun catchUpSource(contentId: String, url: String): LiveChannelSource {
        val dnsProvider = XtreamItemRegistry.dnsProviderFor(contentId)
        val playback = resolveLivePlaybackUrl(url, dnsProvider)
        return LiveChannelSource(url = playback.url, headers = playback.headers)
    }

    /** The learned-winner memory the walk is constructed with. */
    fun winnerMemory(): CatchUpDialectWalk.WinnerMemory = CatchUpWinnerStore(
        accountOf = { id -> XtreamRepository.uiState.value.accounts.firstOrNull { it.id == id } },
        update = { id, edit -> XtreamRepository.updateOptions(id) { edit(it) } },
    )
}
