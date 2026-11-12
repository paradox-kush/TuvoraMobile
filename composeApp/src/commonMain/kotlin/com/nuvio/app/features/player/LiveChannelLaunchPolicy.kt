package com.nuvio.app.features.player

import com.nuvio.app.core.contracts.LivePlaybackProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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

    /**
     * Who resolves a tune's stream link, decided BEFORE anything is minted (B257).
     *
     * The Live TV screen ALWAYS resolves the channel itself when it opens (LiveTvScreen's resolve effect,
     * or the replay walk for a Sports replay) — the launch payload's URL is never played. So a resolve at
     * launch is a second one. For a reusable link (Xtream, M3U) that costs a lookup and buys the
     * "channel unavailable" toast before the screen opens. For a single-use link (a Stalker
     * `create_link`) it burns a play token and, on a `max_connections=1` panel, the viewer's one slot —
     * so the screen's resolve must be the ONLY one: one tune, one mint.
     */
    sealed interface Plan {
        /** A usable URL is already in hand: launch with it, nothing resolves here. */
        data class LaunchNow(val url: String) : Plan

        /** A reusable link: resolve here (it doubles as the unavailable check), then launch. */
        data object ResolveThenLaunch : Plan

        /**
         * A single-use link: launch with NO url — the Live TV screen's own bounded resolve mints the one link
         * this tune spends, and a failure there ends as its error pill + Retry (never a silent no-op).
         */
        data object LaunchScreenResolves : Plan
    }

    fun planFor(knownUrl: String?, singleUseLink: Boolean): Plan = when {
        !knownUrl.isNullOrBlank() -> Plan.LaunchNow(knownUrl)
        singleUseLink -> Plan.LaunchScreenResolves
        else -> Plan.ResolveThenLaunch
    }
}

/**
 * The live-channel launch App.kt runs for every hub / Library / Search / Sports tap: [LiveChannelLaunchPolicy]'s
 * decisions with the effects injected, so a test can count the resolver calls one tune makes (B257).
 */
object LiveChannelLauncher {
    /** The payload URL of a launch whose link the Live TV screen mints itself (see [LiveChannelLaunchPolicy.Plan]). */
    const val SCREEN_RESOLVES_URL: String = ""

    fun tune(
        provider: LivePlaybackProvider,
        contentId: String,
        knownUrl: String?,
        scope: CoroutineScope,
        onLaunch: (sourceUrl: String) -> Unit,
        onUnavailable: () -> Unit,
    ) {
        val known = knownUrl?.takeIf { it.isNotBlank() } ?: provider.liveStreamUrlFor(contentId)
        when (val plan = LiveChannelLaunchPolicy.planFor(known, provider.liveLinkIsSingleUse(contentId))) {
            is LiveChannelLaunchPolicy.Plan.LaunchNow -> onLaunch(plan.url)
            LiveChannelLaunchPolicy.Plan.LaunchScreenResolves -> onLaunch(SCREEN_RESOLVES_URL)
            LiveChannelLaunchPolicy.Plan.ResolveThenLaunch -> scope.launch {
                // The picker is already gone, so a failed resolve must say so rather than return silently.
                when (val outcome = LiveChannelLaunchPolicy.resolutionOutcome(provider.liveStreamUrlForAsync(contentId))) {
                    is LiveChannelLaunchPolicy.Outcome.Launch -> onLaunch(outcome.url)
                    LiveChannelLaunchPolicy.Outcome.Feedback -> onUnavailable()
                }
            }
        }
    }
}
