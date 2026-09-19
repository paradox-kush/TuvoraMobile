package com.nuvio.app.features.player

/**
 * Decides which of the playing stream's request headers may ride along with a subtitle download,
 * and when forwarded credentials must be dropped on a redirect. Pure and dependency-free so it
 * unit-tests on both the JVM and Kotlin/Native runners and is shared across Android, iOS and desktop.
 *
 * Threat model: stream request headers can carry a credential under any name (Authorization, Cookie,
 * a debrid/portal token under a custom header). Forwarding them to a foreign subtitle host — directly,
 * via a cross-host redirect, or over an https->http downgrade — leaks that credential. Subtitle URLs
 * that genuinely need auth on their own host carry it in their own [StreamSubtitle.headers], not the
 * stream's headers.
 */
internal object SubtitleCredentialScope {
    // Hop-by-hop / addressing headers must never be forwarded — they break foreign or CDN edges.
    private val NON_FORWARDABLE = setOf("range", "host", "connection", "transfer-encoding")

    /**
     * The stream headers that may be attached to a subtitle request. Non-empty only when the subtitle
     * shares the stream's host AND the hop does not downgrade https->http. Returns empty when either
     * URL is not http(s), when hosts differ, or on a downgrade — fail closed.
     */
    fun forwardableStreamHeaders(
        streamUrl: String?,
        subtitleUrl: String,
        streamHeaders: Map<String, String>,
    ): Map<String, String> {
        val subtitle = parse(subtitleUrl) ?: return emptyMap()
        val stream = streamUrl?.let(::parse) ?: return emptyMap()
        if (!subtitle.host.equals(stream.host, ignoreCase = true)) return emptyMap()
        if (stream.https && !subtitle.https) return emptyMap()
        return streamHeaders.filterKeys { it.trim().lowercase() !in NON_FORWARDABLE }
    }

    /**
     * True when [hopUrl] (a redirect target) leaves the original subtitle host, so any forwarded
     * stream credentials must be stripped before the request goes out. An unparseable origin or hop
     * is treated as leaving (fail closed).
     */
    fun redirectLeavesOriginHost(originSubtitleUrl: String, hopUrl: String): Boolean {
        val origin = parse(originSubtitleUrl) ?: return true
        val hop = parse(hopUrl) ?: return true
        return !hop.host.equals(origin.host, ignoreCase = true)
    }

    private data class UrlParts(val https: Boolean, val host: String)

    /** Minimal http(s) URL parse: scheme + host, dependency-free. Returns null for non-http(s). */
    private fun parse(url: String): UrlParts? {
        val schemeSep = url.indexOf("://")
        if (schemeSep <= 0) return null
        val scheme = url.substring(0, schemeSep).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val authority = url.substring(schemeSep + 3)
            .substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@') // drop userinfo
        val host = if (authority.startsWith('[')) {
            authority.substringAfter('[').substringBefore(']') // IPv6 literal
        } else {
            authority.substringBefore(':') // drop port
        }
        if (host.isBlank()) return null
        return UrlParts(https = scheme == "https", host = host.lowercase())
    }
}
