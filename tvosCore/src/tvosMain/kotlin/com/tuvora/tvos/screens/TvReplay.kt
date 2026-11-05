package com.tuvora.tvos.screens

import com.nuvio.app.core.contracts.LivePlaybackAccess
import com.nuvio.app.features.iptv.BoundedLoad
import com.nuvio.app.features.iptv.CatchUpDialectWalk
import com.nuvio.app.features.iptv.LoadOutcome
import com.nuvio.app.features.iptv.LoadSurface
import com.nuvio.app.features.iptv.M3uReplayContinuation
import com.nuvio.app.features.iptv.M3uReplayOpen
import com.nuvio.app.features.iptv.M3uReplayWalk
import com.nuvio.app.features.iptv.ReplayLadder
import com.nuvio.app.features.iptv.ReplayOpenFence
import com.nuvio.app.features.iptv.XtreamProgram
import com.nuvio.app.features.livetv.LiveGuideChannel
import com.nuvio.app.features.livetv.LiveTvData
import com.nuvio.app.features.player.LiveReplayLaunch
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktPlatformClock
import com.tuvora.tvos.player.TvPlayerSession
import com.tuvora.tvos.player.TvResolvedSource
import kotlinx.coroutines.delay

/**
 * A programme replay on Apple TV, M3U or Xtream: the phone's LiveTvScreen flow (open under the live-resolve
 * deadline, walk the provider's link ladder, follow the playhead into the next programmes, rejoin live)
 * driven through the one [TvReplayRun]. This class only performs the run's moves; the decisions are the run's.
 *
 * It never closes a session it hands over: a [TvReplayRun.Move.Reopen] returns the successor and Swift's
 * PlaybackCoordinator closes the predecessor on the swap. Closing it here made the swap read "the viewer
 * left" and drop the successor, so the Xtream dialect walk never moved (B229).
 *
 * Lifecycle: the walk's ask for this replay is released when supervision ends, however it ends (the
 * viewer left, a notice, rejoining live), so nothing is held after the player is gone.
 */
