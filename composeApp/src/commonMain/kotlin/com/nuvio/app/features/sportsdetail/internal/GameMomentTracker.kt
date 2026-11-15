package com.nuvio.app.features.sportsdetail.internal

import com.nuvio.app.features.sportsdetail.api.GameDetail

/** One diff result. [seq] changes only when a NEW batch was produced, so the UI animates each batch exactly once. */
internal data class MomentBatch(val seq: Long, val moments: List<Moment>)

/**
 * Holds the previous snapshot and turns each new one into moments via [GameMomentPolicy]. Pure state, no I/O.
 * The screen calls [reset] when it resumes so the first snapshot afterwards is treated as `prev = null` (nothing replays).
 */
internal class GameMomentTracker {
    private var prev: GameDetail? = null
    private var seq = 0L

    fun accept(next: GameDetail): MomentBatch {
        val moments = GameMomentPolicy.diff(prev, next)
        prev = next
        if (moments.isNotEmpty()) seq++
        return MomentBatch(seq, moments)
    }

    fun reset() {
        prev = null
    }
}
