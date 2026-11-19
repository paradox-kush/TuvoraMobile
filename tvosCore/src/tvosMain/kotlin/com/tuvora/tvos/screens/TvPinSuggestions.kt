package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.PinSuggestionState
import com.nuvio.app.features.iptv.PinSuggestions
import com.nuvio.app.features.livetv.LiveGuideChannel
import kotlinx.coroutines.flow.StateFlow

/**
 * Apple TV's face of "you keep coming back to these channels, pin them?". The decisions (what qualifies, the window, the
 * snooze, what "pin" does) are the phone's shared rules in `PinSuggestionPolicy`; this only hands the SwiftUI guide the
 * current suggestions and the four verbs. Pinning makes a channel a live favourite, which is what the guide's Favorites
 * group lists. Play counts stay on this device.
 */
object TvPinSuggestions {
    /** Changes whenever a play is counted or the switch or snooze changes; the guide re-reads [suggested] on each. */
    val changes: StateFlow<PinSuggestionState>
        get() {
            PinSuggestions.store.ensureLoaded()
            return PinSuggestions.state
        }

    /** The channels to suggest for one playlist's guide, best first (empty: nothing to offer, so no group). */
    fun suggested(accountId: String): List<LiveGuideChannel> {
        PinSuggestions.store.ensureLoaded()
        return PinSuggestions
            .suggestionsFor(PinSuggestions.state.value, com.nuvio.app.features.iptv.XtreamItemRegistry.accountPrefix(accountId))
            .map { LiveGuideChannel(contentId = it.contentId, name = it.name, logo = it.logo, streamId = 0, categoryId = null) }
    }

    /** "Pin all N": every channel [suggested] right now, in one step. Returns how many were pinned. */
    fun pinAll(accountId: String): Int {
        PinSuggestions.store.ensureLoaded()
        val shown = PinSuggestions.suggestionsFor(
            PinSuggestions.state.value, com.nuvio.app.features.iptv.XtreamItemRegistry.accountPrefix(accountId),
        )
        return PinSuggestions.pinAll(shown)
    }

    /** Leaving the Suggested group without choosing: ask again later. */
    fun notNow() = PinSuggestions.store.snooze()

    /** "Stop suggesting": the Settings switch goes off. */
    fun stop() = PinSuggestions.store.stop()

    /** The IPTV settings switch. */
    fun setEnabled(enabled: Boolean) = PinSuggestions.store.setEnabled(enabled)

    /** IPTV settings, "Clear play counts". */
    fun clearPlayCounts() = PinSuggestions.store.clearPlayCounts()
}
