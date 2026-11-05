package com.nuvio.app.features.livetv

import com.nuvio.app.features.epg.EpgMirrorRepository
import com.nuvio.app.features.iptv.CONTENT_TYPE_LIVE
import com.nuvio.app.features.iptv.CatchUpDialectWalk
import com.nuvio.app.features.iptv.CatchUpEpgRepository
import com.nuvio.app.features.iptv.EpgSourceLadder
import com.nuvio.app.features.iptv.CatchUpWinnerStore
import com.nuvio.app.features.iptv.IptvClient
import com.nuvio.app.features.iptv.IptvPanelGuard
import com.nuvio.app.features.iptv.CatchUpNextProgramme
import com.nuvio.app.features.iptv.M3uCatchUpInfo
import com.nuvio.app.features.iptv.M3uReplayContinuation
import com.nuvio.app.features.iptv.M3uReplayWalk
import com.nuvio.app.features.iptv.XtreamCatchUp
import com.nuvio.app.features.iptv.SOURCE_TYPE_M3U_FILE
import com.nuvio.app.features.iptv.SOURCE_TYPE_M3U_URL
import com.nuvio.app.features.iptv.content.IptvContentDb
import com.nuvio.app.features.iptv.epg.XmltvClient
import com.nuvio.app.features.iptv.XtreamAccount
import com.nuvio.app.features.iptv.XtreamHubRepository
import com.nuvio.app.features.iptv.displayChannelName
import com.nuvio.app.features.iptv.XtreamItemRegistry
import com.nuvio.app.features.iptv.XtreamKind
import com.nuvio.app.features.iptv.XtreamLiveRecents
import com.nuvio.app.features.iptv.XtreamRepository
import com.nuvio.app.features.iptv.XtreamChannel
import com.nuvio.app.features.iptv.XtreamProgram
import com.nuvio.app.features.iptv.allowsCategory
import com.nuvio.app.features.iptv.overlay.IptvHiddenItemsPolicy
import com.nuvio.app.features.iptv.XtreamSearchIndex
import com.nuvio.app.features.iptv.resolveLivePlaybackUrl
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.flow.StateFlow

/** A resolved, ready-to-play live source (post DoH/IP-rewrite). */
data class LiveChannelSource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /**
     * The URL is spent after its first open (a Stalker `create_link`): the engine must not re-open it
     * after a drop; recovery mints a fresh one. See [com.nuvio.app.features.player.LiveLinkReconnectPolicy].
     */
    val singleUseLink: Boolean = false,
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
    /**
     * The channel is pinned in the personalization overlay. Carried on the row so the guide can draw a
     * visible pin marker beside the name — today a pin only FLOATS the channel to the top of its
     * category (see [guideChannels]) with no visible cue. Sourced from the same overlay snapshot that
     * drives the float, so the marker and the float never disagree.
     */
    val pinned: Boolean = false,
)

/**
 * Shared live-TV data access for the docked Live screen: resolves a channel's playable URL (reusing
 * the same registry + DoH path as the old direct-to-player launch) and loads guide channels + EPG
 * windows. Stateless — callers own their own coroutine scope and caches.
 */
object LiveTvData {

