package com.nuvio.app.features.livetv

/**
 * UX191: why a live channel did not play, in a word the viewer can act on.
 *
 * The docked error pill used to say "Can't play this channel" for everything, so a provider that
 * expired the line, a second device holding the only connection and a real app bug all reached
 * Discord as the same screenshot. This maps what the screen already knows (its failure code and the
 * engine's error text) to a [Reason]; the screen words each reason from string resources.
 *
 * Pure on purpose. It reads the engine message only to classify it and never returns, logs or
 * reports any of it: error text can carry a host or a path, so the viewer sees one of our own fixed
 * sentences and telemetry sees only [Reason.code].
 *
 * Android's engine appends the HTTP status ("Source error (HTTP 403)"); iOS/libmpv reports only
 * mpv's own end-of-file text, which has no status, so those failures stay [Reason.UNKNOWN] unless
 * the text names the cause.
 */
internal object LiveTvFailureReasonPolicy {

    enum class Reason(val code: String) {
        /** 401/403/404/410/451...: the provider turned this line or device away (expired, blocked, channel gone). */
        PROVIDER_REFUSED("provider_refused"),

        /** The provider's connection cap: another device is already streaming on the line. */
        TOO_MANY_CONNECTIONS("too_many_connections"),

        /** The provider's own server answered 5xx. */
        PROVIDER_DOWN("provider_down"),

        /** The app could not get a stream link from the provider at all. */
        LINK_UNAVAILABLE("link_unavailable"),

        /** This device could not reach the provider: DNS, timeout, reset, offline. */
        NETWORK("network"),

        /**
         * UX343: the device itself has no connectivity (the shared [com.nuvio.app.core.network.NetworkStatusRepository]
         * reads NoInternet), so no provider advice applies. The pill says "No network connection" and nothing more.
         */
        OFFLINE("offline"),

        /** The stream arrived but this device cannot read it: unknown container or codec. */
        UNSUPPORTED("unsupported"),

        /**
         * The channel's first open failed and nothing names the cause. libmpv (the phone default on Android and
         * the iOS engine) ends a failed open with END_FILE "loading failed" and no status, so this is the common
         * case there; the pill then says only what is known: the stream did not start.
         */
        NOT_STARTED("not_started"),

        /** Nothing in the failure says why: keep the generic line. */
        UNKNOWN("unknown"),
    }

    /**
     * [offline] is the device's own connectivity (fed by the platform-neutral network status, never an OS API
     * emulated here). It explains every failure that does not prove the provider answered; an HTTP status, a
     * refusal text or an unreadable stream means the device did reach the provider, so a stale offline flag
     * never hides those.
     */
    fun classify(failureCode: String?, message: String?, offline: Boolean = false): Reason {
        val named = if (failureCode == "resolve_failed") Reason.LINK_UNAVAILABLE else classifyMessage(message)
        if (offline && named !in PROVED_REACHABLE) return Reason.OFFLINE
        if (failureCode == "resolve_failed") return named
        // Only a first open that never started may say so; a channel that played and then dropped did start.
        return if (named == Reason.UNKNOWN && failureCode == "startup_failed") Reason.NOT_STARTED else named
    }

    private val PROVED_REACHABLE = setOf(
        Reason.PROVIDER_REFUSED,
        Reason.TOO_MANY_CONNECTIONS,
        Reason.PROVIDER_DOWN,
        Reason.UNSUPPORTED,
    )

    private fun classifyMessage(message: String?): Reason {
        val m = message?.lowercase().orEmpty()
        if (m.isEmpty()) return Reason.UNKNOWN

        val status = STATUS.find(m)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            status in CONNECTION_LIMIT_STATUSES || CONNECTION_LIMIT_TEXT.any { it in m } -> Reason.TOO_MANY_CONNECTIONS
            status == 408 -> Reason.NETWORK
            status != null && status in 400..499 -> Reason.PROVIDER_REFUSED
            status != null && status in 500..599 -> Reason.PROVIDER_DOWN
            UNSUPPORTED_TEXT.any { it in m } -> Reason.UNSUPPORTED
            NETWORK_TEXT.any { it in m } -> Reason.NETWORK
            else -> Reason.UNKNOWN
        }
    }

    /** The failure code with the reason appended (`playback_error_provider_refused`); unchanged when the reason adds nothing. */
    fun telemetryCode(failureCode: String?, reason: Reason): String? =
        // NOT_STARTED only restates startup_failed, so it adds nothing to the code.
        if (failureCode == null || reason == Reason.UNKNOWN || reason == Reason.NOT_STARTED) failureCode
        else "${failureCode}_${reason.code}"

    /** The chip on the docked player's top row. */
    enum class Badge { LIVE, CONNECTING, NONE }

    /**
     * UX344: LIVE means the channel is on air for this viewer. It must not sit on a failed channel, nor on one that
     * has not started: while loading, or while a retry has not resolved its source yet (the old snapshot is idle
     * then, which used to flash LIVE before the next failure). A viewer pause or a time-shifted channel is
     * still live.
     */
    fun badge(failed: Boolean, sourceReady: Boolean, loading: Boolean, playing: Boolean, timeShifted: Boolean): Badge = when {
        failed -> Badge.NONE
        timeShifted -> Badge.LIVE
        !sourceReady || (loading && !playing) -> Badge.CONNECTING
        else -> Badge.LIVE
    }

    // Anchored on the word that introduces a status so a stray number (a port, a channel id) never counts.
    private val STATUS = Regex("""(?:response code|http(?: error| status| status code)?|status(?: code)?|returned)\s*[:=]?\s*(\d{3})\b""")

    private val CONNECTION_LIMIT_STATUSES = setOf(429, 458, 509)

    private val CONNECTION_LIMIT_TEXT = listOf(
        "too many connections",
        "too many users",
        "max connections",
        "maximum connections",
        "connection limit",
        "already in use",
    )

    private val UNSUPPORTED_TEXT = listOf(
        "unrecognized",
        "unsupported",
        "decoder",
        "decoding",
        "no decoder",
        "malformed",
    )

    private val NETWORK_TEXT = listOf(
        "unable to resolve",
        "unknownhost",
        "timeout",
        "timed out",
        "connection refused",
        "connection reset",
        "connection failed",
        "network",
        "unreachable",
        "no route",
        "offline",
    )
}
