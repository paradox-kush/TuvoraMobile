package com.tuvora.tvos.player

/** A second OK must preserve the pending preview and carry fullscreen intent to its completion. */
enum class TvGuideSelectionAction { Preview, Fullscreen, AwaitFullscreen }

object TvGuideSelectionPolicy {
    fun decide(sameChannel: Boolean, hasSession: Boolean): TvGuideSelectionAction =
        when {
            !sameChannel -> TvGuideSelectionAction.Preview
            hasSession -> TvGuideSelectionAction.Fullscreen
            else -> TvGuideSelectionAction.AwaitFullscreen
        }
}
