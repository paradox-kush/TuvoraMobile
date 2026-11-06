package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.CatchUpStartupBudget
import com.nuvio.app.features.iptv.CatchUpStartupPolicy
import com.nuvio.app.features.iptv.stalker.StalkerReplayFailure
import com.nuvio.app.features.iptv.stalker.StalkerReplayOutcome
import kotlin.time.TimeSource

/** The viewer-facing words for a Stalker replay that did not play. The same copy as the phone's strings.xml. */
object TvStalkerReplayText {
    const val LIMIT = "This portal is already streaming on another device. Close it there, then press Retry."
    const val NOTHING_TO_PLAY = "The portal has no recording of this programme. Press Retry to ask again."
    const val LINK_FAULT = "The portal couldn’t build a playback link for this programme. Press Retry to try again."
    const val PLAYBACK = "The portal’s link for this programme stopped working. Press Retry to ask for a fresh one."
    const val NO_ANSWER = "The provider didn’t start this replay in time. Press Retry to try again, or go back and pick another programme."

    fun forFailure(failure: StalkerReplayFailure): String = when (failure) {
        StalkerReplayFailure.SESSION_LIMIT -> LIMIT
        StalkerReplayFailure.NOTHING_TO_PLAY -> NOTHING_TO_PLAY
        StalkerReplayFailure.LINK_FAULT -> LINK_FAULT
        StalkerReplayFailure.PLAYBACK_ERROR -> PLAYBACK
        StalkerReplayFailure.NO_ANSWER, StalkerReplayFailure.STARTUP_HANG -> NO_ANSWER
    }
}

/**
 * One Stalker archive replay on Apple TV, as a pure watcher over the ONE minted single-use link. There is no
 * ladder: no walk, no probe, no re-open, no engine fallback on the same URL. The link either plays or the run
 * answers [Move.Failed] and the viewer's Retry mints a fresh one.
 *
 * Every wait ends: the first frame must arrive within the shared [CatchUpStartupBudget] (one link, so its
 * 15 s deadline, then a hang). The clock is injected so tests drive it.
 */
class TvStalkerReplayRun(
    private val clockMs: () -> Long = monotonicClockMs(),
) {
    sealed interface Move {
        data object Wait : Move

        /** The replay ended normally (or the player is done with it): stop supervising. */
        data object Done : Move

        /** The link did not play or stopped: the viewer sees [failure]'s words and Retry (a fresh mint). */
        data class Failed(val failure: StalkerReplayFailure) : Move
    }

    private val budget = CatchUpStartupBudget(clockMs)
    private var proven = false
    private var armedAtMs: Long? = null
    private var deadlineMs = CatchUpStartupPolicy.LINK_DEADLINE_MS

    fun observe(isPlaying: Boolean, positionMs: Long, isEnded: Boolean, errorMessage: String?): Move {
        if (proven) {
            return when {
                errorMessage != null -> Move.Failed(StalkerReplayFailure.PLAYBACK_ERROR)
                isEnded -> Move.Done
                else -> Move.Wait
            }
        }
        return when (TvReplayPolicy.verdict(isPlaying, positionMs, isEnded, errorMessage)) {
            TvReplayVerdict.Proven -> { proven = true; budget.markPlayed(); Move.Wait }
            TvReplayVerdict.SessionLimit -> Move.Failed(StalkerReplayFailure.SESSION_LIMIT)
            TvReplayVerdict.Failed -> Move.Failed(StalkerReplayOutcome.ofSession(startupTimedOut = false))
            TvReplayVerdict.Pending -> {
                val armed = armedAtMs ?: clockMs().also {
                    armedAtMs = it
                    deadlineMs = budget.arm() ?: CatchUpStartupPolicy.LINK_DEADLINE_MS
                }
                if (clockMs() - armed >= deadlineMs) {
                    // One link: its timeout is final whatever the budget would allow a walk.
                    budget.onTimeout()
                    Move.Failed(StalkerReplayOutcome.ofSession(startupTimedOut = true))
                } else Move.Wait
            }
        }
    }

    private companion object {
        fun monotonicClockMs(): () -> Long {
            val origin = TimeSource.Monotonic.markNow()
            return { origin.elapsedNow().inWholeMilliseconds }
        }
    }
}
