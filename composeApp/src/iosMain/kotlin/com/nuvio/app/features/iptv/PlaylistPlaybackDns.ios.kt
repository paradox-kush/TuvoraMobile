package com.nuvio.app.features.iptv

/**
 * iOS no-op for the per-playlist live DNS path (P3). MPVKit resolves + connects itself and there is
 * no per-app DNS hook on iOS, so a DoH provider can't be applied to live playback here — the URL is
 * returned unchanged. The settings form already tells the user this is Android-only. (Re-resolving in
 * the MPVKit bridge is a possible future upgrade; deferred.)
 */
actual suspend fun resolveLivePlaybackUrl(url: String, dnsProvider: String?): LivePlaybackResolution =
    LivePlaybackResolution(url)
