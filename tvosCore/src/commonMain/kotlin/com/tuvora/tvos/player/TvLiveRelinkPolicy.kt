package com.tuvora.tvos.player

import com.nuvio.app.features.player.LiveLinkReconnectPolicy

/**
 * What an Apple TV live reconnect opens. A Stalker `create_link` URL is spent after its first open
 * (the portal 401s it), so a reconnect that replays it can never recover the channel: it must mint a
 * fresh link, or open nothing and let the bounded ladder ([TvLiveMonitor]) end in Failed + Retry.
 * Xtream / M3U addresses are stable, so replaying them is a real reconnect.
 *
 * The pacing is not decided here: [TvLiveMonitor] (the shared freeze / recovery policies) says WHEN
 * a reconnect is due, never faster than its backoff; this only says WHAT that reconnect opens.
 */
object TvLiveRelinkPolicy {

    enum class Step {
        /** Ask the session's re-resolver for a fresh link. */
        Remint,

        /** Open the link the re-mint just returned. */
        OpenFresh,

        /** Re-open the address already playing (a stable Xtream / M3U URL). */
        Replay,

        /** Open nothing: the ladder's next attempt (or its GiveUp) decides what happens next. */
        Skip,
    }

    /** A reconnect is due. [remintInFlight]: an earlier re-mint has not answered yet. */
    fun onReconnect(spentLink: Boolean, canRemint: Boolean, remintInFlight: Boolean): Step = when {
        // One create_link at a time: the ladder's next tick must not stack a second mint on the first.
        remintInFlight -> Step.Skip
        canRemint -> Step.Remint
        spentLink -> Step.Skip
        else -> Step.Replay
    }

    /** The re-mint answered; [gotFresh] is false when it returned no link (failed, timed out). */
    fun afterRemint(spentLink: Boolean, gotFresh: Boolean): Step = when {
        gotFresh -> Step.OpenFresh
        spentLink -> Step.Skip
        else -> Step.Replay
    }

    /**
     * Whether a failed engine may hand the launch's address to the next engine lane. Never for a spent
     * link: the next engine would open the same dead URL; the reconnect ladder mints a fresh one instead.
     */
    fun mayEscalateLane(spentLink: Boolean): Boolean =
        LiveLinkReconnectPolicy.allowsEngineFailover(LiveLinkReconnectPolicy.modeFor(spentLink))

    /**
     * The error card's text for a live channel the ladder gave up on. The engine's own text (mpv's
     * "Failed to open <url>") carries the stream address and its play token: it goes to the log,
     * redacted, never to the screen.
     */
    fun viewerMessage(engineError: String?): String =
        if (engineError.isNullOrBlank()) CHANNEL_STOPPED else CHANNEL_UNREACHABLE

    /**
     * Whether the engine's "ended" is the previous link's leftover. mpv keeps reporting the old
     * file's end (keep-open) until the fresh link it was handed starts loading; read as a new end, the
     * ladder would mint a second link two seconds after the first. [sinceFreshOpenMs] is null when no
     * fresh link is pending; the grace is bounded, so a fresh link that never starts still ends the wait.
     */
    fun isStaleEnd(isEnded: Boolean, sinceFreshOpenMs: Long?): Boolean =
        isEnded && sinceFreshOpenMs != null && sinceFreshOpenMs < FRESH_OPEN_GRACE_MS

    /** As the freeze policy's startup grace: long enough for an HLS open, short of a real outage. */
    const val FRESH_OPEN_GRACE_MS = 8_000L

    const val CHANNEL_STOPPED = "The channel stopped responding"
    const val CHANNEL_UNREACHABLE = "Couldn't reach this channel"

    /** A re-mint that never answers must not hold the single-flight slot forever. */
    const val REMINT_TIMEOUT_MS = 30_000L
}
