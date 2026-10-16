package com.nuvio.app.features.party

/** What the backend answered when asked whether watch parties are on (`watch_party_enabled`). */
internal sealed interface PartyEnabledProbe {
    data class Answered(val enabled: Boolean) : PartyEnabledProbe

    /** The backend has no watch-party RPCs (PostgREST `PGRST202`, HTTP 404): the feature is not deployed there. */
    data object MissingFunction : PartyEnabledProbe

    /** Offline, timeout, 5xx, an unreadable body: no verdict either way. */
    data object Failed : PartyEnabledProbe
}

/**
 * Whether the watch-party UI may show. Pure, so the "RPC is missing" path is pinned by a test instead of
 * trusted: before this policy every failure of `watch_party_enabled` kept the previous value (initially
 * `true`), so a backend without the RPCs left an entry point that could only ever fail.
 */
internal object PartyAvailabilityPolicy {
    fun nextEnabled(previous: Boolean, probe: PartyEnabledProbe): Boolean = when (probe) {
        is PartyEnabledProbe.Answered -> probe.enabled
        PartyEnabledProbe.MissingFunction -> false
        PartyEnabledProbe.Failed -> previous
    }

    /**
     * Classifies a PostgREST refusal. supabase-kt 3.6.0's `RestException.message` is
     * `<PostgREST message>\nCode: <SQLSTATE or PGRST code>\nHint: ..` (see ProviderRpcErrors). A missing
     * function is `PGRST202` on HTTP 404; a bare 404 with no server code is treated the same (the RPC path
     * only 404s when the function does not exist).
     */
    fun probeOfFailure(statusCode: Int?, message: String?): PartyEnabledProbe {
        val serverCode = message.orEmpty().lines()
            .firstOrNull { it.trim().startsWith("Code:") }
            ?.substringAfter("Code:")?.trim()
            ?.takeIf { it.isNotEmpty() && it != "null" }
        return when {
            serverCode == MISSING_FUNCTION_CODE -> PartyEnabledProbe.MissingFunction
            statusCode == 404 && serverCode == null -> PartyEnabledProbe.MissingFunction
            else -> PartyEnabledProbe.Failed
        }
    }

    private const val MISSING_FUNCTION_CODE = "PGRST202"
}
