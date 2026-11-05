package com.nuvio.app.features.iptv

/**
 * What the Live TV screen does as an M3U replay's playhead moves on past the programme it was started
 * for. The DECISION is [CatchUpContinuationPolicy.decide]; this object feeds it (where the playhead is
 * in wall-clock time, which guide row is next) and names the single thing the screen then does, so the
 * composable only acts and never decides.
 */
object M3uReplayContinuation {

    /** A guide row as the replay titles it. */
    data class Row(val title: String, val startMs: Long, val endMs: Long)

    /** The replay as the screen holds it. [streamStartMs] is the instant the LINK asked the provider to start from. */
    data class State(
        val programme: CatchUpContinuationPolicy.Programme,
        val streamStartMs: Long,
        val windowEndMs: Long,
        val url: String,
    )

    sealed interface Action {
        /** Nothing to change. */
        data object Stay : Action

        /** The playhead entered [next]: show its title and move the seek bar to its bounds. */
        data class Retitle(val next: Row) : Action

        /** The window ran out mid-guide: ask for a new one starting at [atMs]. */
        data class Restart(val atMs: Long) : Action

        /** The window ran out at the live edge: rejoin the live stream. */
        data object GoLive : Action

        /** The window ran out and there is nothing to replay: stop and say so. */
        data object End : Action
    }

    /**
     * The wall-clock instant on screen. An Xtream-style link names a whole minute, so the replay really
     * starts at the start of that minute ([M3uCatchUp.wholeMinutes]), not at the programme's second.
     */
    fun playheadMs(state: State, positionMs: Long): Long {
        val origin = if (M3uCatchUp.wholeMinutes(state.url)) state.streamStartMs.floorDiv(60_000L) * 60_000L else state.streamStartMs
        return origin + positionMs.coerceAtLeast(0L)
    }

    /**
     * @param next the guide's row after the one playing, or covering the playhead ([CatchUpNextProgramme])
     * @param ended the stream reached its end: that is the window running out, whatever the position says
     */
    fun react(state: State, next: Row?, positionMs: Long, ended: Boolean, nowMs: Long): Action {
        val head = maxOf(playheadMs(state, positionMs), if (ended) state.windowEndMs else Long.MIN_VALUE)
        return when (
            val decision = CatchUpContinuationPolicy.decide(
                playing = state.programme,
                next = next?.let { CatchUpContinuationPolicy.Programme(it.startMs, it.endMs) },
                playheadMs = head,
                windowEndMs = state.windowEndMs,
                nowMs = nowMs,
            )
        ) {
            CatchUpContinuationPolicy.Decision.Stay -> Action.Stay
            is CatchUpContinuationPolicy.Decision.MoveToNext ->
                Action.Retitle(next?.takeIf { it.startMs == decision.programme.startMs } ?: Row("", decision.programme.startMs, decision.programme.endMs))
            is CatchUpContinuationPolicy.Decision.StartNewReplay -> Action.Restart(decision.atMs)
            CatchUpContinuationPolicy.Decision.GoLive -> Action.GoLive
            CatchUpContinuationPolicy.Decision.Ended -> Action.End
        }
    }
}

/** Which guide row a replay's playhead is in (or about to enter) - pure, so it tests without the guide store. */
object CatchUpNextProgramme {

    /** The row covering [atMs], else the first one that starts after it; degenerate rows never count. */
    fun pick(rows: List<M3uReplayContinuation.Row>, atMs: Long): M3uReplayContinuation.Row? {
        val real = rows.filter { it.startMs > 0L && it.endMs > it.startMs }
        return real.firstOrNull { atMs >= it.startMs && atMs < it.endMs }
            ?: real.filter { it.startMs > atMs }.minByOrNull { it.startMs }
    }
}
