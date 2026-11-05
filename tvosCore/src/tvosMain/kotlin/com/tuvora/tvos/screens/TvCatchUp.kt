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
 * One programme replay, run through the shared catch-up path exactly as the phone's LiveTvScreen
 * does: [LiveTvData.catchUpRequest] → [CatchUpDialectWalk] picks the URL shape → [LiveTvData.catchUpSource]
 * resolves it like a live link → a [PlayerLaunch] carrying [LiveReplayLaunch] (live identity, replay
 * bounds, seekable). [supervise] follows each attempt: proof pins the dialect, failure walks to the
 * next shape (TvReplayPolicy).
 */
class TvReplay internal constructor(
    private val walk: CatchUpDialectWalk,
    private val channel: LiveGuideChannel,
    private val programme: XtreamProgram,
) : TvReplaySupervisor {
    private var attempt: CatchUpDialectWalk.Attempt? = null

    internal suspend fun begin(): TvReplayStep {
        val request = LiveTvData.catchUpRequest(channel.contentId, programme.startMs, programme.endMs)
        val step = request?.let(walk::begin)
        if (step !is CatchUpDialectWalk.Step.Next) return TvReplayStep(null, TvReplayPolicy.noRecordingText(programme.title))
        return open(step.attempt)
    }

    /**
     * Waits for [session]'s outcome. Returns null when it played (walk pinned) or the viewer left
     * (nothing to decide after [TIMEOUT_MS]); otherwise the next attempt or the notice that ends it.
     */
    override suspend fun supervise(session: TvPlayerSession): TvReplayStep? {
        val current = attempt ?: return null
        val verdict = withTimeoutOrNull(TIMEOUT_MS) {
            session.state.first {
                TvReplayPolicy.verdict(it.isPlaying, it.positionMs, it.isEnded, it.errorMessage) != TvReplayVerdict.Pending
            }.let { TvReplayPolicy.verdict(it.isPlaying, it.positionMs, it.isEnded, it.errorMessage) }
        } ?: return null
        val error = session.state.value.errorMessage
        return when (verdict) {
            TvReplayVerdict.Proven -> { walk.onSuccess(current.token); null }
            TvReplayVerdict.SessionLimit -> TvReplayStep(null, TvReplayPolicy.SESSION_LIMIT_TEXT)
            else -> when (val step = walk.onFailure(current.token, CatchUpPlayback.failureKind(error))) {
                is CatchUpDialectWalk.Step.Next -> { session.close(); open(step.attempt) }
                CatchUpDialectWalk.Step.Stale -> null
                else -> TvReplayStep(null, TvReplayPolicy.noRecordingText(programme.title))
            }
        }
    }

    private suspend fun open(next: CatchUpDialectWalk.Attempt): TvReplayStep {
        attempt = next
        val source = LiveTvData.catchUpSource(channel.contentId, next.url)
        val title = TvReplayPolicy.title(channel.name, programme.title)
        val launch = PlayerLaunch(
            profileId = ProfileRepository.activeProfileId,
            title = title,
            sourceUrl = source.url,
            sourceHeaders = source.headers,
            streamTitle = title,
            streamType = "live",
            providerName = LivePlaybackAccess.current().accountNameFor(channel.contentId) ?: "IPTV",
            providerAddonId = "xtream",
            logo = channel.logo,
            contentType = "live",
            videoId = channel.contentId,
            parentMetaId = channel.contentId,
            parentMetaType = "tv",
            liveReplay = LiveReplayLaunch(programme.title, programme.startMs, programme.endMs),
        )
        return TvReplayStep(TvPlayerSession(launch), null)
    }

    private companion object {
        /** An attempt that neither plays nor fails in this long is left to the player's own error UI. */
        const val TIMEOUT_MS = 45_000L
    }
}

/**
 * The live guide's catch-up data: past windows from the stored history (focused channel only, as on
 * the phone and NuvioTV), the full synopsis, and replays. One dialect walk for the app: it
 * single-flights per account and supersedes its own stale attempts.
 */
object TvCatchUp {
    private val walk by lazy { CatchUpDialectWalk(LiveTvData.winnerMemory()) }

    fun supportsCatchUp(contentId: String): Boolean = LiveTvData.supportsCatchUp(contentId)

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

    suspend fun startReplay(channel: LiveGuideChannel, programme: XtreamProgram): TvReplayPair {
        val ticket = openFence.next()
        if (LiveTvData.isM3uPlaylist(channel.contentId)) {
            val replay = TvM3uReplay(m3uWalk, openFence, ticket, channel, programme)
            return TvReplayPair(replay, replay.begin())
        }
        val replay = TvReplay(walk, channel, programme)
        return TvReplayPair(replay, replay.begin())
    }
}

data class TvWindowProgrammes(val programmes: List<XtreamProgram>, val fromHistory: Boolean)

/** A started replay and its first step. */
data class TvReplayPair(val replay: TvReplaySupervisor, val first: TvReplayStep)
