package com.nuvio.app.features.iptv.stalker

import com.nuvio.app.features.player.PlaybackCredentialScope

/**
 * The portal identity a Stalker STREAM request may carry: the same mac cookie, MAG User-Agent pair and session
 * token the portal's API calls send ([StalkerSession]). A snapshot, taken when a link is minted.
 *
 * [portalBaseUrl] is the normalised portal base (`scheme://host[:port]`, see [StalkerProtocol.normalizePortalBase]).
 */
data class StalkerStreamIdentity(
    val portalBaseUrl: String,
    val cookie: String,
    val magUserAgent: String,
    val xUserAgent: String,
    val token: String?,
) {
    // Never print the cookie or token (a data class toString would, and a log line is one call away).
    override fun toString(): String = "StalkerStreamIdentity(portal=<redacted>)"
}

/**
 * The two halves of a Stalker stream's request headers ([StalkerStreamHeaderPolicy]).
 *
 * [public] is safe on every engine and every redirect hop: no credential. [originBound] carries the portal identity
 * (cookie, token, portal Referer/Origin) and may only go to an engine that re-checks each redirect hop and drops it
 * on one that is not credential-safe (Android Media3 over OkHttp). Empty for a foreign-host stream.
 */
data class StalkerStreamHeaders(
    val public: Map<String, String>,
    val originBound: Map<String, String>,
    /**
     * A portal-host stream opened with the MAG identity ([StalkerStreamProfile.IDENTITY]) and no playlist UA
     * override: if it is rejected before its first frame, the tune may fall back to [StalkerStreamProfile.NEUTRAL]
     * once ([StalkerStreamFallback]). False for a foreign host, a NEUTRAL open, or an override.
     */
    val identityFallback: Boolean = false,
) {
    // Never print the secrets.
    override fun toString(): String = "StalkerStreamHeaders(public=${public.keys}, originBound=${originBound.keys})"
}

/**
 * B259: which request headers a Stalker live / VOD / episode STREAM URL is opened with.
 *
 * The rule is iptvnator's (`isStalkerStreamCredentialSafe` + `buildStalkerExternalPlaybackHeaders`):
 *  - a stream on the portal's own HOST (any port; an http portal may hand out https) is portal-owned: panels gate
 *    those streams on the same mac cookie / token the API used, so it carries the portal identity;
 *  - a different host, an https portal handing out an http stream (a TLS-obtained session replayed in cleartext),
 *    a non-http(s) scheme or anything unparseable is FOREIGN and gets a credential-free player profile. The play
 *    token of such a stream rides in the URL create_link minted; the mac cookie and Bearer token never leave.
 *
 * The playlist's own User-Agent (the form field, [com.nuvio.app.features.iptv.StreamUserAgentPolicy]) wins in both
 * cases: the viewer set it because the provider's WAF keys on it.
 *
 * Not emitted on purpose: `Range`, `Icy-MetaData` and `Connection`. Both engines own them. FFmpeg (every mpv lane)
 * sends `Range: bytes=<off>-` itself on a seekable-or-unknown stream, `Icy-MetaData: 1` (its `icy` option defaults
 * on and it strips the metadata) and its own `Connection`; a user `Range` would replace its offset on a seek.
 * Media3 adds `Range` per DataSpec (a duplicate would conflict on a seek) and `Icy-MetaData` only on the progressive
 * path that strips it; forcing it onto the HLS lane would ask an ICY server to interleave metadata into segments.
 * The foreign profile's `Accept` (any type) is FFmpeg's own default, so it changes nothing there and makes Media3 match.
 *
 * Redirects, and the split. The identity is only ever built for a credential-safe first hop, and its SECRET part
 * (`Cookie` with the mac and serial, `Authorization: Bearer`, and the portal `Referer`/`Origin`) is returned apart from
 * the rest as [StalkerStreamHeaders.originBound]. FFmpeg re-sends custom headers on every redirect hop to ANY host with
 * no per-host scoping (verified in libavformat/http.c), so the libmpv/FFmpeg lanes (Android mpv, iOS, Apple TV,
 * desktop) get only [StalkerStreamHeaders.public]: the User-Agent pair, never a credential. Only the Android Media3 lane,
 * whose OkHttp interceptor re-checks every hop ([mayCarryCredentialsTo]) and strips origin-bound headers on one that
 * leaves the host or downgrades, receives [StalkerStreamHeaders.originBound]. NuvioTV does the same.
 *
 * Pure: no I/O, no logging. Unit-tested on both runners.
 */