internal class TvReplay(
    private val ladder: ReplayLadder,
    private val fence: ReplayOpenFence,
    private val ticket: Long,
    private val channel: LiveGuideChannel,
    private val programme: XtreamProgram,
    /** The outside world (guide database, link resolver, player): the live app's by default, a fake in tests. */
    private val io: TvReplayIo = LiveTvReplayIo(channel),
) : TvReplaySupervisor {
    private var run: TvReplayRun? = null
    private var finished = false
    private var nextRow: M3uReplayContinuation.Row? = null
    private var nextRowFor = Long.MIN_VALUE

    /** An M3U playlist: bounded read of the channel's line, then the first link and its player session. */
    suspend fun beginM3u(walk: M3uReplayWalk): TvReplayStep {
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
        return start(begun)
    }

    /** An Xtream panel: bounded build of the request, then the walk asks for the continuation window. */
    suspend fun beginXtream(walk: CatchUpDialectWalk): TvReplayStep {
        val outcome = BoundedLoad.run<CatchUpDialectWalk.Request?>(
            surface = LoadSurface.LIVE_RESOLVE,
            isEmpty = { it == null },
            report = mapOf("source_type" to LiveTvData.sourceTypeOf(channel.contentId), "kind" to "catchup"),
        ) { LiveTvData.catchUpRequest(channel.contentId, programme.startMs, programme.endMs) }
        if (!fence.isCurrent(ticket)) return stale()
        val request = when (outcome) {
            is LoadOutcome.Failed -> return TvReplayStep(null, TvReplayPolicy.REPLAY_FAILED_TEXT, canRetry = true)
            is LoadOutcome.Empty -> return noRecording()
            is LoadOutcome.Loaded -> outcome.value ?: return noRecording()
        }
        val begun = walk.beginReplay(request, TraktPlatformClock.nowEpochMs(), scope = null, admit = { fence.isCurrent(ticket) })
        return start(begun)
    }

    internal suspend fun start(begun: M3uReplayWalk.Begun?): TvReplayStep {
        begun ?: return if (fence.isCurrent(ticket)) noRecording() else stale()
        val started = TvReplayRun(ladder, channel.name, programme.title, programme.startMs, programme.endMs, begun)
        run = started
        return open(started, started.url)
    }

    override suspend fun supervise(session: TvPlayerSession): TvReplayStep? = superviseOn(TvSessionPlayback(session))

    internal suspend fun superviseOn(session: TvReplayPlayback): TvReplayStep? {
        val current = run ?: return null
        if (finished) return null
        while (true) {
            if (session.isClosed || !fence.isCurrent(ticket)) { release(); return null }
            ensureNextRow(current)
            val s = session.snapshot()
            val move = current.observe(
                isPlaying = s.isPlaying, positionMs = s.positionMs, isEnded = s.isEnded, errorMessage = s.errorMessage,
                next = nextRow, nowMs = io.nowMs(),
            )
            when (move) {
                TvReplayRun.Move.Wait -> delay(TICK_MS)
                is TvReplayRun.Move.Retitle -> {
                    session.retitle(move.title)
                    session.setReplayBar(current.barOriginMs, current.barSpanMs, current.barProgrammeStartMs, current.barProgrammeEndMs)
                    delay(TICK_MS)
                }
                // The old session is left for Swift to swap out (PlaybackCoordinator closes it): closing it here
                // would make the swap read "the viewer left" and drop the new one.
                is TvReplayRun.Move.Reopen -> return open(current, move.attempt.url)
                TvReplayRun.Move.GoLive -> return ended(null)
                TvReplayRun.Move.Ended -> return ended(TvReplayPolicy.REPLAY_ENDED_TEXT)
                is TvReplayRun.Move.Failed -> return failed(move)
                TvReplayRun.Move.Abandon -> { release(); return null }
            }
        }
    }

    /** The guide row after the programme on screen, looked up once per programme (a local database read). */
    private suspend fun ensureNextRow(current: TvReplayRun) {
        val endMs = current.programmeEndMs
        if (nextRowFor == endMs) return
        nextRowFor = endMs
        nextRow = runCatching { io.nextRow(endMs) }.getOrNull()
    }

    /** Resolves [url] like a live link (bounded) and wraps it in a replay session. */
    private suspend fun open(started: TvReplayRun, url: String): TvReplayStep {
        val source = io.resolve(url)
        if (!fence.isCurrent(ticket)) { release(); return stale() }
        if (source == null) {
            // This link's host did not resolve in time: the next link on the ladder, else a failure with Retry.
            return when (val move = started.sourceFailed()) {
                is TvReplayRun.Move.Reopen -> open(started, move.attempt.url)
                is TvReplayRun.Move.Failed -> failed(move)
                else -> { release(); stale() }
            }
        }
        val title = started.initialTitle()
        val launch = PlayerLaunch(
            profileId = ProfileRepository.activeProfileId,
            title = title,
            sourceUrl = source.url,
            sourceHeaders = source.headers,
            streamTitle = title,
            streamType = "live",
            providerName = io.providerName(),
            providerAddonId = "xtream",
            logo = channel.logo,
            contentType = "live",
            videoId = channel.contentId,
            parentMetaId = channel.contentId,
            parentMetaType = "tv",
            liveReplay = LiveReplayLaunch(programme.title, programme.startMs, programme.endMs),
        )
        return TvReplayStep(io.newSession(launch).also { it.setReplayBar(started.barOriginMs, started.barSpanMs, started.barProgrammeStartMs, started.barProgrammeEndMs) }, null)
    }

    private fun failed(move: TvReplayRun.Move.Failed): TvReplayStep {
        release()
        return TvReplayStep(null, move.notice, canRetry = move.retry)
    }

    /** The replay ended: back on the live channel it belongs to. Without a live session the guide says so instead. */
    private suspend fun ended(notice: String?): TvReplayStep {
        release()
        val live = runCatching { io.playLive() }.getOrNull()
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

/** What a replay needs from outside, so its handover logic tests without the guide database, network or engine. */
internal interface TvReplayIo {
    /** The playable address of [url], resolved under the live-resolve deadline; null when it did not resolve in time. */
    suspend fun resolve(url: String): TvResolvedSource?
    suspend fun nextRow(endMs: Long): M3uReplayContinuation.Row?
    suspend fun playLive(): TvPlayerSession?
    fun newSession(launch: PlayerLaunch): TvPlayerSession
    fun nowMs(): Long
    fun providerName(): String
}

internal class LiveTvReplayIo(private val channel: LiveGuideChannel) : TvReplayIo {
    override suspend fun resolve(url: String): TvResolvedSource? =
        BoundedLoad.run(
            surface = LoadSurface.LIVE_RESOLVE,
            report = mapOf("source_type" to LiveTvData.sourceTypeOf(channel.contentId), "kind" to "catchup_source"),
        ) { LiveTvData.catchUpSource(channel.contentId, url) }.valueOrNull()?.let { TvResolvedSource(it.url, it.headers) }

    override suspend fun nextRow(endMs: Long) = LiveTvData.replayRowAt(channel.contentId, endMs)
    override suspend fun playLive() = TvIptvBrowse.playChannel(channel.contentId, channel.name, channel.logo)
    override fun newSession(launch: PlayerLaunch) = TvPlayerSession(launch)
    override fun nowMs() = TraktPlatformClock.nowEpochMs()
    override fun providerName() = LivePlaybackAccess.current().accountNameFor(channel.contentId) ?: "IPTV"
}

/** What the supervisor reads off the session it follows. */
internal data class TvReplaySnapshot(val isPlaying: Boolean, val positionMs: Long, val isEnded: Boolean, val errorMessage: String?)

/** The session being followed. The supervisor reads and retitles it; it must NOT close one it hands over (B229). */
internal interface TvReplayPlayback {
    val isClosed: Boolean
    /** Only for a session the supervisor owns outright; a handover leaves closing to Swift's PlaybackCoordinator. */
    fun close()
    fun snapshot(): TvReplaySnapshot
    fun retitle(title: String)
    fun setReplayBar(originMs: Long, spanMs: Long, programmeStartMs: Long, programmeEndMs: Long)
}

internal class TvSessionPlayback(private val session: TvPlayerSession) : TvReplayPlayback {
    override val isClosed get() = session.isClosed
    override fun close() = session.close()
    override fun snapshot() = session.state.value.let { TvReplaySnapshot(it.isPlaying, it.positionMs, it.isEnded, it.errorMessage) }
    override fun retitle(title: String) = session.retitle(title)
    override fun setReplayBar(originMs: Long, spanMs: Long, programmeStartMs: Long, programmeEndMs: Long) =
        session.setReplayBar(originMs, spanMs, programmeStartMs, programmeEndMs)
}
