package com.nuvio.app.features.iptv

import com.nuvio.app.features.addons.HttpStatusException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException

/**
 * Why saving a playlist failed, in words the user can act on (UX11 / UX20 / UX21 / B23).
 *
 * [fallbackText] is the approved English sentence — the string resource of the same meaning is
 * preferred at runtime (see `PlaylistSaveErrorText.kt`); the fallback keeps tests and resource-less
 * hosts honest and pins the wording.
 */
internal enum class PlaylistSaveError(val fallbackText: String) {
    /** Truly empty required Xtream fields — the only case the old one-size message was right for. */
    MISSING_XTREAM_FIELDS("Enter a server URL, username and password"),
    MISSING_M3U_URL("Enter an M3U playlist URL"),
    MISSING_STALKER_FIELDS("Enter a portal URL and MAC address"),
    MISSING_M3U_FILE("Choose an M3U file to import"),

    /** Malformed URL / bad port: the fields are filled, the address just isn't one. */
    INVALID_ADDRESS("That server address isn't valid"),

    /** DNS, timeout, refused, an open breaker, or anything we can't explain better. */
    UNREACHABLE("Couldn't reach the server — check the address"),

    /** HTTP 401 or the Xtream panel's `auth != 1`. */
    WRONG_CREDENTIALS("Wrong username or password"),

    /**
     * The provider's firewall answered (403/419/429/451/456 — see [IptvLoadFailurePolicy]): the server
     * is up and turned this device away. Not a password problem; reuses the hub's shipped title.
     */
    PROVIDER_BLOCKED("This provider is blocking us"),

    /** TLS handshake / certificate failure — the B23 class. */
    SECURE_CONNECTION_FAILED("Secure connection failed — try http:// or check the certificate"),

    /**
     * The device-source guard refused: the playlist is turned off on this device (B224). Not a network
     * problem, so it must never read as "couldn't reach the server".
     */
    SOURCE_DISABLED_ON_DEVICE("This playlist is turned off on this device — turn it on under Sources on this device"),

    /** The account turned this playlist off (it is off for every device), not this device. */
    SOURCE_DISABLED_FOR_ACCOUNT("This playlist is turned off on your account — turn it on in your playlist settings, then try again"),

    /** A local M3U file that could not be read or parsed. */
    FILE_UNREADABLE("Could not read that playlist file"),
}

/** What the form shows: one of our mapped sentences, or an explanation we authored ourselves. */
internal sealed interface PlaylistSaveMessage {
    data class Known(val error: PlaylistSaveError) : PlaylistSaveMessage

    /**
     * An already-worded, remedy-bearing sentence WE wrote (a Stalker portal refusal, an expired
     * account, an empty M3U). Safe to render verbatim — unlike a platform exception's message.
     */
    data class Authored(val text: String) : PlaylistSaveMessage
}

/** The neutral transport vocabulary every platform's network stack is mapped onto. */
internal enum class PlaylistTransportFailure { UNREACHABLE, SECURE_CONNECTION, INVALID_ADDRESS }

/**
 * Maps one throwable from a platform network stack (OkHttp's java.net/javax.net.ssl types on
 * Android/desktop, Ktor Darwin's NSError wrapper on iOS/tvOS) onto [PlaylistTransportFailure], or
 * null when it is not a transport failure. Only the throwable itself — the cause chain is walked by
 * [PlaylistSaveErrorPolicy].
 */
internal expect fun classifyPlatformTransportFailure(t: Throwable): PlaylistTransportFailure?

/** [classifyPlatformTransportFailure] plus Ktor's own (platform-neutral) timeout types. */
internal fun classifyPlaylistTransportFailure(t: Throwable): PlaylistTransportFailure? = when (t) {
    is HttpRequestTimeoutException,
    is ConnectTimeoutException,
    is SocketTimeoutException -> PlaylistTransportFailure.UNREACHABLE
    else -> classifyPlatformTransportFailure(t)
}

/**
 * The pure decision behind every playlist-save message: form validation before the network, and
 * failure classification after it. Type-driven, never parses a (possibly localized) message, and
 * holds no string resources — so it tests without Compose, a portal, or a network stack.
 *
 * Reuses [IptvLoadFailurePolicy] for the portal-refusal family so the hub card and the form agree
 * on which of our own explanations are safe to show verbatim.
 */
internal object PlaylistSaveErrorPolicy {

    /**
     * Null when the form can go to the live check; otherwise why not. Distinguishes "you left a
     * field empty" from "what you typed is not an address" (UX21: a bad port used to be reported as
     * missing fields even with all three filled in).
     */
    fun validate(input: XtreamFormInput): PlaylistSaveError? = when (input.sourceType) {
        SOURCE_TYPE_M3U_FILE -> null   // the picked file is checked by the file path itself
        SOURCE_TYPE_M3U_URL -> when {
            input.m3uUrl.isBlank() -> PlaylistSaveError.MISSING_M3U_URL
            !isValidServerAddress(input.m3uUrl) -> PlaylistSaveError.INVALID_ADDRESS
            else -> null
        }
        SOURCE_TYPE_STALKER -> when {
            input.serverUrl.isBlank() || input.macAddress.isBlank() -> PlaylistSaveError.MISSING_STALKER_FIELDS
            !isValidServerAddress(input.serverUrl) -> PlaylistSaveError.INVALID_ADDRESS
            else -> null
        }
        else -> when {
            input.serverUrl.isBlank() || input.username.isBlank() || input.password.isBlank() ->
                PlaylistSaveError.MISSING_XTREAM_FIELDS
            !isValidServerAddress(input.serverUrl) -> PlaylistSaveError.INVALID_ADDRESS
            else -> null
        }
    }

