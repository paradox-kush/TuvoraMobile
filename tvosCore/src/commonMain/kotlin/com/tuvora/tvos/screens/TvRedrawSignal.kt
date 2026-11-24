package com.tuvora.tvos.screens

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * Wakes the game screen's spoiler redraw loop(s) when their plan may have changed (new snapshot, settings change).
 * A broadcast, not a queue: every loop waiting sees every wake made after its [mark] — a conflated channel handed one
 * wake to one receiver, so a second loop slept through a new due time. A wake between [mark] and [awaitAfter] is kept.
 */
internal class TvRedrawSignal {
    private val seq = MutableStateFlow(0L)

    /** Take before planning; pass to [awaitAfter]. */
    fun mark(): Long = seq.value

    fun wake() = seq.update { it + 1 }

    suspend fun awaitAfter(mark: Long) {
        seq.first { it != mark }
    }
}
