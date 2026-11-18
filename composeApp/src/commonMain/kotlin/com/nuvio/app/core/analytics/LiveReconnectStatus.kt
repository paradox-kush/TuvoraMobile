package com.nuvio.app.core.analytics

/**
 * Why a live channel is reconnecting, from a closed set the player can word on every platform.
 *
 * Only these codes ever leave the policy: the engine text it reads can carry a host or a play token,
 * so the screen shows one of our own sentences and nothing else.
 */
enum class LiveReconnectReason(val code: String) {
    /** HTTP 456: the provider's firewall (often Cloudflare) refused the player's User-Agent. */
    PROVIDER_FIREWALL("provider_firewall"),

    /** HTTP 458 / 509 / 429: the account's connection cap, or the provider asking us to slow down. */
    CONNECTION_LIMIT("connection_limit"),

    /** HTTP 404: the provider no longer serves this stream. */
    STREAM_NOT_FOUND("stream_not_found"),

    /** HTTP 5xx: the provider's own server failed. */
    PROVIDER_SERVER_ERROR("provider_server_error"),

    /** No answer at all: DNS, timeout, refused or reset connection. */
    UNREACHABLE("unreachable"),

    /** The stream ended or froze with nothing naming why — the common case. */
    STREAM_STOPPED("stream_stopped"),
}

/** "Reconnecting… (attempt [attempt] of [maxAttempts])" plus [reason]; shown while the live ladder runs. */
data class LiveReconnectStatus(
    val attempt: Int,
    val maxAttempts: Int,
    val reason: LiveReconnectReason,
)

/**
 * Pure: the HTTP status in an engine's failure text, and the reconnect reason it stands for.
 *
 * Every engine words a refused request differently: Media3's text carries our own ` (HTTP 456)` hint,
 * FFmpeg (libmpv on Android, iOS, Apple TV, desktop) logs `HTTP error 456` or `Server returned 404 Not Found`.
 * The match is anchored on the word that introduces a status, so a port or a channel id never counts.
 */
object LiveReconnectReasonPolicy {

    private val STATUS = Regex(
        """(?:response code|http(?: error| status| status code)?|status(?: code)?|server returned|returned)\s*[:=]?\s*(\d{3})\b""",
        RegexOption.IGNORE_CASE,
    )

    private val NETWORK_TEXT = listOf(
        "network error",
        "unable to resolve",
        "unknownhost",
        "failed to resolve",
        "timed out",
        "timeout",
        "connection refused",
        "connection reset",
        "connection failed",
        "unreachable",
        "no route",
    )

    /** The HTTP status named in [message], or null when it names none. */
    fun httpStatusIn(message: String?): Int? =
        message?.let { STATUS.find(it) }?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 100..599 }

    /** The reason for a known HTTP status; null for a status that says nothing a viewer can act on. */
    fun fromHttpStatus(status: Int?): LiveReconnectReason? = when (status) {
        null -> null
        456 -> LiveReconnectReason.PROVIDER_FIREWALL
        429, 458, 509 -> LiveReconnectReason.CONNECTION_LIMIT
        404 -> LiveReconnectReason.STREAM_NOT_FOUND
        in 500..599 -> LiveReconnectReason.PROVIDER_SERVER_ERROR
        else -> null
    }

    /** The reason [message] names: its HTTP status first, then a connection that never got an answer. */
    fun fromFailureText(message: String?): LiveReconnectReason {
        if (message.isNullOrBlank()) return LiveReconnectReason.STREAM_STOPPED
        fromHttpStatus(httpStatusIn(message))?.let { return it }
        // A status was named but it is not one we word (403, 410…): the stream stopped, the provider said no.
        if (httpStatusIn(message) != null) return LiveReconnectReason.STREAM_STOPPED
        val lower = message.lowercase()
        return if (NETWORK_TEXT.any { it in lower }) LiveReconnectReason.UNREACHABLE else LiveReconnectReason.STREAM_STOPPED
    }
}

/**
 * Whether a live channel whose FIRST open failed is worth asking again (pure). A provider that answered 5xx or could
 * not be reached is often back within seconds, so it gets the same bounded ladder as a mid-stream drop; a firewall
 * (456), a connection cap (458/509/429), a missing stream (404) or any other refusal will answer the same way again,
 * and racing a connection cap only makes it worse — those fail at once with the reason and Retry.
 */
object LiveOpenRetryPolicy {

    fun retries(reason: LiveReconnectReason): Boolean =
        reason == LiveReconnectReason.PROVIDER_SERVER_ERROR || reason == LiveReconnectReason.UNREACHABLE

    /**
     * The wait before open retry N (0-based): the freeze ladder's backoff one step on, since the failed open already
     * made its immediate request — 2 s, 5 s, 10 s, then 20 s.
     */
    fun delayMs(attempt: Int): Long = LivePlaybackRecoveryPolicy.backoffMs(attempt.coerceAtLeast(0) + 1)
}
