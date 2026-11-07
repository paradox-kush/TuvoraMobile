package com.tuvora.tvos.screens

import com.nuvio.app.features.sportsdetail.api.DetailLoad
import com.nuvio.app.features.sportsdetail.api.GameDetail

/**
 * What the Apple TV game-detail screen renders. Loading / Loaded / Empty / Failed: a load always ends
 * (repo rule "every loading state has a deadline and a terminal outcome"); a failure is never "empty".
 */
sealed interface TvGameDetailState {
    data object Loading : TvGameDetailState
    /** [stale]: the last refresh failed, so the numbers are the previous good ones (the screen says so). */
    data class Loaded(val detail: GameDetail, val stale: Boolean = false) : TvGameDetailState
    /** No such game in the sources: Swift falls back to the existing match sheet. */
    data object Empty : TvGameDetailState
    data class Failed(val timedOut: Boolean) : TvGameDetailState
}

/** Pure fold of a tick result into the screen state (no I/O, so it tests without the network). */
object TvGameDetailReducer {
    fun reduce(previous: TvGameDetailState, load: DetailLoad): TvGameDetailState = when (load) {
        is DetailLoad.Loaded -> TvGameDetailState.Loaded(load.detail)
        // Once we have a game on screen, a later miss keeps it (marked stale) instead of blanking the screen.
        is DetailLoad.Empty -> if (previous is TvGameDetailState.Loaded) previous.copy(stale = true) else TvGameDetailState.Empty
        is DetailLoad.Failed ->
            if (previous is TvGameDetailState.Loaded) previous.copy(stale = true) else TvGameDetailState.Failed(load.timedOut)
    }

    /** Consecutive-failure count for the refresh policy's backoff. */
    fun failures(previousFailures: Int, load: DetailLoad): Int =
        if (load is DetailLoad.Loaded) 0 else previousFailures + 1
}