object StalkerStreamHeaderPolicy {

    /**
     * The foreign-host User-Agent when the playlist sets none. `libmpv` is what mpv itself sends when no UA is
     * configured, so on the mpv lanes (the live default on Android, iOS, Apple TV and desktop) it is literally
     * true; it names a media player rather than impersonating a browser (skill 07 7.6) or another app, and it
     * carries no app or device identity. iptvnator's `KSPlayer` would be a claim to be a different app.
     */
    const val PLAYER_USER_AGENT = "libmpv"

    /** Headers that carry the portal identity: never on a hop that is not credential-safe. Lower-case. */
    val CREDENTIAL_HEADERS: Set<String> get() = PlaybackCredentialScope.CREDENTIAL_HEADERS

    /**
     * The request headers for [streamUrl] (before any DNS host rewrite). [userAgentOverride] = the playlist's UA.
     * [profile] NEUTRAL opens even a portal-host stream with the credential-free player profile
     * ([StalkerStreamIdentityPolicy]: a provider that 401s the MAG User-Agent).
     */
    fun headersFor(
        identity: StalkerStreamIdentity,
        streamUrl: String,
        userAgentOverride: String?,
        profile: StalkerStreamProfile = StalkerStreamProfile.IDENTITY,
    ): StalkerStreamHeaders {
        val override = userAgentOverride?.trim()?.takeIf { it.isNotEmpty() }
        if (profile == StalkerStreamProfile.NEUTRAL || !isCredentialSafe(identity.portalBaseUrl, streamUrl)) {
            return StalkerStreamHeaders(
                public = linkedMapOf("User-Agent" to (override ?: PLAYER_USER_AGENT), "Accept" to "*/*"),
                originBound = emptyMap(),
            )
        }
        val origin = originOf(identity.portalBaseUrl)
        val public = linkedMapOf(
            "User-Agent" to (override ?: identity.magUserAgent),
            "X-User-Agent" to identity.xUserAgent,
        ).filterValues { it.isNotBlank() }
        val originBound = buildMap {
            put("Cookie", identity.cookie)
            identity.token?.takeIf { it.isNotEmpty() }?.let { put("Authorization", "Bearer $it") }
            put("Referer", origin)
            put("Origin", origin)
        }.filterValues { it.isNotBlank() }
        return StalkerStreamHeaders(public = public, originBound = originBound, identityFallback = override == null)
    }

    /**
     * iptvnator's predicate: [streamUrl] shares [portalUrl]'s host (case and a trailing DNS-root dot ignored, any
     * port) and is not an https -> http downgrade. Fail closed: unparseable, non-http(s) or blank is false.
     */
    fun isCredentialSafe(portalUrl: String?, streamUrl: String?): Boolean =
        PlaybackCredentialScope.isCredentialSafe(portalUrl, streamUrl)

    /**
     * May the identity set ride a request (or redirect hop) to [hopUrl] when the stream was opened at [originUrl]?
     * The same rule hop by hop: a redirect that leaves the host, or downgrades https -> http, drops
     * [CREDENTIAL_HEADERS]. Used by the engines that can scope a header per hop (Media3/OkHttp on Android).
     */
    fun mayCarryCredentialsTo(originUrl: String?, hopUrl: String?): Boolean =
        PlaybackCredentialScope.isCredentialSafe(originUrl, hopUrl)

    /** `scheme://host[:port]` of [baseUrl] (already normalised, so this only drops a path or trailing slash). */
    private fun originOf(baseUrl: String): String {
        val normalized = StalkerProtocol.normalizePortalBase(baseUrl)
        return normalized.trimEnd('/')
    }
}
