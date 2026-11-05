package com.nuvio.app.features.livetv.pause

/**
 * What a live capture does with its oldest pieces. Pure, so the decision is tested without a disk.
 *  - [Trim]: live pause. A sliding window of [keepMs]; when space runs short the oldest pieces go.
 *  - [KeepAll]: a recording. Nothing is ever dropped, the playlist is EVENT, and when there is no room the
 *    capture ends as Failed(NO_SPACE) with everything recorded so far kept.
 */
sealed interface LiveRetention {
    data class Trim(val keepMs: Long) : LiveRetention
    data object KeepAll : LiveRetention

    val keepsEverything: Boolean get() = this is KeepAll

    /** Whether making room may delete the oldest piece. */
    val mayTrim: Boolean get() = this is Trim

    /** How many pieces to drop from the front; [fallbackKeepMs] applies when the disk start shrank the window. */
    fun piecesToDrop(pieceDurationsMs: List<Long>, effectiveKeepMs: Long): Int = when (this) {
        is Trim -> LivePausePolicy.piecesToDrop(pieceDurationsMs, effectiveKeepMs)
        KeepAll -> 0
    }
}
