package com.tuvora.tvos.screens

import com.nuvio.app.core.contracts.LivePlaybackAccess
import com.nuvio.app.features.iptv.CatchUpDialectWalk
import com.nuvio.app.features.iptv.CatchUpPlayback
import com.nuvio.app.features.iptv.M3uReplayWalk
import com.nuvio.app.features.iptv.ReplayOpenFence
import com.nuvio.app.features.iptv.XtreamProgram
import com.nuvio.app.features.livetv.GuideWindowSource
import com.nuvio.app.features.livetv.LiveGuideChannel
import com.nuvio.app.features.livetv.LiveTvData
import com.nuvio.app.features.player.LiveReplayLaunch
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.profiles.ProfileRepository
import com.tuvora.tvos.player.TvPlayerSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** What a replay start or a walk step produced: a session to show, or the viewer-facing reason there is none. */
data class TvReplayStep(
    val session: TvPlayerSession?,
    val notice: String?,
    /** The open or the link hung or failed: Swift offers Retry (the same programme again), not "no recording". */
    val canRetry: Boolean = false,
    /** [session] is the live channel the replay ended on: Swift swaps it in and shows [notice] over it. */
    val rejoinsLive: Boolean = false,
)

/** What Swift loops on while a replay runs: the next session to show, or the notice that ends it, or null when done. */
interface TvReplaySupervisor {
    suspend fun supervise(session: TvPlayerSession): TvReplayStep?
}

/**
 * The live guide's catch-up data: past windows from the stored history (focused channel only, as on
 * the phone and NuvioTV), the full synopsis, and replays. One dialect walk for the app: it
 * single-flights per account and supersedes its own stale attempts.
 */
object TvCatchUp {
    private val walk by lazy { CatchUpDialectWalk(LiveTvData.winnerMemory()) }

    /**
     * Whether the guide may offer replay on this playlist's channels: an Xtream panel, or a URL/file M3U
     * playlist (which channels actually have catch-up is each channel's own `hasArchive`).
     */
    fun supportsCatchUp(contentId: String): Boolean =
        LiveTvData.supportsCatchUp(contentId) || LiveTvData.isM3uPlaylist(contentId)

    /** Pulls the FOCUSED channel's guide table (never a page of rows: the 2 MB → 40 MB lesson). */
    suspend fun ensureHistory(contentId: String) = LiveTvData.ensureHistory(contentId)

    /**
     * A row's programmes for one window, per [GuideWindowSource]: stored history wins; the panel's
     * now-and-next is only the live window's first paint; a travelled window never falls back to it.
     * Null = keep what the row shows.
     */
    suspend fun windowProgrammes(contentId: String, fromMs: Long, toMs: Long, travelling: Boolean, historyShown: Boolean): TvWindowProgrammes {
        val history = LiveTvData.historyProgrammes(contentId, fromMs, toMs)
        return when (GuideWindowSource.forWindow(history.isNotEmpty(), historyShown, travelling)) {
            GuideWindowSource.Source.HISTORY -> TvWindowProgrammes(history, fromHistory = true)
            GuideWindowSource.Source.NOW_NEXT -> TvWindowProgrammes(LiveTvData.programmes(contentId, limit = 8), fromHistory = false)
            GuideWindowSource.Source.NONE -> TvWindowProgrammes(emptyList(), fromHistory = false)
        }
    }

    suspend fun description(contentId: String, programme: XtreamProgram): String =
        LiveTvData.programmeDescription(contentId, programme.startMs)?.takeIf { it.isNotBlank() } ?: programme.description

    /** M3U playlists build replays from their own catch-up attributes (shared M3uReplayWalk), not the Xtream dialects. */
    private val m3uWalk by lazy { M3uReplayWalk(LiveTvData.winnerStore()) }

    /** Opens run detached under a deadline: only the latest may publish a session (shared ReplayOpenFence). */
    private val openFence = ReplayOpenFence()

    /** The viewer chose something else or left: an open still in flight publishes nothing. */
    fun cancelOpens() { openFence.next() }

    /** A Stalker portal's replays: one minted single-use link each (no walk), one mint at a time. */
    private val stalkerStarter by lazy { TvStalkerReplayStarter(openFence) }

    suspend fun startReplay(channel: LiveGuideChannel, programme: XtreamProgram): TvReplayPair {
        if (LiveTvData.isStalkerPortal(channel.contentId)) return startStalkerReplay(channel, programme)
        val ticket = openFence.next()
        if (LiveTvData.isM3uPlaylist(channel.contentId)) {
            val replay = TvReplay(m3uWalk, openFence, ticket, channel, programme)
            return TvReplayPair(replay, replay.beginM3u(m3uWalk))
        }
        val replay = TvReplay(walk, openFence, ticket, channel, programme)
        return TvReplayPair(replay, replay.beginXtream(walk))
    }

    /**
     * A Stalker portal's replay: mint ONE `create_link` for the programme (its portal id; none = the no-recording
     * notice), open ONE session on it flagged single-use, and follow it with [TvStalkerReplay]. Retry from Swift
     * calls this again, which is a fresh mint; the spent URL is never opened a second time.
     */
    private suspend fun startStalkerReplay(channel: LiveGuideChannel, programme: XtreamProgram): TvReplayPair {
        val contentId = channel.contentId
        val io = LiveTvReplayIo(channel)
        val result = stalkerStarter.start(
            sourceType = LiveTvData.sourceTypeOf(contentId),
            programmeTitle = programme.title,
            mint = { LiveTvData.stalkerArchiveLink(contentId, programme.startMs) },
            resolve = { url -> io.resolveSingleUse(url) },
        )
        return when (result) {
            TvStalkerStart.Ignore -> TvReplayPair(TvNoReplay, TvReplayStep(null, null))
            is TvStalkerStart.Notice -> TvReplayPair(TvNoReplay, TvReplayStep(null, result.text, canRetry = result.canRetry))
            is TvStalkerStart.Play -> {
                val title = TvReplayPolicy.title(channel.name, programme.title)
                val launch = PlayerLaunch(
                    profileId = ProfileRepository.activeProfileId,
                    title = title,
                    sourceUrl = result.source.url,
                    sourceHeaders = result.source.headers,
                    streamTitle = title,
                    streamType = "live",
                    providerName = io.providerName(),
                    providerAddonId = "xtream",
                    logo = channel.logo,
                    contentType = "live",
                    videoId = contentId,
                    parentMetaId = contentId,
                    parentMetaType = "tv",
                    liveReplay = LiveReplayLaunch(programme.title, programme.startMs, programme.endMs),
                )
                // The link is spent by this one open: the session never re-opens it (no reconnect, retry or lane change).
                val session = TvPlayerSession(launch).bindSingleUseLink(true)
                TvReplayPair(TvStalkerReplay(openFence, result.ticket), TvReplayStep(session, null))
            }
        }
    }
}

/** No supervision: the replay ended before a session existed. */
private object TvNoReplay : TvReplaySupervisor {
    override suspend fun supervise(session: TvPlayerSession): TvReplayStep? = null
}

data class TvWindowProgrammes(val programmes: List<XtreamProgram>, val fromHistory: Boolean)

/** A started replay and its first step. */
data class TvReplayPair(val replay: TvReplaySupervisor, val first: TvReplayStep)
