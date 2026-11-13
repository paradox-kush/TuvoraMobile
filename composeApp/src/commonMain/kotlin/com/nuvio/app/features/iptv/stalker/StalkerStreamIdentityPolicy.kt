package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.player.LiveRemintSpacingPolicy
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Which header profile a live Stalker stream on the PORTAL'S OWN HOST opens with ([StalkerStreamHeaderPolicy]).
 *
 * [IDENTITY]: the MAG User-Agent pair, plus the portal cookie / token / Referer / Origin on the Media3
 * lane. [NEUTRAL] (the default, the pre-1.20.3 request shape): the credential-free player profile a foreign-host
 * stream already gets (the playlist UA or `libmpv`, and an Accept of any type; no X-User-Agent, nothing origin-bound).
 */
enum class StalkerStreamProfile { IDENTITY, NEUTRAL }

/**
 * B259 follow-up. Before 1.20.3 the phone, iOS, Apple TV and desktop apps never sent the MAG User-Agent, X-User-Agent
 * or portal secrets to a Stalker stream, and a real provider played fine; 1.20.3 started sending the MAG identity to
 * portal-host streams, and that provider 401s any request carrying the MAG User-Agent (any other UA, or none, gets
 * 200 + video). So NEUTRAL is the default again, and IDENTITY is the one-shot fallback for providers that gate their
 * streams on it:
 *
 *  - a LIVE portal-host stream (no playlist UA override: the viewer set that one on purpose) that fails AT OPEN,
 *    before its first frame, with an auth rejection or no named cause is re-minted at once (a create_link is
 *    single-use with a 5 s life) with the OTHER profile, once per tune, as a one-shot trial;
 *  - the trial's first frame is what the playlist learns (in memory, per process): an IDENTITY frame remembers
 *    IDENTITY, a NEUTRAL frame forgets it. A playlist that learned IDENTITY may so try NEUTRAL again;
 *  - a failed trial ends it: the tune goes back to its starting profile and the normal failure path (the reconnect
 *    ladder re-mints with the starting profile and its usual spacing);
 *  - VOD, series, catch-up and recordings send no Stalker header set at all, exactly as before 1.20.3.
 *
 * Engines and the trigger. Media3 names the status ("Source error (HTTP 401)"), so a named 401/403 switches and any
 * other named cause (404, 5xx, network, codec) does not. libmpv ends a failed open with "loading failed" and no
 * status on the live lanes (Android mpv reports only an ended snapshot), so a failed open that names NO cause is
 * treated as the trigger too ([StalkerOpenFailureGate] first waits briefly for the engine's text); it is still one
 * switch per tune.
 *
 * Pure: no I/O, no logging.
 */
object StalkerStreamIdentityPolicy {

    /** How a failed first open reads, for the switch only. */
    enum class OpenFailure {
        /** 401 / 403, or the words: the provider refused the request. */
        AUTH_REJECTED,

        /** Nothing names a cause (libmpv's "loading failed", an ended snapshot with no message). */
        UNNAMED,

        /** A different, named cause: another status, the network, the stream itself. */
        OTHER,
    }

    fun classify(message: String?): OpenFailure {
        val m = message?.lowercase()?.trim().orEmpty()
        if (m.isEmpty()) return OpenFailure.UNNAMED
        val status = STATUS.find(m)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            status == 401 || status == 403 -> OpenFailure.AUTH_REJECTED
            status != null -> OpenFailure.OTHER
            AUTH_TEXT.any { it in m } -> OpenFailure.AUTH_REJECTED
            OTHER_TEXT.any { it in m } -> OpenFailure.OTHER
            else -> OpenFailure.UNNAMED
        }
    }

    /**
     * Whether a failed open switches the tune to the other profile. [eligible] = the attempt opened a portal-host
     * stream with no UA override ([StalkerStreamHeaders.profileFallback]) and no failure of it was classified yet;
     * [switchedThisTune] = the one switch is spent; [started] = this tune already drew a frame.
     */
    fun shouldSwitch(eligible: Boolean, switchedThisTune: Boolean, started: Boolean, failure: OpenFailure): Boolean =
        eligible && !switchedThisTune && !started && failure != OpenFailure.OTHER

    /**
     * How long the automatic re-mint after a failure waits. The profile switch never waits: the provider refused the
     * request outright, so it holds no connection for [LiveRemintSpacingPolicy]'s window to wait out, and the
     * fresh link must still beat the portal's 5 s link life. Every other automatic re-mint keeps the spacing.
     */
    fun remintDelayMs(switchingProfile: Boolean, singleUseLink: Boolean, connectionEndedAtMs: Long?, nowMs: Long): Long =
        if (switchingProfile) 0L else LiveRemintSpacingPolicy.automaticDelayMs(singleUseLink, connectionEndedAtMs, nowMs)

    private val STATUS = Regex("""(?:response code|http(?: error| status| status code)?|status(?: code)?|returned)\s*[:=(]?\s*(\d{3})\b""")
    private val AUTH_TEXT = listOf("unauthorized", "forbidden")
    private val OTHER_TEXT = listOf(
        "unable to resolve", "unknownhost", "timeout", "timed out", "connection refused", "connection reset",
        "unreachable", "no route", "network", "unrecognized", "unsupported", "decoder", "decoding", "malformed",
    )
}

/**
 * The profile each playlist has PROVEN to play live with (an IDENTITY trial's first frame; a NEUTRAL trial's first
 * frame forgets it). NEUTRAL until proven otherwise. In memory, per process: a provider that changes its mind is asked
 * again on the next launch. Shared by every live surface ([Process]); tests make their own. Never read by VOD.
 */
