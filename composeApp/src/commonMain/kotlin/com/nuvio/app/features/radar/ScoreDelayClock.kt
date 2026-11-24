package com.nuvio.app.features.radar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * "Now" for a screen that shows delayed scores: the current time, plus ONE local re-render when the next buffered
 * score becomes due ([RadarUiState.nextScoreChangeAtMs]). No network and no ticking: the wait runs only while the
 * screen is RESUMED and is re-armed only when there is something pending.
 */
@Composable
internal fun rememberScoreNowMs(state: RadarUiState): Long {
    var tick by remember { mutableLongStateOf(0L) }
    val now = RadarTime.nowMs()
    val next = state.nextScoreChangeAtMs(now)
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(next, owner) {
        val due = next ?: return@LaunchedEffect
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            delay((due - RadarTime.nowMs()).coerceAtLeast(0L) + SCORE_DUE_SLACK_MS)
            tick = RadarTime.nowMs()
        }
    }
    // Reading [tick] subscribes this composable to the one-shot re-render.
    return maxOf(now, tick)
}

private const val SCORE_DUE_SLACK_MS = 50L
