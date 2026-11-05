package com.nuvio.app.features.iptv

/**
 * Adapted from homelessbrian/NuvioPlusIPTV (GPL-3.0), `LiveTvViewModel.followCatchup`.
 *
 * Catch-up carries on into the next programme. A replay asks the provider for more than the
 * programme itself ([replayWindowEndMs]: up to [CONTINUE_MS] past its end, never past what has
 * aired), so playback runs on after the programme's end. This policy decides what the screen does:
 * leave things alone, move the title and seek bar to the next programme, or, once the window the
 * provider served has run out, rejoin live, start a fresh replay at the playhead, or end.
 *
 * A finished window never decides [Decision.Stay]: every outcome is terminal. Pure: no player, no
 * clock, no guide repository.
 */
object CatchUpContinuationPolicy {

    /** How far past the end of a programme a replay keeps going, into the next programmes. */
    const val CONTINUE_MS: Long = 3L * 60 * 60 * 1000

    /** A playhead this close to "now" is live. */
    const val LIVE_EDGE_MS: Long = 60_000L

    /** Margin kept behind "now": a request reaching into the future is refused by some panels. */
    const val AIRED_MARGIN_MS: Long = 30_000L

    data class Programme(val startMs: Long, val endMs: Long)

    sealed interface Decision {
        /** Nothing to change. */
        data object Stay : Decision

        /** The playhead has passed into [programme]: move the title and seek bar there. */
        data class MoveToNext(val programme: Programme) : Decision

        /** The served window ran out: start a new replay at absolute time [atMs]. */
        data class StartNewReplay(val atMs: Long) : Decision

        /** The window ran out with the playhead at the live edge: rejoin the live stream. */
        data object GoLive : Decision

        /** The window ran out and there is nothing to replay: stop waiting and say so. */
        data object Ended : Decision
    }

    /** The end of the window to ask the provider for when replaying [programme]. */
    fun replayWindowEndMs(programme: Programme, nowMs: Long): Long =
        maxOf(programme.endMs, minOf(nowMs - AIRED_MARGIN_MS, programme.endMs + CONTINUE_MS))

    /**
     * @param playing the programme the replay session is showing
     * @param next the guide's programme after it (or the one containing the playhead), if any
     * @param playheadMs wall-clock instant on screen
     * @param windowEndMs end of the window that was requested ([replayWindowEndMs]); the window is
     *   over once the playhead reaches it
     */
    fun decide(
        playing: Programme,
        next: Programme?,
        playheadMs: Long,
        windowEndMs: Long,
        nowMs: Long,
    ): Decision {
        if (playheadMs >= windowEndMs) {
            if (playheadMs > nowMs - LIVE_EDGE_MS) return Decision.GoLive
            val covered = (playheadMs >= playing.startMs && playheadMs < playing.endMs) ||
                (next != null && playheadMs >= next.startMs && playheadMs < next.endMs)
            return if (covered) Decision.StartNewReplay(playheadMs) else Decision.Ended
        }
        if (playheadMs < playing.endMs) return Decision.Stay
        if (next == null || next.startMs == playing.startMs) return Decision.Stay
        return Decision.MoveToNext(next)
    }
}
