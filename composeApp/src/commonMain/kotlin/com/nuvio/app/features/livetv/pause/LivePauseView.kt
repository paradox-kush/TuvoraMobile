package com.nuvio.app.features.livetv.pause

/**
 * What a native (non-Compose) player UI draws for live pause / rewind: [LivePauseState] flattened into plain values and
 * ready-made text, so a SwiftUI view owns no formatting and no decision (the clock text, the chip wording and which
 * buttons exist are all here, tested without a player). Apple TV's player reads this; Compose screens render
 * [LivePauseState] directly.
 *
 * Apple TV's mechanism is libmpv's own demuxer cache (ENGINE_BACK_BUFFER), so the window is what that cache holds;
 * [Kind.Preparing] and [Kind.Failed] belong to the disk-buffer mechanisms and are drawn for parity should the controller
 * ever report them.
 */
data class LivePauseView(
    val kind: Kind,
    /** The viewer paused (the play button shows Play). */
    val paused: Boolean = false,
    /** "LIVE" at the edge, "−1:05 · LIVE" when time-shifted: selecting it goes live. */
    val chipText: String = LIVE_TEXT,
    /** The playhead inside the window, 0 (oldest held) .. 1 (live edge). */
    val progress: Float = 1f,
    /** "−5:10": how far back the window reaches; the scrub bar's left label. Empty when there is no window. */
    val reachText: String = "",
    val behindMs: Long = 0L,
    val failureText: String? = null,
    /** Preparing only: when the wait must end whatever the work does (epoch ms), so the view can backstop it. */
    val preparingDeadlineEpochMs: Long = 0L,
) {
    enum class Kind {
        /** No time shift on this player (not live, or the engine cannot hold the past): the plain LIVE badge. */
        Unavailable,

        /** At the live edge, shift available. */
        Live,
        Preparing,
        Shifted,
        Failed,
    }

    val canGoLive: Boolean get() = kind == Kind.Shifted || kind == Kind.Failed

    /** The scrub bar is drawn: a shift is running and the window has width. */
    val showsScrubBar: Boolean get() = kind == Kind.Shifted && reachText.isNotEmpty()

    companion object {
        const val LIVE_TEXT = "LIVE"

        val Unavailable = LivePauseView(Kind.Unavailable)
    }
}

object LivePauseViews {
    /**
     * [available]: the pause controller drives this player (it has a mechanism for the channel and engine). A player
     * it does not drive keeps the plain badge whatever the state says.
     */
    fun of(state: LivePauseState, available: Boolean, failureText: (LivePauseFailure) -> String): LivePauseView {
        if (!available) return LivePauseView.Unavailable
        return when (state) {
            LivePauseState.Off -> LivePauseView(LivePauseView.Kind.Live)
            is LivePauseState.Preparing -> LivePauseView(
                LivePauseView.Kind.Preparing,
                paused = true,
                preparingDeadlineEpochMs = state.status.deadlineAtMs,
            )
            is LivePauseState.Failed -> LivePauseView(
                LivePauseView.Kind.Failed,
                paused = true,
                failureText = failureText(state.reason),
            )
            is LivePauseState.Shifted -> {
                val width = state.windowEndMs - state.windowStartMs
                val chip = if (state.behindLiveMs <= LivePauseUiText.AT_LIVE_MS) LivePauseView.LIVE_TEXT
                else LivePauseUiText.behind(state.behindLiveMs) + " · " + LivePauseView.LIVE_TEXT
                LivePauseView(
                    LivePauseView.Kind.Shifted,
                    paused = state.paused,
                    chipText = chip,
                    progress = if (width > 0L) ((state.positionMs - state.windowStartMs).toFloat() / width.toFloat()).coerceIn(0f, 1f) else 1f,
                    reachText = if (width > 0L) LivePauseUiText.behind(width) else "",
                    behindMs = state.behindLiveMs,
                )
            }
        }
    }
}
