package com.tuvora.tvos.screens

import com.nuvio.app.core.contracts.LivePlaybackAccess
import com.nuvio.app.features.iptv.BoundedLoad
import com.nuvio.app.features.iptv.LoadSurface
import com.nuvio.app.features.iptv.M3uReplayContinuation
import com.nuvio.app.features.iptv.M3uReplayOpen
import com.nuvio.app.features.iptv.M3uReplayWalk
import com.nuvio.app.features.iptv.ReplayOpenFence
import com.nuvio.app.features.iptv.XtreamProgram
import com.nuvio.app.features.livetv.LiveGuideChannel
import com.nuvio.app.features.livetv.LiveTvData
import com.nuvio.app.features.player.LiveReplayLaunch
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktPlatformClock
import com.tuvora.tvos.player.TvPlayerSession
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

/**
 * An M3U playlist's replay on Apple TV: the phone's LiveTvScreen flow (open under the live-resolve
 * deadline, walk the playlist's own links, follow the playhead into the next programmes, rejoin live)
 * driven through [TvM3uReplayRun]. This class only performs the run's moves; the decisions are the run's.
 *
 * Lifecycle: the walk's ask for this replay is released when supervision ends, however it ends (the
 * viewer left, a notice, rejoining live), so nothing is held after the player is gone.
 */
internal class TvM3uReplay(
    private val walk: M3uReplayWalk,
    private val fence: ReplayOpenFence,
    private val ticket: Long,
    private val channel: LiveGuideChannel,
    private val programme: XtreamProgram,
) : TvReplaySupervisor {
    private var run: TvM3uReplayRun? = null
    private var finished = false
    private var openedAt = TimeSource.Monotonic.markNow()
    private var nextRow: M3uReplayContinuation.Row? = null
    private var nextRowFor = Long.MIN_VALUE

    /** Opens the replay: bounded read of the channel's line, the first link, and its player session. */
    suspend fun begin(): TvReplayStep {
        val open = M3uReplayOpen.run(LiveTvData.sourceTypeOf(channel.contentId)) {
            LiveTvData.m3uReplayRequest(channel.contentId, programme.startMs, programme.endMs, TraktPlatformClock.nowEpochMs())
        }
        // A newer open, a Back or a zap overtook this one: a late result publishes nothing.
        if (!fence.isCurrent(ticket)) return stale()
        val request = when (open) {
            is M3uReplayOpen.Failed -> return TvReplayStep(null, TvReplayPolicy.REPLAY_FAILED_TEXT, canRetry = true)
            M3uReplayOpen.Unavailable -> return noRecording()
            is M3uReplayOpen.Ready -> open.request
        }
        val begun = walk.begin(request, scope = null, admit = { fence.isCurrent(ticket) })
            ?: return if (fence.isCurrent(ticket)) noRecording() else stale()
        val started = TvM3uReplayRun(walk, channel.name, programme.title, programme.startMs, programme.endMs, begun)
        run = started
        return open(started, started.url)
    }

    override suspend fun supervise(session: TvPlayerSession): TvReplayStep? {
        val current = run ?: return null
        if (finished) return null
        while (true) {
            if (session.isClosed || !fence.isCurrent(ticket)) { release(); return null }
            ensureNextRow(current)
            val s = session.state.value
            val move = current.observe(
                isPlaying = s.isPlaying, positionMs = s.positionMs, isEnded = s.isEnded, errorMessage = s.errorMessage,
                elapsedMs = openedAt.elapsedNow().inWholeMilliseconds,
                next = nextRow, nowMs = TraktPlatformClock.nowEpochMs(),
            )
            when (move) {
                TvM3uReplayRun.Move.Wait -> delay(TICK_MS)
                is TvM3uReplayRun.Move.Retitle -> {
                    session.retitle(move.title)
                    session.setReplayBar(current.barOriginMs, current.barSpanMs)
                    delay(TICK_MS)
                }
                // The old session is left for Swift to swap out (PlaybackCoordinator closes it): closing it here
                // would make the swap read "the viewer left" and drop the new one.
                is TvM3uReplayRun.Move.Reopen -> return open(current, move.attempt.url)
                TvM3uReplayRun.Move.GoLive -> return ended(null)
                TvM3uReplayRun.Move.Ended -> return ended(TvReplayPolicy.REPLAY_ENDED_TEXT)
                is TvM3uReplayRun.Move.Failed -> return failed(move)
                TvM3uReplayRun.Move.Abandon -> { release(); return null }
            }
        }
    }

    /** The guide row after the programme on screen, looked up once per programme (a local database read). */
    private suspend fun ensureNextRow(current: TvM3uReplayRun) {
        val endMs = current.programmeEndMs
        if (nextRowFor == endMs) return
        nextRowFor = endMs
        nextRow = runCatching { LiveTvData.replayRowAt(channel.contentId, endMs) }.getOrNull()
    }

    /** Resolves [url] like a live link (bounded) and wraps it in a replay session. */
    private suspend fun open(started: TvM3uReplayRun, url: String): TvReplayStep {
        val source = BoundedLoad.run(
            surface = LoadSurface.LIVE_RESOLVE,
            report = mapOf("source_type" to LiveTvData.sourceTypeOf(channel.contentId), "kind" to "catchup_source"),
        ) { LiveTvData.catchUpSource(channel.contentId, url) }.valueOrNull()
        if (!fence.isCurrent(ticket)) { release(); return stale() }
        if (source == null) {
            // This link's host did not resolve in time: the next link on the ladder, else a failure with Retry.
            return when (val move = started.sourceFailed()) {
                is TvM3uReplayRun.Move.Reopen -> open(started, move.attempt.url)
                is TvM3uReplayRun.Move.Failed -> failed(move)
                else -> { release(); stale() }
            }
        }
        openedAt = TimeSource.Monotonic.markNow()
        val title = started.initialTitle()
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
        return TvReplayStep(TvPlayerSession(launch).also { it.setReplayBar(started.barOriginMs, started.barSpanMs) }, null)
    }

    private fun failed(move: TvM3uReplayRun.Move.Failed): TvReplayStep {
        release()
        return TvReplayStep(null, move.notice, canRetry = move.retry)
    }

    /** The replay ended: back on the live channel it belongs to. Without a live session the guide says so instead. */
    private suspend fun ended(notice: String?): TvReplayStep {
        release()
        val live = runCatching { TvIptvBrowse.playChannel(channel.contentId, channel.name, channel.logo) }.getOrNull()
        return TvReplayStep(live, notice ?: if (live == null) TvReplayPolicy.REPLAY_ENDED_TEXT else null, rejoinsLive = live != null)
    }

    private fun noRecording(): TvReplayStep {
        release()
        return TvReplayStep(null, TvReplayPolicy.noRecordingText(programme.title))
    }

    private fun stale() = TvReplayStep(null, null)

    /** Ends supervision: the walk's ask for this replay is released. */
    private fun release() {
        finished = true
        run?.release?.dispose()
    }

    private companion object {
        const val TICK_MS = 1_000L
    }
}
