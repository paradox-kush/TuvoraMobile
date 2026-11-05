package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.CatchUpDialectWalk
import com.nuvio.app.features.iptv.CatchUpPlayback
import com.nuvio.app.features.iptv.CatchUpStartupBudget
import com.nuvio.app.features.iptv.CatchUpStartupPolicy
import com.nuvio.app.features.iptv.M3uReplayContinuation
import com.nuvio.app.features.iptv.M3uReplayWalk
import com.nuvio.app.features.iptv.ReplayLadder
import kotlin.time.TimeSource

/**
 * One replay on Apple TV (an M3U playlist's or an Xtream panel's), as a pure decision machine over its
 * [ReplayLadder]: the phone's LiveTvScreen replay rules (proof, link walk, continuation into the next programme, restart, rejoin live) without the screen. The facade
 * feeds it what the player shows once a second ([observe]) and does the one [Move] it answers; Swift
 * never decides. No player, clock or network here, so it tests without them.
 *
 * Every wait ends: a link that neither plays nor fails within the shared [CatchUpStartupBudget] (15 s per
 * link, 45 s in all, three timeouts) counts as a failed link (the next shape on the ladder, or
 * [Move.Failed] with Retry), never an endless spinner. The run never touches a player session: a [Move.Reopen]
 * is answered by the facade opening a new one and Swift closing the old one on the swap.
 */