    /**
     * True when [raw] (scheme optional — http is assumed, as the builders do) is an http(s) address
     * with a host and, if a port is given, a port in 1..65535. Reuses [PlaylistKey.origin] — the same
     * parse the playlist id is built from — rather than Ktor's `Url`, which accepts out-of-range
     * ports and lets the transport fail later with an unhelpful error.
     */
    fun isValidServerAddress(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return false
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd >= 0 && trimmed.substring(0, schemeEnd).lowercase() !in HTTP_SCHEMES) return false
        return PlaylistKey.origin(trimmed) != null
    }

    /**
     * Why the live check for a playlist of [sourceType] failed. [transport] is the platform mapping
     * (injected only by tests). Never returns a platform exception's message.
     */
    fun classify(
        error: Throwable,
        sourceType: String,
        transport: (Throwable) -> PlaylistTransportFailure? = ::classifyPlaylistTransportFailure,
    ): PlaylistSaveMessage {
        if (error is com.nuvio.app.core.sources.SourceDisabledOnDeviceException)
            return PlaylistSaveMessage.Known(
                if (error.scope == com.nuvio.app.core.sources.RefusalScope.ACCOUNT) PlaylistSaveError.SOURCE_DISABLED_FOR_ACCOUNT
                else PlaylistSaveError.SOURCE_DISABLED_ON_DEVICE)
        transportFailureIn(error, transport)?.let { return PlaylistSaveMessage.Known(it.toSaveError()) }
        return when {
            error is XtreamAuthRejectedException -> PlaylistSaveMessage.Known(PlaylistSaveError.WRONG_CREDENTIALS)
            // Step 0.3: the failover probe's definitive refusal (auth=0 / banned) is the same answer on every server.
            error is FailoverAuthRejectedException -> PlaylistSaveMessage.Known(PlaylistSaveError.WRONG_CREDENTIALS)
            error is XtreamAccountInactiveException -> PlaylistSaveMessage.Authored(error.message.orEmpty())
            error is M3UNoContentException -> PlaylistSaveMessage.Authored(M3U_NO_CONTENT_MESSAGE)
            // The provider's edge (WAF/Cloudflare) turned us away: the server is up, the password is not the problem.
            error is HttpStatusException && IptvLoadFailurePolicy.isBlockingStatus(error.status) ->
                PlaylistSaveMessage.Known(PlaylistSaveError.PROVIDER_BLOCKED)
            // Stalker signs in by MAC: a 401 there is a portal refusal, not a password typo.
            error is HttpStatusException && error.status in CREDENTIAL_STATUSES && sourceType != SOURCE_TYPE_STALKER ->
                PlaylistSaveMessage.Known(PlaylistSaveError.WRONG_CREDENTIALS)
            error is HttpStatusException -> PlaylistSaveMessage.Known(PlaylistSaveError.UNREACHABLE)
            else -> {
                val load = IptvLoadFailurePolicy.classify(error)
                val portalText = load.portalText
                when {
                    load.kind == IptvLoadFailurePolicy.Kind.REFUSED && portalText != null -> PlaylistSaveMessage.Authored(portalText)
                    sourceType == SOURCE_TYPE_M3U_FILE -> PlaylistSaveMessage.Known(PlaylistSaveError.FILE_UNREADABLE)
                    else -> PlaylistSaveMessage.Known(PlaylistSaveError.UNREACHABLE)
                }
            }
        }
    }

    /** The first transport failure in [error]'s cause chain (bounded — chains can be cyclic). */
    private fun transportFailureIn(
        error: Throwable,
        transport: (Throwable) -> PlaylistTransportFailure?,
    ): PlaylistTransportFailure? {
        var current: Throwable? = error
        repeat(MAX_CAUSE_DEPTH) {
            val t = current ?: return null
            transport(t)?.let { return it }
            current = t.cause?.takeIf { it !== t }
        }
        return null
    }

    private fun PlaylistTransportFailure.toSaveError(): PlaylistSaveError = when (this) {
        PlaylistTransportFailure.UNREACHABLE -> PlaylistSaveError.UNREACHABLE
        PlaylistTransportFailure.SECURE_CONNECTION -> PlaylistSaveError.SECURE_CONNECTION_FAILED
        PlaylistTransportFailure.INVALID_ADDRESS -> PlaylistSaveError.INVALID_ADDRESS
    }

    private val CREDENTIAL_STATUSES = setOf(401)
    private val HTTP_SCHEMES = setOf("http", "https")
    private const val MAX_CAUSE_DEPTH = 8
}
