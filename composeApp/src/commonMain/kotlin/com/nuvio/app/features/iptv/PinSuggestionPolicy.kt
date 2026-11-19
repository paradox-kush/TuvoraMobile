package com.nuvio.app.features.iptv

import com.nuvio.app.core.contracts.LiveChannelNames
import kotlinx.serialization.Serializable

/**
 * One live channel's play history on THIS device: when it was played (epoch ms, oldest first, newest
 * [LivePlayCountsPolicy.MAX_PLAYS_PER_CHANNEL] kept) plus the name and logo to draw it with. Never synced and never
 * sent to analytics; "Clear play counts" in IPTV settings wipes it.
 */
@Serializable
data class PlayedChannel(
    val contentId: String,
    val name: String,
    val logo: String? = null,
    val plays: List<Long> = emptyList(),
) {
    val lastPlayedMs: Long get() = plays.maxOrNull() ?: 0L
}

/** Everything the pin-suggestion feature keeps for one profile, on the device only. */
@Serializable
data class PinSuggestionState(
    /** The "Suggest channels to pin" switch. "Stop suggesting" turns it off too, so Settings always shows the truth. */
    val enabled: Boolean = true,
    /** "Not now": nothing is suggested before this time. 0 = not snoozed. */
    val snoozedUntilMs: Long = 0L,
    val channels: List<PlayedChannel> = emptyList(),
)

/**
 * Pure bookkeeping of the play counts: record a play, keep the store small, clear it. No clock, no storage, so it
 * tests without either (house pattern: [XtreamLiveRecents.recorded]).
 */
internal object LivePlayCountsPolicy {
    /** Channels kept, most recently played first; the rest are forgotten. */
    const val MAX_CHANNELS = 50

    /** Plays kept per channel (newest). Well above [PinSuggestionPolicy.MIN_PLAYS], so the cap never hides a qualifier. */
    const val MAX_PLAYS_PER_CHANNEL = 10

    /**
     * A channel opened again within this gap is the same sitting (a reconnect, a flick away and back), so it adds no
     * play: three plays means three separate times, not one channel that dropped twice.
     */
    const val SAME_SITTING_MS = 10 * 60 * 1000L

    /**
     * [current] with a play of [contentId] at [nowMs]. Plays older than the suggestion window are dropped (they can
     * never count again), channels left with none are forgotten, and the list is capped at [MAX_CHANNELS]. A launch
     * titled with the generic fallback keeps the name the channel already had.
     */
    fun recorded(
        current: List<PlayedChannel>,
        contentId: String,
        name: String,
        logo: String?,
        nowMs: Long,
    ): List<PlayedChannel> {
        val prior = current.firstOrNull { it.contentId == contentId }
        val priorLast = prior?.lastPlayedMs
        val sameSitting = priorLast != null && nowMs - priorLast in 0 until SAME_SITTING_MS
        val plays = if (sameSitting) prior!!.plays else (prior?.plays.orEmpty() + nowMs)
        val entry = PlayedChannel(
            contentId = contentId,
            name = LiveChannelNames.best(name, prior?.name) ?: name,
            logo = logo ?: prior?.logo,
            plays = plays.sorted().takeLast(MAX_PLAYS_PER_CHANNEL),
        )
        val windowStart = nowMs - PinSuggestionPolicy.WINDOW_MS
        return (current.filterNot { it.contentId == contentId } + entry)
            .map { channel -> channel.copy(plays = channel.plays.filter { it >= windowStart }) }
            .filter { it.plays.isNotEmpty() }
            .sortedWith(compareByDescending<PlayedChannel> { it.lastPlayedMs }.thenBy { it.contentId })
            .take(MAX_CHANNELS)
    }

    /** "Clear play counts": every count gone. The switch and any snooze are choices, not counts, and stay. */
    fun cleared(state: PinSuggestionState): PinSuggestionState = state.copy(channels = emptyList())
}

/**
 * When to offer "you keep coming back to these, pin them?". Pure and clock-free (the caller passes `nowMs`), so every
 * threshold tests without the player, the network or the UI.
 *
 * A channel qualifies with at least [MIN_PLAYS] separate plays in the last [WINDOW_DAYS] days. Channels the viewer has
 * already pinned are left out ([PinSuggestionPolicy.suggest]'s `skip`, supplied by the pin port), the best [MAX_SUGGESTIONS]
 * are offered, and nothing is offered unless at least [MIN_SUGGESTIONS] qualify (one channel is not a pattern). The
 * viewer's own choices always win: switch off, "Stop suggesting", or a "Not now" that snoozes for [SNOOZE_DAYS] days.
 */