class TvReplayRun(
    private val walk: ReplayLadder,
    private val channelName: String,
    programmeTitle: String,
    programmeStartMs: Long,
    programmeEndMs: Long,
    first: M3uReplayWalk.Begun,
    /** Monotonic; injected so tests drive the startup deadlines. */
    private val clockMs: () -> Long = monotonicClockMs(),
) {
    /** What the facade does after an [observe]. */
    sealed interface Move {
        /** Nothing to change. */
        data object Wait : Move

        /** Show [title] for the programme the playhead moved into. */
        data class Retitle(val title: String) : Move

        /** Open [attempt]'s link in a fresh session (the next link shape, or a restart past the served window). */
        data class Reopen(val attempt: CatchUpDialectWalk.Attempt) : Move

        /** The window ran out at the live edge: rejoin the live channel. */
        data object GoLive : Move

        /** The window ran out with nothing left to replay: say so and rejoin live. */
        data object Ended : Move

        /** Nothing plays: [retry] is true when asking again could help (a hang), false for a provider with no recording. */
        data class Failed(val notice: String, val retry: Boolean) : Move

        /** A superseded replay's late answer: stop supervising it. */
        data object Abandon : Move
    }

    private var attempt = first.attempt
    private var windowEndMs = first.windowEndMs
    private var streamStartMs = programmeStartMs
    private var title = programmeTitle
    private var startMs = programmeStartMs
    private var endMs = programmeEndMs
    private var proven = false
    private var budget = CatchUpStartupBudget(clockMs)

    /** When the current link's wait began and how long it may last; null until the first [observe] of that link. */
    private var linkStartMs: Long? = null
    private var linkDeadlineMs = CatchUpStartupPolicy.LINK_DEADLINE_MS

    /** The ask the walk holds for this replay; disposed by the facade when supervision ends. */
    val release = first.release

    /** The link the current session plays. */
    val url: String get() = attempt.url

    /** End of the programme on screen: the facade looks up the guide row after it when this changes. */
    val programmeEndMs: Long get() = endMs

    /** Where the programme on screen starts in the player's timeline: the seek bar's origin ([TvReplayBar]). */
    val barOriginMs: Long get() = TvReplayBar.originMs(startMs, streamStartMs)

    /** The seek bar spans the programme on screen. */
    val barSpanMs: Long get() = (endMs - startMs).coerceAtLeast(1L)

    /** The programme on screen, for the seek bar's live-edge clamp ([TvReplayBar.clampSeek]). */
    val barProgrammeStartMs: Long get() = startMs
    val barProgrammeEndMs: Long get() = endMs

    /** The replay's player title as the first session shows it. */
    fun initialTitle(): String = TvReplayPolicy.title(channelName, title)

    /**
     * @param next the guide row after (or covering the playhead past) the programme on screen, if known
     */
    fun observe(
        isPlaying: Boolean,
        positionMs: Long,
        isEnded: Boolean,
        errorMessage: String?,
        next: M3uReplayContinuation.Row?,
        nowMs: Long,
    ): Move {
        if (!proven) {
            val verdict = TvReplayPolicy.verdict(isPlaying, positionMs, isEnded, errorMessage)
            if (verdict == TvReplayVerdict.Pending && linkStartMs == null) {
                linkStartMs = clockMs()
                linkDeadlineMs = budget.arm() ?: CatchUpStartupPolicy.LINK_DEADLINE_MS
            }
            val hung = verdict == TvReplayVerdict.Pending && clockMs() - (linkStartMs ?: clockMs()) >= linkDeadlineMs
            when {
                verdict == TvReplayVerdict.Pending && !hung -> return Move.Wait
                verdict == TvReplayVerdict.Proven -> {
                    if (walk.onSuccess(attempt.token) == CatchUpDialectWalk.Step.Stale) return Move.Abandon
                    proven = true
                    budget.markPlayed()
                }
                verdict == TvReplayVerdict.SessionLimit -> return Move.Failed(TvReplayPolicy.SESSION_LIMIT_TEXT, retry = false)
                hung -> return timedOut()
                else -> return failed(CatchUpPlayback.failureKind(errorMessage), hung = false)
            }
        }
        return when (
            val action = M3uReplayContinuation.react(
                state = M3uReplayContinuation.State(
                    programme = com.nuvio.app.features.iptv.CatchUpContinuationPolicy.Programme(startMs, endMs),
                    streamStartMs = streamStartMs,
                    windowEndMs = windowEndMs,
                    url = attempt.url,
                ),
                next = next,
                positionMs = positionMs,
                ended = isEnded,
                nowMs = nowMs,
            )
        ) {
            M3uReplayContinuation.Action.Stay -> Move.Wait
            is M3uReplayContinuation.Action.Retitle -> {
                title = action.next.title.ifBlank { title }
                startMs = action.next.startMs
                endMs = action.next.endMs
                Move.Retitle(TvReplayPolicy.title(channelName, title))
            }
            is M3uReplayContinuation.Action.Restart -> {
                val begun = walk.restartAt(attempt.token, action.atMs, nowMs) ?: return Move.Ended
                attempt = begun.attempt
                streamStartMs = action.atMs
                windowEndMs = begun.windowEndMs
                proven = false
                budget = CatchUpStartupBudget(clockMs)
                linkStartMs = null
                Move.Reopen(attempt)
            }
            M3uReplayContinuation.Action.GoLive -> Move.GoLive
            M3uReplayContinuation.Action.End -> Move.Ended
        }
    }

    /** The link could not be opened at all (its source did not resolve in time): walk on, or fail with Retry. */
    fun sourceFailed(): Move = timedOut()

    /** A link's deadline passed with no frame (or its source never resolved): the budget decides whether to walk on. */
    private fun timedOut(): Move {
        val giveUp = budget.onTimeout() == CatchUpStartupPolicy.Decision.GIVE_UP
        val move = failed(CatchUpDialectWalk.FailureKind.TRANSPORT, hung = true)
        return if (giveUp && move is Move.Reopen) Move.Failed(TvReplayPolicy.REPLAY_FAILED_TEXT, retry = true) else move
    }

    private fun failed(kind: CatchUpDialectWalk.FailureKind, hung: Boolean): Move =
        when (val step = walk.onFailure(attempt.token, kind)) {
            is CatchUpDialectWalk.Step.Next -> { attempt = step.attempt; linkStartMs = null; Move.Reopen(step.attempt) }
            CatchUpDialectWalk.Step.Stale -> Move.Abandon
            // Out of links: a provider that answered with nothing has no recording; one that never answered failed.
            else -> if (hung) Move.Failed(TvReplayPolicy.REPLAY_FAILED_TEXT, retry = true)
            else Move.Failed(TvReplayPolicy.noRecordingText(title), retry = false)
        }

    private companion object {
        fun monotonicClockMs(): () -> Long {
            val origin = TimeSource.Monotonic.markNow()
            return { origin.elapsedNow().inWholeMilliseconds }
        }
    }
}