    /**
     * Bumped when new guide data lands (XMLTV ingest, mirror sync, manual guide pick). The docked
     * guide re-asks rows that answered empty before it — see GuideDataRefreshPolicy.
     */
    val guideDataGeneration: StateFlow<Long> get() = XtreamHubRepository.guideDataGeneration

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
        return LiveChannelSource(
            url = playback.url,
            headers = playback.headers,
            singleUseLink = sourceTypeOf(contentId) == com.nuvio.app.features.iptv.SOURCE_TYPE_STALKER,
        )
    }

    /**
     * USER-driven Live TV retry (WP6): clears the panel breaker for the channel's account so the
     * re-resolve the user just asked for is never met with a fast-fail. The automatic one-shot
     * re-resolve must NOT call this — only a user action means "contact this host now".
     */
    /** The channel's playlist type for telemetry ("xtream", "m3u_url", …); never a host or login. */
    fun sourceTypeOf(contentId: String): String {
        val accountId = XtreamItemRegistry.parseId(contentId)?.accountId ?: return "unknown"
        return XtreamRepository.uiState.value.accounts.firstOrNull { it.id == accountId }?.sourceType ?: "unknown"
    }

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
        val allChannels = runCatching { XtreamSearchIndex.liveChannelsFor(account) }
            .getOrDefault(emptyList())
        if (allChannels.isEmpty()) return emptyList()
        val channels = visibleGuideChannels(account, allChannels, currentStreamId = parsed.id)

        val currentCategory = channels.firstOrNull { it.streamId.toString() == parsed.id }?.categoryId
        val mapped = channels.map { ch ->
            LiveGuideChannel(
                contentId = XtreamItemRegistry.liveId(account.id, ch.streamId),
                // F10: the cleaned name when the user opted in (identity below stays on the raw one).
                name = account.displayChannelName(ch.name),
                logo = ch.logo,
                streamId = ch.streamId,
                categoryId = ch.categoryId,
                hasArchive = CatchUpEpgRepository.supportsCatchUp(account, ch.m3uCatchUp) && ch.hasArchive,
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
        // Tag pinned onto the row BEFORE ordering so the marker survives displayedByCategory (it only
        // reorders/filters/renames the row objects). The overlay.isEmpty() fast-path above returns base
        // with pinned=false, which is correct — nothing is pinned when there is no overlay.
        val tagged = base.mapIndexed { i, ch ->
            val row = if (com.nuvio.app.features.iptv.overlay.IptvChannelOverlayPolicy.isPinned(overlay, ch.entityId)) {
                ch.copy(pinned = true)
            } else {
                ch
            }
            com.nuvio.app.features.iptv.overlay.IptvChannelOverlayPolicy.Tagged(ch.entityId, i, row)
        }
        // Per-category (BUG #1): the guide is one flat all-account column, so applying the overlay
        // across the whole list floated a channel pinned in category B above category A. Scope the
        // overlay to each category so a pin floats only to the top of its OWN category.
        return com.nuvio.app.features.iptv.overlay.IptvChannelOverlayPolicy.displayedByCategory(
            tagged, overlay, categoryOf = { it.categoryId }, withName = { row, newName -> row.copy(name = newName) },
        )
    }

    /**
     * B65: the guide drops channels whose category is hidden (website or device) or switched off in the
     * playlist's in-app category settings, as the hub already drops the category itself. The channel
     * being watched always stays so the guide still opens on it. Category names are read only when the
     * overlay hides some category, because the overlay keys categories by name.
     */
    private suspend fun visibleGuideChannels(
        account: XtreamAccount,
        channels: List<XtreamChannel>,
        currentStreamId: String,
    ): List<XtreamChannel> {
        val overlay = runCatching {
            com.nuvio.app.features.iptv.overlay.IptvOverlayStore
                .snapshot(com.nuvio.app.features.profiles.ProfileRepository.activeProfileId)
                .categories
        }.getOrDefault(emptyMap())
        val hiddenIds = if (overlay.values.any { it.hidden }) {
            IptvHiddenItemsPolicy.hiddenCategoryIds(account.id, CONTENT_TYPE_LIVE, com.nuvio.app.features.iptv.overlay.IptvHiddenItems.categoryNames(account, CONTENT_TYPE_LIVE), overlay)
        } else {
            emptySet()
        }
        val visible = IptvHiddenItemsPolicy.guideChannels(
            channels,
            hiddenCategoryIds = hiddenIds,
            allowedBySelection = { account.allowsCategory(CONTENT_TYPE_LIVE, it) },
            categoryOf = { it.categoryId },
        )
        if (visible.size == channels.size) return channels
        val keep = visible.toHashSet()
        return channels.filter { it in keep || it.streamId.toString() == currentStreamId }
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
        // B10: warm the account's whole guide too — the docked guide had no STORE rung at all, so
        // it paid a get_short_epg per channel while the hub read the same guide off disk.
        com.nuvio.app.features.iptv.epg.XmltvClient.warm(account)
        val nowMs = TraktPlatformClock.nowEpochMs()
        return EpgSourceLadder.resolveAndRemember(
            memory = EpgSourceLadder.sessionMemory,
            accountId = account.id,
            streamId = streamId,
            nowMs = nowMs,
            // F14: the user's own pick of guide channel for this channel, if any.
            manual = EpgSourceLadder.ManualResolver { _, sid, _ ->
                runCatching { com.nuvio.app.features.iptv.epg.XmltvClient.manualNowNext(account, sid, limit) }.getOrNull()
            },
            store = {
                runCatching {
                    com.nuvio.app.features.iptv.epg.XmltvClient.storedNowNext(account, streamId, limit)
                }.getOrDefault(emptyList())
            },
            // null = the ask FAILED (see EpgSourceLadder.Source.UNAVAILABLE) — the hub path has made
            // this distinction since 2026-08-18; the docked guide still collapsed it into "no guide".
            provider = {
                runCatching {
                    IptvClient.forAccount(account).shortEpg(account, streamId, limit).getOrNull()
                }.getOrNull()
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
        // An M3U catch-up channel's past is its XMLTV guide (kept up to catchup-days); an M3U channel without
        // catch-up has no history and keeps the now-and-next ladder it always had.
        if (account.sourceType == SOURCE_TYPE_M3U_URL || account.sourceType == SOURCE_TYPE_M3U_FILE) {
            if (m3uCatchUpOf(account, streamId) == null) return emptyList()
            return runCatching { XmltvClient.storedWindow(account, streamId, fromMs, toMs) }.getOrDefault(emptyList())
        }
        return CatchUpEpgRepository.window(account, streamId, fromMs, toMs)
    }

    /** The channel's own catch-up from the content database; null for a channel without (or a missing row). */
    private suspend fun m3uCatchUpOf(account: XtreamAccount, streamId: Int): M3uCatchUpInfo? =
        runCatching { IptvContentDb.channelRow(account.id, streamId)?.catchUp }.getOrNull()

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
            // Catch-up never fails over itself — it replays from the playlist's ACTIVE server (Step 0.3).
            baseUrl = com.nuvio.app.features.iptv.PlaylistServerFailover.activeAccount(account).baseUrl,
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
    suspend fun catchUpSource(contentId: String, url: String, singleUse: Boolean = false): LiveChannelSource {
        val dnsProvider = XtreamItemRegistry.dnsProviderFor(contentId)
        val playback = resolveLivePlaybackUrl(url, dnsProvider)
        return LiveChannelSource(url = playback.url, headers = playback.headers, singleUseLink = singleUse)
    }

    /** Whether [contentId] belongs to a Stalker portal (its replays are minted per programme, never walked). */
    fun isStalkerPortal(contentId: String): Boolean = sourceTypeOf(contentId) == com.nuvio.app.features.iptv.SOURCE_TYPE_STALKER

    /**
     * One fresh archive create_link for the programme starting at [startMs] on [contentId]'s channel: the portal's
     * own EPG programme id is read from the stored guide, then minted. Null = no programme id (nothing to ask the
     * portal for). Every call mints a NEW single-use link: this is also what Retry runs.
     */
    suspend fun stalkerArchiveLink(contentId: String, startMs: Long): com.nuvio.app.features.iptv.stalker.StalkerArchiveAnswer? {
        val (account, streamId) = accountFor(contentId) ?: return null
        val programmeId = CatchUpEpgRepository.window(account, streamId, startMs, startMs + 1L)
            .firstOrNull { it.startMs == startMs }?.programmeId
            ?: return null
        return com.nuvio.app.features.iptv.stalker.StalkerClient.createArchiveLink(account, programmeId)
    }

    /** The learned-winner memory the walks are constructed with (Xtream dialects and M3U link forms share it). */
    internal fun winnerStore(): CatchUpWinnerStore = CatchUpWinnerStore(
        accountOf = { id -> XtreamRepository.uiState.value.accounts.firstOrNull { it.id == id } },
        update = { id, edit -> XtreamRepository.updateOptions(id) { edit(it) } },
    )

    fun winnerMemory(): CatchUpDialectWalk.WinnerMemory = winnerStore()

    /** Whether [contentId] belongs to a URL/file M3U playlist (replays are built from the playlist's own catch-up). */
    fun isM3uPlaylist(contentId: String): Boolean =
        sourceTypeOf(contentId).let { it == SOURCE_TYPE_M3U_URL || it == SOURCE_TYPE_M3U_FILE }

    /**
     * The M3U replay ask for one programme on [contentId]'s channel, or null when the channel has no catch-up
     * (or the playlist is gone). The channel's own live line and `catchup-*` come from the content database;
     * the offset is the playlist's catch-up correction. Run it through [M3uReplayOpen.run] so it is bounded.
     */
    suspend fun m3uReplayRequest(contentId: String, startMs: Long, endMs: Long, nowMs: Long): M3uReplayWalk.Request? {
        val (account, streamId) = accountFor(contentId) ?: return null
        if (!account.availableOnDevice) return null
        val row = IptvContentDb.channelRow(account.id, streamId) ?: return null
        val info = row.catchUp ?: return null
        if (!CatchUpEpgRepository.supportsCatchUp(account, info)) return null
        return M3uReplayWalk.Request(
            accountId = account.id,
            info = info,
            liveUrl = row.url,
            programmeStartMs = startMs,
            programmeEndMs = endMs,
            nowMs = nowMs,
            offsetMs = XtreamCatchUp.replayOffsetMs(null, account.catchUpTimeCorrectionMinutes) ?: 0L,
        )
    }

    /** The guide row covering [atMs] on this channel, else the next one to start - what a continuing replay moves its title to. */
    suspend fun replayRowAt(contentId: String, atMs: Long): M3uReplayContinuation.Row? {
        val rows = historyProgrammes(contentId, atMs - 60_000L, atMs + 6 * 60 * 60_000L)
            .map { M3uReplayContinuation.Row(it.title, it.startMs, it.endMs) }
        return CatchUpNextProgramme.pick(rows, atMs)
    }
}

/** F03 — Move earlier / later for a pinned channel in the phone guide (null when it cannot move that way). */
internal object LiveGuidePinnedMoves {
    fun move(
        shown: List<LiveGuideChannel>,
        overlay: Map<String, com.nuvio.app.features.iptv.overlay.ChannelOverlay>,
        channel: LiveGuideChannel,
        delta: Int,
    ): (() -> Unit)? {
        val pinned = shown.map { it.entityId }.filter { it.isNotBlank() && overlay[it]?.pinned == true }.distinct()
        val entity = channel.entityId.takeIf { it.isNotBlank() } ?: return null
        val from = pinned.indexOf(entity)
        if (from < 0) return null
        val writes = com.nuvio.app.features.iptv.PinnedChannelOrder.move(pinned, entity, from + delta)
        if (writes.isEmpty()) return null
        val playlistId = com.nuvio.app.features.iptv.XtreamItemRegistry.parseId(channel.contentId)?.accountId
        return { com.nuvio.app.features.iptv.overlay.IptvOverlayRepository.setChannelPositions(playlistId, writes) }
    }
}
