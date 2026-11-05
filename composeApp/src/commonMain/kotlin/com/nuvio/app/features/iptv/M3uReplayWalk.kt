package com.nuvio.app.features.iptv

/**
 * Walks the candidate links of ONE M3U catch-up replay until one plays: the Xtream walk's
 * counterpart for a playlist that says how to ask for its own archive ([M3uCatchUp]).
 *
 * The first link is [M3uCatchUp.build]'s; the others are [M3uCatchUp.alternatives]; the link FORM that
 * played last time on this playlist ([M3uCatchUp.linkForm]) leads, via [M3uCatchUp.preferForm]. As with
 * the Xtream walk, only a TRANSPORT-shaped failure could be a wrong link shape; a DECODE failure reached
 * a stream, so another shape would replay the same broken recording and the walk ends.
 *
 * Pure policy (no network, no persistence): [FormMemory] is injected and the caller performs each attempt.
 * Reports reuse the Xtream walk's [CatchUpDialectWalk.Step] / [CatchUpDialectWalk.Attempt] so the screen
 * handles both the same way. Drive it from one dispatcher; the state is deliberately unsynchronised.
 */
class M3uReplayWalk(private val memory: FormMemory) {

    /** What played last, per playlist. Injected storage; the policy decides when to read and write. */
    interface FormMemory {
        fun recallM3uForm(accountId: String): String?
        fun rememberM3uForm(accountId: String, form: String)
    }

    /** One replay ask: the channel's own live URL and catch-up, and the programme to play. */
    data class Request(
        val accountId: String,
        val info: M3uCatchUpInfo,
        val liveUrl: String,
        val programmeStartMs: Long,
        val programmeEndMs: Long,
        val nowMs: Long,
        /** The playlist's catch-up correction, as an offset in ms (the clock the bare local-time placeholders mean). */
        val offsetMs: Long = 0L,
    )

    /** A replay that can start: its first attempt, and where the window it asked the provider for ends (wall clock). */
    data class Begun(val attempt: CatchUpDialectWalk.Attempt, val windowEndMs: Long)

    private class Ladder(val request: Request, val primary: String, val urls: List<String>) {
        var index = 0
        var token = 0L

        /** Played: the proof is recorded and failures no longer walk, but the ask stays so a continuing replay can [restartAt]. */
        var settled = false
        fun attempt() = CatchUpDialectWalk.Attempt(token, CatchUpDialectWalk.Dialect.PLAYLIST_LINK, urls[index])
    }

    private val ladders = HashMap<String, Ladder>()
    private var tokenSeq = 0L

    /**
     * Starts the walk for [request], superseding any earlier one for the playlist. The window asked for is
     * the programme plus what follows it ([CatchUpContinuationPolicy.replayWindowEndMs]) so the next shows
     * play on. Null when the playlist's style cannot be built into a link, or the programme is degenerate.
     */
    fun begin(request: Request): Begun? {
        if (request.programmeStartMs <= 0L || request.programmeEndMs <= request.programmeStartMs) return null
        if (request.liveUrl.isBlank()) return null
        val windowEnd = CatchUpContinuationPolicy.replayWindowEndMs(
            CatchUpContinuationPolicy.Programme(request.programmeStartMs, request.programmeEndMs), request.nowMs,
        )
        val primary = M3uCatchUp.build(
            request.info, request.liveUrl, request.programmeStartMs, windowEnd, request.nowMs, request.offsetMs,
        ) ?: return null
        val airing = request.programmeEndMs > request.nowMs
        val alternatives = M3uCatchUp.alternatives(
            url = primary,
            offsetMs = request.offsetMs,
            showMinutes = (request.programmeEndMs - request.programmeStartMs) / 60_000L,
            airedMinutes = if (airing) (request.nowMs - request.programmeStartMs) / 60_000L else null,
        )
        return start(request, primary, alternatives, windowEnd)
    }

    /**
     * The replay ran past its window: a fresh one starting at [atMs] (the playhead), capped at what has
     * aired. [token] must name the replay's current attempt. Null when the replay is gone or no link can be built.
     */
    fun restartAt(token: Long, atMs: Long, nowMs: Long): Begun? {
        val old = ladderFor(token) ?: return null
        val request = old.request.copy(programmeStartMs = atMs, programmeEndMs = atMs + 1L, nowMs = nowMs)
        val windowEnd = CatchUpContinuationPolicy.replayWindowEndMs(
            CatchUpContinuationPolicy.Programme(atMs, atMs + 1L), nowMs,
        )
        val primary = M3uCatchUp.build(request.info, request.liveUrl, atMs, windowEnd, nowMs, request.offsetMs) ?: return null
        return start(request, primary, M3uCatchUp.alternatives(url = primary, offsetMs = request.offsetMs), windowEnd)
    }

    private fun start(request: Request, primary: String, alternatives: List<String>, windowEnd: Long): Begun {
        val urls = M3uCatchUp.preferForm(primary, alternatives, memory.recallM3uForm(request.accountId))
        val ladder = Ladder(request, primary, urls)
        ladder.token = ++tokenSeq
        ladders[request.accountId] = ladder
        return Begun(ladder.attempt(), windowEnd)
    }

    /** The current attempt played: remember the form that worked on this playlist and end the walk. */
    fun onSuccess(token: Long): CatchUpDialectWalk.Step {
        val ladder = ladderFor(token)?.takeUnless { it.settled } ?: return CatchUpDialectWalk.Step.Stale
        memory.rememberM3uForm(ladder.request.accountId, M3uCatchUp.linkForm(ladder.urls[ladder.index], ladder.primary))
        ladder.settled = true
        return CatchUpDialectWalk.Step.Done
    }

    /** The current attempt failed. TRANSPORT advances the ladder; DECODE or exhaustion ends it with nothing remembered. */
    fun onFailure(token: Long, kind: CatchUpDialectWalk.FailureKind): CatchUpDialectWalk.Step {
        val ladder = ladderFor(token)?.takeUnless { it.settled } ?: return CatchUpDialectWalk.Step.Stale
        val next = ladder.index + 1
        if (kind != CatchUpDialectWalk.FailureKind.TRANSPORT || next >= ladder.urls.size) {
            ladders.remove(ladder.request.accountId)
            return CatchUpDialectWalk.Step.Unavailable
        }
        ladder.index = next
        ladder.token = ++tokenSeq
        return CatchUpDialectWalk.Step.Next(ladder.attempt())
    }

    private fun ladderFor(token: Long): Ladder? = ladders.values.firstOrNull { it.token == token }
}