internal object PinSuggestionPolicy {
    const val MIN_PLAYS = 3
    const val WINDOW_DAYS = 14
    const val MIN_SUGGESTIONS = 2
    const val MAX_SUGGESTIONS = 4
    const val SNOOZE_DAYS = 14

    private const val DAY_MS = 24 * 60 * 60 * 1000L
    const val WINDOW_MS = WINDOW_DAYS * DAY_MS
    const val SNOOZE_MS = SNOOZE_DAYS * DAY_MS

    /** Plays of [channel] inside the window ending at [nowMs] (a play stamped in the future by a clock change is ignored). */
    fun playsInWindow(channel: PlayedChannel, nowMs: Long): Int =
        channel.plays.count { it in (nowMs - WINDOW_MS)..nowMs }

    /**
     * The channels to suggest right now, best first: most plays in the window, then most recently played. Empty when
     * the switch is off, a "Not now" is still running, or fewer than [MIN_SUGGESTIONS] qualify. [skip] says a channel
     * is not eligible here (already pinned, or not on the playlist being shown).
     */
    fun suggest(
        state: PinSuggestionState,
        nowMs: Long,
        skip: (String) -> Boolean = { false },
    ): List<PlayedChannel> {
        if (!state.enabled) return emptyList()
        if (nowMs < state.snoozedUntilMs) return emptyList()
        val ranked = state.channels
            .filter { playsInWindow(it, nowMs) >= MIN_PLAYS && !skip(it.contentId) }
            .sortedWith(
                compareByDescending<PlayedChannel> { playsInWindow(it, nowMs) }
                    .thenByDescending { it.lastPlayedMs }
                    .thenBy { it.contentId },
            )
            .take(MAX_SUGGESTIONS)
        return if (ranked.size >= MIN_SUGGESTIONS) ranked else emptyList()
    }

    /** "Not now": ask again no sooner than [SNOOZE_DAYS] days from [nowMs]. */
    fun snoozed(state: PinSuggestionState, nowMs: Long): PinSuggestionState =
        state.copy(snoozedUntilMs = nowMs + SNOOZE_MS)

    /** "Stop suggesting": the switch goes off (the viewer turns it back on in IPTV settings). */
    fun stopped(state: PinSuggestionState): PinSuggestionState = state.copy(enabled = false)

    /** The Settings switch. Turning it back on starts clean: an old snooze must not hide what the viewer just asked for. */
    fun withEnabled(state: PinSuggestionState, enabled: Boolean): PinSuggestionState =
        if (enabled) state.copy(enabled = true, snoozedUntilMs = 0L) else state.copy(enabled = false)
}

/**
 * Where "Pin" lands. Tuvora already has one place that puts channels at the top of Live TV for the viewer: a live
 * FAVOURITE (the library's "tv" entries: the first rows of the IPTV hub and the guide, in a synced order, with a
 * long-press and hold-OK toggle on every platform). Pinning a suggestion is making it a favourite; there is no third
 * list. This port is that seam, so the action tests with a fake.
 */
internal interface LiveChannelPinPort {
    /** True when [contentId] is already at the top of Live TV for this viewer (favourited, or pinned in the overlay). */
    fun isPinned(contentId: String): Boolean

    /** Puts [channel] at the top of Live TV. Idempotent. */
    fun pin(channel: PlayedChannel)
}

/** The "Pin" and "Pin all" actions on suggested channels. */
internal object PinSuggestionActions {
    /** Pins one channel. False (and no write) when it is already pinned. */
    fun pin(port: LiveChannelPinPort, channel: PlayedChannel): Boolean {
        if (port.isPinned(channel.contentId)) return false
        port.pin(channel)
        return true
    }

    /**
     * "Pin all N": every suggested channel not already pinned, in one step. The best-ranked is written LAST, so it
     * lands first among them (a new favourite goes to the top). Returns how many were pinned.
     */
    fun pinAll(port: LiveChannelPinPort, suggested: List<PlayedChannel>): Int {
        val toPin = suggested.distinctBy { it.contentId }.filterNot { port.isPinned(it.contentId) }
        toPin.asReversed().forEach(port::pin)
        return toPin.size
    }
}
