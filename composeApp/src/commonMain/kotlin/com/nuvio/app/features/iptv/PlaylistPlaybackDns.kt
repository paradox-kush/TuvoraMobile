package com.nuvio.app.features.iptv

/**
 * The result of preparing a live stream URL for playback under a per-playlist DNS provider (P3).
 * [url] is what mpv should load; [headers] are extra request headers mpv must send (specifically a
 * `Host:` header when the URL host was rewritten to an IP so the origin still routes correctly).
 */
data class LivePlaybackResolution(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Prepares a live channel's stream URL for the libmpv player under the playlist's DNS provider.
 *
 * Android: when [dnsProvider] names a DoH resolver AND [url] is plain `http://`, the host is resolved
 * over that DoH endpoint (never the system resolver), redirects are followed over the same resolver,
 * and the final media URL is rewritten to `http://<ip>/...` with a `Host: <original-host>` header —
 * so mpv connects to the DoH-resolved IP directly. `https://` URLs are returned UNCHANGED (rewriting
 * the host to an IP would break TLS SNI/cert validation). Any failure returns the original url with no
 * headers — DNS must never break playback.
 *
 * iOS/common: a no-op that returns the url unchanged (MPVKit has no per-app DNS hook wired yet).
 */
expect suspend fun resolveLivePlaybackUrl(url: String, dnsProvider: String?): LivePlaybackResolution
