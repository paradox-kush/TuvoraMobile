package com.nuvio.app.features.player

/**
 * May a request to [targetUrl] carry credentials that belong to [originUrl]? The browser-cookie rule, as iptvnator's
 * `isStalkerStreamCredentialSafe` states it: the same HOST (case and a trailing DNS-root dot ignored; any port, since
 * IPTV panels serve streams from :8080 beside a portal on :80) and no https -> http downgrade (a session obtained
 * over TLS must not be replayed in cleartext). An http origin may hand off to https on the same host.
 *
 * Fail closed: a blank, unparseable or non-http(s) URL is never credential-safe.
 *
 * Used for a Stalker stream's first request ([com.nuvio.app.features.iptv.stalker.StalkerStreamHeaderPolicy]) and,
 * on engines that can scope a header per hop (Media3 over OkHttp), for every redirect hop. Pure; both runners.
 */
object PlaybackCredentialScope {

    /** Headers that carry a credential and so never ride to a target that is not credential-safe. Lower-case. */
    val CREDENTIAL_HEADERS: Set<String> = setOf("cookie", "authorization")

    fun isCredentialSafe(originUrl: String?, targetUrl: String?): Boolean {
        val origin = parse(originUrl) ?: return false
        val target = parse(targetUrl) ?: return false
        if (origin.host != target.host) return false
        return !(origin.https && !target.https)
    }

    private data class Parts(val https: Boolean, val host: String)

    private fun parse(url: String?): Parts? {
        val raw = url?.trim().orEmpty()
        val schemeSep = raw.indexOf("://")
        if (schemeSep <= 0) return null
        val scheme = raw.substring(0, schemeSep).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val authority = raw.substring(schemeSep + 3)
            .substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@') // userinfo is never the host
        val host = if (authority.startsWith('[')) {
            authority.substringAfter('[').substringBefore(']') // IPv6 literal
        } else {
            authority.substringBefore(':')
        }.lowercase().removeSuffix(".")
        if (host.isBlank() || host.any { it.isWhitespace() }) return null
        return Parts(https = scheme == "https", host = host)
    }
}
