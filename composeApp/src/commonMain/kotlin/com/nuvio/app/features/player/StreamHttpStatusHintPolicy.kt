package com.nuvio.app.features.player

import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_error_stream_blocked
import nuvio.composeapp.generated.resources.player_error_stream_connection_limit
import nuvio.composeapp.generated.resources.player_error_stream_expired
import nuvio.composeapp.generated.resources.player_error_stream_provider_firewall
import nuvio.composeapp.generated.resources.player_error_stream_rate_limited
import nuvio.composeapp.generated.resources.player_error_stream_removed
import nuvio.composeapp.generated.resources.player_error_stream_unavailable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * What a stream's HTTP status means for the viewer, as one of a closed set of hints (pure; the
 * platforms word it). Ported from NuvioTV's status explanations so every app reads 456 the same way.
 *
 * 456 is not a standard status: Cloudflare-fronted IPTV panels send it when a bot rule rejects the
 * stream request, almost always on the User-Agent ([com.nuvio.app.features.iptv.StreamUserAgentPolicy]).
 * 458 (some Xtream panels) and 509 are the account's concurrent-connection cap.
 */
object StreamHttpStatusHintPolicy {

    enum class Hint {
        /** 456: the provider's firewall refused the request; a custom User-Agent on the playlist fixes it. */
        PROVIDER_FIREWALL,
        /** 458 / 509: too many connections on the account. */
        CONNECTION_LIMIT,
        /** 401 / 410: the link expired. */
        EXPIRED,
        /** 404: the link is gone. */
        REMOVED,
        /** 429: too many requests. */
        RATE_LIMITED,
        /** 5xx: the stream server failed. */
        SERVER_UNAVAILABLE,
        /** 403 and any other 4xx: refused. */
        BLOCKED,
    }

    fun hintFor(status: Int): Hint? = when (status) {
        456 -> Hint.PROVIDER_FIREWALL
        458, 509 -> Hint.CONNECTION_LIMIT
        401, 410 -> Hint.EXPIRED
        404 -> Hint.REMOVED
        429 -> Hint.RATE_LIMITED
        in 500..599 -> Hint.SERVER_UNAVAILABLE
        in 400..499 -> Hint.BLOCKED
        else -> null
    }
}

/** The string resource that words [StreamHttpStatusHintPolicy.Hint]. */
internal fun StreamHttpStatusHintPolicy.Hint.resource(): StringResource = when (this) {
    StreamHttpStatusHintPolicy.Hint.PROVIDER_FIREWALL -> Res.string.player_error_stream_provider_firewall
    StreamHttpStatusHintPolicy.Hint.CONNECTION_LIMIT -> Res.string.player_error_stream_connection_limit
    StreamHttpStatusHintPolicy.Hint.EXPIRED -> Res.string.player_error_stream_expired
    StreamHttpStatusHintPolicy.Hint.REMOVED -> Res.string.player_error_stream_removed
    StreamHttpStatusHintPolicy.Hint.RATE_LIMITED -> Res.string.player_error_stream_rate_limited
    StreamHttpStatusHintPolicy.Hint.SERVER_UNAVAILABLE -> Res.string.player_error_stream_unavailable
    StreamHttpStatusHintPolicy.Hint.BLOCKED -> Res.string.player_error_stream_blocked
}

/** A plain-language reason for an HTTP status, shown with the technical error. */
internal fun httpStatusExplanation(code: Int): StringResource? = StreamHttpStatusHintPolicy.hintFor(code)?.resource()

internal suspend fun httpStatusExplanationText(code: Int): String =
    httpStatusExplanation(code)?.let { getString(it) }.orEmpty()

/**
 * [message] with the hint for the HTTP status it names appended (an engine that reports only raw text, such
 * as libmpv on iOS); unchanged when it names none or already carries the hint.
 */
internal suspend fun withHttpStatusHint(message: String): String {
    val status = com.nuvio.app.core.analytics.LiveReconnectReasonPolicy.httpStatusIn(message) ?: return message
    val hint = httpStatusExplanationText(status)
    return if (hint.isEmpty() || message.contains(hint)) message else "$message\n\n$hint"
}
