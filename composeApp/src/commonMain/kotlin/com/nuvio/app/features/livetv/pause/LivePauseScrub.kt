package com.nuvio.app.features.livetv.pause

import com.nuvio.app.features.player.LiveSeekWindow

/**
 * What a remote's left / right arrow does on a live channel that can be time-shifted (Apple TV's Siri Remote, a
 * keyboard, any D-pad): step through the window the engine holds. Pure, so every platform's arrow handling agrees
 * and none needs a player to be tested.
 *
 *  - At live ([LivePauseState.Off]) only "back" does anything: it starts the shift (the controller pauses to open the
 *    window) and lands [STEP_MS] behind. Nothing happens when the engine holds no past yet, or the playhead is already
 *    at the start of it.
 *  - Time-shifted ([LivePauseState.Shifted]) both directions scrub inside the window; stepping forward into the last
 *    [edgeSlackMs] of it goes live instead of parking just short of the edge.
 *  - Starting or failed: nothing to scrub, so [Step.Ignore].
 */
object LivePauseScrub {
    const val STEP_MS = 10_000L

    /** Less past than this behind the playhead is not worth a shift. */
    const val MIN_ROOM_MS = 1_000L

    sealed interface Step {
        data object Ignore : Step
        data object GoLive : Step

        /**
         * Seek to [targetMs] (the window's clock). [pauseFirst]: open the shift first (state was Off). [resume]: play on
         * after the seek (the viewer was watching), otherwise stay paused.
         */
        data class Seek(val targetMs: Long, val pauseFirst: Boolean, val resume: Boolean) : Step
    }

    fun step(
        state: LivePauseState,
        engineWindow: LiveSeekWindow?,
        deltaMs: Long,
        wasPlaying: Boolean,
        edgeSlackMs: Long = LivePauseUiText.AT_LIVE_MS,
    ): Step = when (state) {
        LivePauseState.Off -> {
            val window = engineWindow
            val room = if (window == null) 0L else window.positionMs - window.startMs
            if (deltaMs >= 0L || window == null || room < MIN_ROOM_MS) Step.Ignore
            else Step.Seek((window.positionMs + deltaMs).coerceAtLeast(window.startMs), pauseFirst = true, resume = wasPlaying)
        }
        is LivePauseState.Shifted -> {
            if (state.windowEndMs <= state.windowStartMs) Step.Ignore
            else {
                val target = (state.positionMs + deltaMs).coerceIn(state.windowStartMs, state.windowEndMs)
                when {
                    deltaMs > 0L && state.windowEndMs - target <= edgeSlackMs -> Step.GoLive
                    target == state.positionMs -> Step.Ignore
                    else -> Step.Seek(target, pauseFirst = false, resume = false)
                }
            }
        }
        is LivePauseState.Preparing, is LivePauseState.Failed -> Step.Ignore
    }
}
