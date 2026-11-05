package com.nuvio.app.features.iptv

/**
 * What changes once the thing playing is a recording rather than a live feed.
 *
 * A catch-up stream arrives down the same pipe as live and carries the same `streamType = "live"`
 * (the Android engine selection depends on it), so the difference is a flag carried BESIDE the
 * content type rather than a new type — StreamVault's shape, and the one that doesn't ripple
 * through every `contentType` comparison in the app. Every live-only behaviour then reads
 * `live && !isCatchUpPlayback`.
 *
 * Pure policy: the gates, the scrub clamp and the failure classification are all decidable from
 * values, so they are pinned by tests rather than by playing a real replay on someone's account.
 */
object CatchUpPlayback {

    /**
     * How far short of "now" the scrub bar's right edge stops.
     *
     * The segments either side of the live edge have not been written yet — iptvsimple refuses
     * seeks within 1–2 minutes of it for the same reason. A seek past this asks the panel for
     * something that does not exist, which reads to the viewer as the replay breaking.
     */
    const val LIVE_EDGE_GUARD_MS = 2 * 60_000L

    // --- the gates ------------------------------------------------------------------------

    /**
     * Whether changing channel is allowed. During a replay it is not: the viewer is part-way
     * through a programme they chose deliberately, and there is no way back to their place.
     */
    fun allowsChannelChange(isCatchUpPlayback: Boolean): Boolean = !isCatchUpPlayback

    /**
     * Whether a replay session still belongs to the channel on screen.
     *
     * A replay is bound to ONE channel: its URL carries that channel's stream id and the window it
     * was minted for. So if the screen's channel has moved on, the session is stale and the player
     * must not keep serving it — the viewer would be watching one channel's recording under
     * another channel's name, with that channel's guide row highlighted.
     *
     * This is the INVARIANT behind [allowsChannelChange], enforced where the two pieces of state
     * meet rather than trusted to each caller. It exists because a caller CAN get the flag wrong:
     * a Compose click handler that outlived its composition asks "am I catching up?" and is
     * answered by the composition it was born in, so the tear-down never runs and exactly this
     * mismatched state reaches the screen.
     */
    fun sessionSurvivesChannel(sessionContentId: String, currentContentId: String): Boolean =
        sessionContentId == currentContentId

    /**
     * Whether the freeze watchdog arms.
     *
     * It reports `live_playback_freeze` and recovers by re-resolving the URL. Against a replay
     * both are wrong: its `Kind.ENDED` rule exists because "a live channel has no end", but a
     * recording ending is the recording finishing — so it would report a fault on every successful
     * replay and spend a provider connection re-minting a URL that was never broken.
     */
    fun armsFreezeWatchdog(isCatchUpPlayback: Boolean): Boolean = !isCatchUpPlayback

    // --- the scrub bar --------------------------------------------------------------------

    /**
     * Whether the served container can be scrubbed.
     *
     * Not ours to decide: a panel answering `.m3u8` sends a playlist carrying every segment and
     * its duration, so it seeks; a panel answering `.ts` sends a progressive stream with no
     * duration and usually no byte ranges, so it does not. Same programme, same app, different
     * provider — which is why the UI needs a flat no-scrub treatment rather than a dead handle.
     *
     * Unknown reads as NOT seekable: an absent bar is a provider fact, a handle that ignores drags
     * is a broken app.
     */
    fun isSeekable(url: String?, singleUse: Boolean = false): Boolean {
        // A scrub is a new Range request; a single-use link answers only its first open.
        if (singleUse) return false
        val u = url?.trim()?.lowercase() ?: return false
        if (u.isEmpty()) return false
        val withoutFragment = u.substringBefore('#')
        return withoutFragment.substringBefore('?').endsWith(".m3u8") ||
            EXTENSION_PARAM.containsMatchIn(withoutFragment)
    }

    /**
     * The furthest point in the programme the viewer may seek to, in milliseconds from its start.
     *
     * A finished programme is wholly recorded, so the whole duration is available. One still
     * airing only exists up to now, minus [LIVE_EDGE_GUARD_MS] for the segments the panel has not
     * written — and never below zero, because a programme that just started has nothing to seek.
     */
    fun maxSeekPositionMs(programmeStartMs: Long, programmeEndMs: Long, nowMs: Long): Long {
        val duration = (programmeEndMs - programmeStartMs).coerceAtLeast(0L)
        if (programmeEndMs <= nowMs) return duration
        val aired = nowMs - programmeStartMs - LIVE_EDGE_GUARD_MS
        return aired.coerceIn(0L, duration)
    }

    // --- failure classification -----------------------------------------------------------

    /**
     * Which kind of failure the player just reported, for [CatchUpDialectWalk.onFailure].
     *
     * TRANSPORT means the URL shape may be wrong, so the walk advances. DECODE means the URL
     * reached a stream whose content is broken — every other shape replays the same broken
     * recording, so walking on only burns attempts (and on a max_connections=1 account, the
     * viewer's single slot).
     *
     * Anything unrecognised counts as TRANSPORT. The dominant real failure IS the wrong URL
     * shape, the walk terminates by itself once the ladder is exhausted, and guessing DECODE would
     * declare a programme unavailable that the panel would have served in another form.
     */
    fun failureKind(error: String?): CatchUpDialectWalk.FailureKind {
        val e = error?.lowercase().orEmpty()
        val decodeShaped = DECODE_MARKERS.any { it in e }
        return if (decodeShaped) CatchUpDialectWalk.FailureKind.DECODE
        else CatchUpDialectWalk.FailureKind.TRANSPORT
    }

    /**
     * Whether a Stalker portal answered `{error: 'limit'}` — the account's concurrent-session cap,
     * not a fault in the app or the recording. It deserves its own message: retrying cannot help,
     * closing another device can, and a generic playback error sends people to Discord blaming
     * Tuvora for their subscription's connection limit.
     *
     * Matched as the error FIELD, never the bare word: channel and programme names contain
     * anything at all.
     */
    fun isSessionLimit(raw: String?): Boolean {
        val r = raw?.lowercase() ?: return false
        return LIMIT_ERROR.containsMatchIn(r)
    }

    private val EXTENSION_PARAM = Regex("""[?&]extension=m3u8\b""")

    private val LIMIT_ERROR = Regex("""["']?error["']?\s*[:=]\s*["']limit["']""")

    private val DECODE_MARKERS = listOf(
        "decoder init",
        "decoder_init",
        "decoding failed",
        "decoderinitializationexception",
        "unsupported codec",
        "unsupported format",
        "renderer error",
        "no decoder",
    )
}
