package com.nuvio.app.core.sources

/**
 * Why an account-backend call (QR sign-in start, device setup RPCs) failed, in terms the user can act on.
 * Pure: no Android, no SDK types - the cause chain is read by class name and message so it unit-tests
 * without the network. B220 ("No internet connection" on a box that was online) and B188 ("Could not
 * reach your account" for every failure) both came from collapsing these classes into one string.
 */
enum class AccountFailureKind {
    /** The device itself has no network (ConnectivityManager says so). */
    OFFLINE,
    /** The network is up but the Tuvora server could not be reached (DNS, refused, TLS, no route). */
    SERVER_UNREACHABLE,
    TIMEOUT,
    /** Session lapsed or was rejected (401/403, JWT expired): the user must sign in again. */
    SESSION_EXPIRED,
    /** The active profile has no row server-side yet (profile_not_found). */
    PROFILE_MISSING,
    /** The signed-in session is not the account owner (owner_required). */
    NOT_OWNER,
    RATE_LIMITED,
    /** The backend is missing a function/migration this build needs (404 / PGRST202). */
    SERVER_OUTDATED,
    SERVER_ERROR,
    /** This build has no backend URL/key baked in. */
    MISSING_CONFIG,
    /** Backend refuses anonymous sign-in, which QR sign-in needs for its first step. */
    ANONYMOUS_DISABLED,
    UNKNOWN,
}

object AccountFailurePolicy {
    /**
     * @param online `true`/`false` from the connectivity service, `null` when unknown.
     * @param configured whether the build carries a backend URL and key.
     */
    fun classify(error: Throwable, online: Boolean? = null, configured: Boolean = true): AccountFailureKind {
        if (!configured) return AccountFailureKind.MISSING_CONFIG
        val chain = generateSequence(error) { it.cause?.takeIf { c -> c !== it } }.take(8).toList()
        val names = chain.map { it::class.simpleName.orEmpty() }
        val text = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
        fun hasType(vararg n: String) = names.any { name -> n.any { name.endsWith(it) } }

        return when {
            "profile_not_found" in text -> AccountFailureKind.PROFILE_MISSING
            "owner_required" in text -> AccountFailureKind.NOT_OWNER
            "anonymous sign-ins are disabled" in text || "anonymous_provider_disabled" in text ->
                AccountFailureKind.ANONYMOUS_DISABLED
            "rate limit" in text || "too many requests" in text || "over_request_rate_limit" in text ||
                Regex("""\b429\b""").containsMatchIn(text) -> AccountFailureKind.RATE_LIMITED
            "jwt expired" in text || "invalid jwt" in text || "pgrst301" in text || "42501" in text ||
                "not authenticated" in text || "invalid refresh token" in text ||
                "refresh_token_not_found" in text || "session_not_found" in text ||
                Regex("""\b(401|403)\b""").containsMatchIn(text) || "unauthorized" in text ->
                AccountFailureKind.SESSION_EXPIRED
            "pgrst202" in text || "could not find the function" in text ||
                Regex("""\b404\b""").containsMatchIn(text) -> AccountFailureKind.SERVER_OUTDATED
            hasType("HttpRequestTimeoutException", "SocketTimeoutException", "ConnectTimeoutException",
                "TimeoutCancellationException") || "timed out" in text || "timeout" in text -> AccountFailureKind.TIMEOUT
            Regex("""\b5\d\d\b""").containsMatchIn(text) && "http" in text -> AccountFailureKind.SERVER_ERROR
            hasType("UnknownHostException", "ConnectException", "NoRouteToHostException", "SSLException",
                "SocketException", "UnresolvedAddressException", "IOException") ||
                "unable to resolve host" in text || "connection refused" in text || "connect failed" in text ->
                if (online == false) AccountFailureKind.OFFLINE else AccountFailureKind.SERVER_UNREACHABLE
            "400" in text -> AccountFailureKind.SERVER_ERROR
            else -> AccountFailureKind.UNKNOWN
        }
    }
}
