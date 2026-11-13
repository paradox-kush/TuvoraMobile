package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.player.LiveRemintSpacingPolicy
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Which header profile a Stalker stream on the PORTAL'S OWN HOST opens with ([StalkerStreamHeaderPolicy]).
 *
 * [IDENTITY] (the default): the MAG User-Agent pair, plus the portal cookie / token / Referer / Origin on the Media3
 * lane. [NEUTRAL]: the credential-free player profile a foreign-host stream already gets (the playlist UA or
 * `libmpv`, and an Accept of any type; no X-User-Agent, nothing origin-bound).
 */
enum class StalkerStreamProfile { IDENTITY, NEUTRAL }

/**
 * B259 follow-up: some providers 401 a portal-host stream at its first hop whenever the request carries the MAG
 * set-top-box User-Agent (proven on a real provider: any other UA, or none, gets 200 + video; cookie, token, Referer
 * and X-User-Agent make no difference). Other providers gate those same streams ON the MAG identity, so it stays the
 * default and the screen falls back once instead:
 *
 *  - a portal-host stream opened with the IDENTITY profile (and no playlist UA override: the viewer set that one on
 *    purpose) that fails AT OPEN, before its first frame, with an auth rejection is re-minted at once (a create_link
 *    is single-use with a 5 s life) and opened with the NEUTRAL profile;
 *  - when the NEUTRAL attempt draws a frame, NEUTRAL is remembered for that playlist (in memory, per process);
 *  - a NEUTRAL failure takes the normal failure path: each profile is tried at most once per tune.
 *
 * Engines and the trigger. Media3 names the status ("Source error (HTTP 401)"), so a named 401/403 switches and any
 * other named cause (404, 5xx, network, codec) does not. libmpv ends a failed open with "loading failed" and no
 * status on the live lanes (Android mpv reports only an ended snapshot), so a failed open that names NO cause is
 * treated as the trigger too; it is still one switch per tune.
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
     * Whether a failed open switches the tune to NEUTRAL. [eligible] = the attempt opened a portal-host stream with
     * the IDENTITY profile and no UA override ([StalkerStreamHeaders.identityFallback]); [switchedThisTune] = the one
     * switch is spent; [started] = this tune already drew a frame.
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
 * The profile each playlist has PROVEN to play with (a NEUTRAL first frame). In memory, per process: a provider
 * that changes its mind is asked again on the next launch. Shared by every surface ([Process]); tests make their own.
 */
class StalkerStreamProfileMemory {
    // Read by the resolve path off the main thread: one plain lock (Kotlin/Native: one lock per collection).
    private val lock = SynchronizedObject()
    private val neutral = mutableSetOf<String>()

    fun profileFor(playlistId: String?): StalkerStreamProfile =
        if (playlistId != null && synchronized(lock) { playlistId in neutral }) StalkerStreamProfile.NEUTRAL
        else StalkerStreamProfile.IDENTITY

    fun rememberNeutral(playlistId: String) {
        synchronized(lock) { neutral += playlistId }
    }

    companion object {
        val Process = StalkerStreamProfileMemory()
    }
}

/**
 * One tune's fallback state (a zap makes a new one; the viewer's Retry calls [reset]): the trial profile, whether the
 * one switch is spent, whether a frame showed, and whether the source on screen may fall back. Driven by the
 * docked Live screen (phone, desktop) and the Apple TV live session. Not thread-safe: one owner, its main thread.
 */
class StalkerStreamFallback(
    private val playlistId: String?,
    private val memory: StalkerStreamProfileMemory = StalkerStreamProfileMemory.Process,
) {
    private var trial: StalkerStreamProfile? = null
    private var switched = false
    private var started = false
    private var eligible = false

    /** The profile the next resolve of this tune opens with. */
    val profile: StalkerStreamProfile get() = trial ?: memory.profileFor(playlistId)

    /** A source was resolved and handed to the engine; [identityFallbackEligible] = [StalkerStreamHeaders.identityFallback]. */
    fun onOpened(identityFallbackEligible: Boolean) {
        eligible = identityFallbackEligible
    }

    /** The first open failed. True: re-mint NOW (no spacing, [StalkerStreamIdentityPolicy.remintDelayMs]) with [profile]. */
    fun onOpenFailure(message: String?): Boolean {
        val switch = StalkerStreamIdentityPolicy.shouldSwitch(
            eligible = eligible,
            switchedThisTune = switched,
            started = started,
            failure = StalkerStreamIdentityPolicy.classify(message),
        )
        if (switch) {
            switched = true
            eligible = false
            trial = StalkerStreamProfile.NEUTRAL
        }
        return switch
    }

    /** The tune drew a frame: a NEUTRAL that got here is remembered for the playlist. */
    fun onFirstFrame() {
        if (started) return
        started = true
        if (trial == StalkerStreamProfile.NEUTRAL && playlistId != null) memory.rememberNeutral(playlistId)
    }

    /** The viewer's Retry: a fresh tune (the learned profile, one switch again). */
    fun reset() {
        trial = null
        switched = false
        started = false
        eligible = false
    }
}
