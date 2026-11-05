package com.nuvio.app.features.livetv

import com.nuvio.app.features.common.lifecycle.Disposable
import com.nuvio.app.features.iptv.CatchUpContinuationPolicy
import com.nuvio.app.features.iptv.CatchUpDialectWalk
import com.nuvio.app.features.iptv.M3uReplayContinuation
import com.nuvio.app.features.iptv.M3uReplayWalk
import com.nuvio.app.features.iptv.ReplayLadder

// Not Compose: the session a replay holds and how each provider's replay begins. Lives apart from LiveTvScreen so
// ReplayStart (and with it the Apple TV build, which has no Compose screen) can share it.

/**
 * One replay in progress.
 *
 * [attempt] is the dialect walk's current candidate; a new token means a new URL to try, and it is
 * what the effect keys on. [proven] latches on the first frame so a walk that has already won
 * never re-reports success, and so a stream that stops LATER reads as the recording finishing
 * rather than as this dialect failing.
 */
internal data class CatchUpSession(
    val contentId: String,
    val programmeTitle: String,
    /** The programme on screen. For a continuing replay these move on as the playhead enters the next shows. */
    val startMs: Long,
    val endMs: Long,
    val attempt: CatchUpDialectWalk.Attempt,
    val proven: Boolean = false,
    /** The instant the current LINK starts from (player position 0). Equals [startMs] until a continuing replay moves on. */
    val streamStartMs: Long = startMs,
    /** End of the window the link asked the provider for. */
    val windowEndMs: Long = endMs,
    /** A replay asks for more than its programme and follows the playhead into the next shows (every provider's does). */
    val continues: Boolean = false,
    /** Releases the replay's ask held by its walk. */
    val release: Disposable? = null,
    /** A Stalker create_link: spent after its first open. No walk, no re-open, no reconnect; Retry mints a new one. */
    val singleUse: Boolean = false,
    /** The ladder this replay's attempts are reported to, and that restarts it past its window. */
    val ladder: ReplayLadder? = null,
) {
    /**
     * Where the programme on screen starts in the player's own timeline (negative once a restarted link
     * begins part-way into it): the scrub bar's origin. Zero for any replay that has not moved on.
     */
    val programmeOffsetMs: Long get() = startMs - streamStartMs

    fun continuationState() = M3uReplayContinuation.State(
        programme = CatchUpContinuationPolicy.Programme(startMs, endMs),
        streamStartMs = streamStartMs,
        windowEndMs = windowEndMs,
        url = attempt.url,
    )
}

/**
 * Begins the catch-up walk for one replay ask and answers the session to hold, or null when
 * nothing can be replayed (no request could be built, or the walk had nothing to offer).
 *
 * The one begin path for BOTH ways into a replay — a guide cell tapped on this screen and a
 * Sports Centre replay arriving with the launch — so every replay carries the same session shape:
 * `isCatchUpPlayback` on the player surface is exactly "this returned non-null", the gates read
 * it, and the walk it started advances on transport failure.
 */
internal fun beginLaunchReplay(
    walk: CatchUpDialectWalk,
    request: CatchUpDialectWalk.Request?,
    contentId: String,
    programmeTitle: String,
    startMs: Long,
    endMs: Long,
    nowMs: Long,
    scope: com.nuvio.app.features.common.lifecycle.EffectScope? = null,
    admit: () -> Boolean = { true },
): CatchUpSession? {
    val begun = request?.let { walk.beginReplay(it, nowMs, scope, admit) } ?: return null
    return CatchUpSession(
        contentId = contentId,
        programmeTitle = programmeTitle,
        startMs = startMs,
        endMs = endMs,
        attempt = begun.attempt,
        windowEndMs = begun.windowEndMs,
        continues = true,
        release = begun.release,
        ladder = walk,
    )
}

/**
 * [beginLaunchReplay]'s M3U counterpart: starts the playlist's own link ladder and answers the session to
 * hold - flagged as continuing, with the window it asked for - or null when no link can be built.
 */
internal fun beginM3uReplay(
    walk: M3uReplayWalk,
    request: M3uReplayWalk.Request,
    contentId: String,
    programmeTitle: String,
    startMs: Long,
    endMs: Long,
    scope: com.nuvio.app.features.common.lifecycle.EffectScope? = null,
    admit: () -> Boolean = { true },
): CatchUpSession? {
    val begun = walk.begin(request, scope, admit) ?: return null
    return CatchUpSession(
        contentId = contentId,
        programmeTitle = programmeTitle,
        startMs = startMs,
        endMs = endMs,
        attempt = begun.attempt,
        windowEndMs = begun.windowEndMs,
        continues = true,
        release = begun.release,
        ladder = walk,
    )
}