class StalkerStreamProfileMemory {
    // Read by the resolve path off the main thread: one plain lock (Kotlin/Native: one lock per collection).
    private val lock = SynchronizedObject()
    private val identity = mutableSetOf<String>()

    fun profileFor(playlistId: String?): StalkerStreamProfile =
        if (playlistId != null && synchronized(lock) { playlistId in identity }) StalkerStreamProfile.IDENTITY
        else StalkerStreamProfile.NEUTRAL

    fun rememberIdentity(playlistId: String) {
        synchronized(lock) { identity += playlistId }
    }

    fun forgetIdentity(playlistId: String) {
        synchronized(lock) { identity -= playlistId }
    }

    companion object {
        val Process = StalkerStreamProfileMemory()
    }
}

/**
 * One live tune's fallback state (a zap makes a new one; the viewer's Retry calls [reset]): the starting profile
 * (what the playlist learned), the one-shot trial, whether a frame showed, and whether the source on screen may
 * still switch. Driven by the docked Live screen (phone, desktop; through [StalkerOpenFailureGate]) and the Apple TV
 * live session. Not thread-safe: one owner, its main thread.
 */
class StalkerStreamFallback(
    private val playlistId: String?,
    private val memory: StalkerStreamProfileMemory = StalkerStreamProfileMemory.Process,
) {
    private var start: StalkerStreamProfile = memory.profileFor(playlistId)
    private var trial: StalkerStreamProfile? = null
    private var switched = false
    private var started = false
    private var eligible = false
    private var opened = false
    private var failureReported = false

    /** The profile the next resolve of this tune opens with. */
    val profile: StalkerStreamProfile get() = trial ?: start

    /** A failed open now could still switch (the gate waits for the engine's text only then). */
    val canSwitch: Boolean get() = eligible && !switched && !started

    /**
     * This tune resolved a source and handed it to the engine; [profileFallbackEligible] =
     * [StalkerStreamHeaders.profileFallback]. Until this runs, a frame belongs to the previous channel's surface.
     */
    fun onOpened(profileFallbackEligible: Boolean) {
        opened = true
        eligible = profileFallbackEligible
        failureReported = false
    }

    /**
     * The first open failed. True: re-mint NOW (no spacing, [StalkerStreamIdentityPolicy.remintDelayMs]) with
     * [profile]. Any classified failure ends this source's eligibility; a failed trial ends the trial. Only the first
     * report per opened source counts: an engine that keeps reporting the same error until the next load (the Apple TV
     * poll loop) must not end the trial it just started.
     */
    fun onOpenFailure(message: String?): Boolean {
        if (failureReported) return false
        failureReported = true
        val wasEligible = eligible
        eligible = false
        if (switched) {
            // The one-shot trial failed before a frame: back to the starting profile for the rest of the tune.
            if (!started) trial = null
            return false
        }
        val switch = StalkerStreamIdentityPolicy.shouldSwitch(
            eligible = wasEligible,
            switchedThisTune = false,
            started = started,
            failure = StalkerStreamIdentityPolicy.classify(message),
        )
        if (switch) {
            switched = true
            trial = if (start == StalkerStreamProfile.NEUTRAL) StalkerStreamProfile.IDENTITY else StalkerStreamProfile.NEUTRAL
        }
        return switch
    }

    /** The tune drew a frame: a trial that got here is what the playlist learns. Ignored before [onOpened]. */
    fun onFirstFrame() {
        if (started || !opened) return
        started = true
        val id = playlistId ?: return
        when (trial) {
            StalkerStreamProfile.IDENTITY -> memory.rememberIdentity(id)
            StalkerStreamProfile.NEUTRAL -> memory.forgetIdentity(id)
            null -> Unit
        }
    }

    /** The viewer's Retry: a fresh tune (the learned profile, one switch again). */
    fun reset() {
        start = memory.profileFor(playlistId)
        trial = null
        switched = false
        started = false
        eligible = false
        opened = false
        failureReported = false
    }
}

/**
 * One attempt's failed open, as the docked Live screen sees it (a new gate per attempt). Engines report a failed
 * open differently: Media3 sends the error text ("Source error (HTTP 401)"); the iOS poll loop reports the ended
 * snapshot BEFORE the text in the same tick; Android libmpv sends no text for a live open at all. So an ended
 * snapshot that could still switch waits [GRACE_MS] for the text before it counts as "no named cause", and the
 * first decision wins: one call into [StalkerStreamFallback.onOpenFailure] per attempt.
 */
class StalkerOpenFailureGate(private val fallback: StalkerStreamFallback) {
    sealed interface Step {
        /** Nothing to do (already decided). */
        data object None : Step

        /** Take today's failure path (the error pill, the reason line). */
        data object Proceed : Step

        /** Re-mint now with [StalkerStreamFallback.profile]; no pill. */
        data object Switch : Step

        /** Hold the failure; [scheduleMs] non-null = start the grace timer (only the first time). */
        data class Wait(val scheduleMs: Long?) : Step
    }

    private var waiting = false
    private var decided = false

    fun onEndedAtOpen(): Step = when {
        decided -> Step.Proceed
        waiting -> Step.Wait(null)
        fallback.canSwitch -> { waiting = true; Step.Wait(GRACE_MS) }
        else -> { decided = true; Step.Proceed }
    }

    fun onErrorText(message: String?): Step {
        if (decided) return Step.Proceed
        return decide(message)
    }

    fun onGraceElapsed(): Step = if (!waiting || decided) Step.None else decide(null)

    private fun decide(message: String?): Step {
        decided = true
        waiting = false
        return if (fallback.onOpenFailure(message)) Step.Switch else Step.Proceed
    }

    companion object {
        /** More than one iOS poll tick (250 ms) and a desktop event hop; short next to the 20 s startup deadline. */
        const val GRACE_MS = 750L
    }
}
