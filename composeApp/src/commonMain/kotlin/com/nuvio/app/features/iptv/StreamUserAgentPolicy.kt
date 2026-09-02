package com.nuvio.app.features.iptv

/**
 * Resolves the per-playlist stream User-Agent an IPTV account should send when fetching media,
 * or null to fall back to the player's default UA.
 *
 * Why this exists (a real support loop, first hit on Android TV): a Cloudflare-fronted Xtream panel
 * answered the *stream* request with `HTTP 456` and an empty body while the same account listed
 * channels and played fine in another IPTV app on the same network. The catalog/API calls go out
 * under an honest app UA and pass the WAF; only the stream fetch, pinned to a spoofed-browser
 * default, tripped the "claims to be a browser but isn't" bot rule. The [XtreamAccount.userAgent]
 * field already fed the catalog/EPG fetch — this makes it reach the *player* too, for every source
 * type, so pinning an honest IPTV-client UA (VLC / IBO / a MAG box) unblocks playback.
 *
 * Pure: no platform, no network — the decision tests in isolation.
 */
object StreamUserAgentPolicy {

    /** The override to send as the stream's User-Agent, or null to use the player default. Never blank. */
    fun resolve(account: XtreamAccount): String? =
        account.userAgent?.trim()?.takeIf { it.isNotEmpty() }
}
