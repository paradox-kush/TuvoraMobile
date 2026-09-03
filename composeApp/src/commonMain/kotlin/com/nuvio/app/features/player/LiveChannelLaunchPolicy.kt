package com.nuvio.app.features.player

/**
 * Decides what a Live TV channel launch does once its stream URL has (or hasn't) resolved.
 *
 * By the time the async resolve returns, the channel picker (the Sports sheet, the guide, a card)
 * has already been dismissed — so a failed resolve that just returns is indistinguishable from a
 * frozen app. Keeping the decision here, pure and tested, makes "a channel tap can never fail
 * silently" an invariant rather than a line of App.kt someone can quietly drop: a null-or-blank URL
 * must produce [Outcome.Feedback], never a no-op.
 */
object LiveChannelLaunchPolicy {
    sealed interface Outcome {
        /** Resolution produced a usable URL — play it. */
        data class Launch(val url: String) : Outcome

        /** Resolution failed (null or blank) — surface a message instead of returning silently. */
        data object Feedback : Outcome
    }

    /** A blank URL is as unusable as a null one — it would hand the player an empty source. */
    fun resolutionOutcome(resolvedUrl: String?): Outcome =
        if (!resolvedUrl.isNullOrBlank()) Outcome.Launch(resolvedUrl) else Outcome.Feedback
}
