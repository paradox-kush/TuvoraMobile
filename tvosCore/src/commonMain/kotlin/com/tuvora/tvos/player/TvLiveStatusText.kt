package com.tuvora.tvos.player

import com.nuvio.app.core.analytics.LiveReconnectReason
import com.nuvio.app.core.analytics.LiveReconnectReasonPolicy
import com.nuvio.app.core.analytics.LiveReconnectStatus
import com.nuvio.app.features.player.StreamHttpStatusHintPolicy
import com.nuvio.app.features.player.StreamHttpStatusHintPolicy.Hint

/**
 * Apple TV's words for the shared reconnect status ([LiveReconnectStatus]) and HTTP status hints
 * ([StreamHttpStatusHintPolicy]): the same sentences as the phone's string resources, kept here beside the
 * facade because Apple TV's SwiftUI screens carry their own English copy (as [TvLiveFailureReason]).
 * Pure: the engine text is only classified, never returned (it can carry a host or a token).
 */
object TvLiveStatusText {

    /** "Reconnecting… (attempt N of M)". */
    fun headline(status: LiveReconnectStatus): String =
        "Reconnecting… (attempt ${status.attempt} of ${status.maxAttempts})"

    fun reason(reason: LiveReconnectReason): String = when (reason) {
        LiveReconnectReason.PROVIDER_FIREWALL -> "Provider firewall blocked the stream"
        LiveReconnectReason.CONNECTION_LIMIT -> "Connection limit reached on this account"
        LiveReconnectReason.STREAM_NOT_FOUND -> "Stream not found"
        LiveReconnectReason.PROVIDER_SERVER_ERROR -> "Provider server error"
        LiveReconnectReason.UNREACHABLE -> "Can't reach the provider"
        LiveReconnectReason.STREAM_STOPPED -> "Stream stopped"
    }

    /** The headline and the reason on two lines, as the player card shows them. */
    fun text(status: LiveReconnectStatus): String = headline(status) + "\n" + reason(status.reason)

    fun hint(hint: Hint): String = when (hint) {
        Hint.PROVIDER_FIREWALL ->
            "Your provider's firewall blocked this stream (HTTP 456). Set a custom User-Agent on this playlist (for example VLC/3.0.20), then try again."
        Hint.CONNECTION_LIMIT ->
            "Too many connections on this account. Close the stream on another device, or try again in a minute."
        Hint.EXPIRED -> "The stream link has expired. Try a different source."
        Hint.REMOVED -> "The stream link has expired or been removed. Try a different source."
        Hint.RATE_LIMITED -> "Too many requests to the stream source. Wait a moment and try again."
        Hint.SERVER_UNAVAILABLE -> "The stream server is currently unavailable. Try a different source."
        Hint.BLOCKED -> "The stream source is blocked or restricted. Try a different source."
    }

    /**
     * Whether [snapshot] shows the reconnected stream playing: a frame counter that moved off [ticksAtReconnect] (a
     * frozen picture's counter stands still; a reloaded engine restarts it) while playing and not loading or ended.
     * Known gap: an engine that keeps the old counter and resumes the SAME value cannot be told apart for one sample.
     */
    fun playingAgain(snapshot: com.nuvio.app.features.player.PlayerPlaybackSnapshot, ticksAtReconnect: Long): Boolean =
        snapshot.isPlaying && !snapshot.isLoading && !snapshot.isEnded &&
            // A radio channel has no frames to count: playing and not loading is all it can show.
            (!snapshot.hasVideoTrack || (snapshot.videoProgressTicks > 0L && snapshot.videoProgressTicks != ticksAtReconnect))

    /** The hint for the HTTP status an engine's failure text names, or null when it names none. */
    fun hintFor(engineError: String?): String? =
        LiveReconnectReasonPolicy.httpStatusIn(engineError)?.let(StreamHttpStatusHintPolicy::hintFor)?.let(::hint)
}
