package com.nuvio.app.features.iptv

import com.nuvio.app.core.network.PlaylistDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Android live-mpv DNS path (P3). See the common [resolveLivePlaybackUrl] contract.
 *
 * The common case for IPTV live is a plain-`http://` `.ts` URL that 302-redirects to a load-balancer
 * host. To honour the playlist's DoH provider through to mpv (which does its OWN DNS, bypassing the
 * OkHttp resolver), we: (1) resolve+follow redirects over the DoH client to the final media URL,
 * (2) resolve that final host to an IP over the same DoH endpoint, (3) hand mpv the IP-rewritten URL
 * plus a `Host:` header so the origin still routes. `https://` is left untouched (SNI-by-IP breaks TLS)
 * and every failure falls back to the original URL — playback must never break for the sake of DNS.
 */
actual suspend fun resolveLivePlaybackUrl(url: String, dnsProvider: String?): LivePlaybackResolution {
    val original = LivePlaybackResolution(url)
    if (!PlaylistDns.isDohProvider(dnsProvider)) return original

    return withContext(Dispatchers.IO) {
        runCatching {
            val parsed = url.toHttpUrlOrNull() ?: return@runCatching original
            // https: rewriting host->IP breaks TLS SNI/cert validation. Leave it to the system.
            if (parsed.isHttps) return@runCatching original

            val dohClient = PlaylistDns.clientFor(dnsProvider, redirectResolveClient)
                ?: return@runCatching original

            // 1) Follow redirects over the DoH client to the FINAL media URL (HEAD-like GET, no body read).
            val finalUrl = resolveFinalUrl(dohClient, parsed) ?: parsed
            if (finalUrl.isHttps) return@runCatching original  // a redirect bounced us to https — leave it

            // 2) Resolve the final host to an IP over the same DoH endpoint. Prefer IPv4 — an IPv6
            //    literal needs [bracket] quoting in a URL and IPv6 routes are flakier on mobile (the
            //    app already prefers v4 via IPv4FirstDns); fall back to the first address otherwise.
            val dns = PlaylistDns.dohDnsFor(dnsProvider) ?: return@runCatching original
            val addresses = dns.lookup(finalUrl.host)
            val ip = (addresses.firstOrNull { it is java.net.Inet4Address } ?: addresses.firstOrNull())
                ?.hostAddress
                ?: return@runCatching original

            // 3) Rewrite host -> IP and carry the original Host so the origin routes correctly.
            rewriteHostToIp(finalUrl.toString(), ip)
        }.getOrDefault(original)
    }
}

/**
 * Follows redirects to the final URL WITHOUT downloading the body. A single GET with the DoH client
 * (which follows redirects) lands on the final URL; we read `response.request.url` and close the body.
 * Returns null on any transport error (caller falls back).
 */
private fun resolveFinalUrl(client: OkHttpClient, url: HttpUrl): HttpUrl? =
    runCatching {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response -> response.request.url }
    }.getOrNull()

// A short-timeout client used only to walk redirects for the live URL — it must follow redirects and
// carry no proxy. The DoH resolver is layered on via PlaylistDns.clientFor(newBuilder().dns(...)).
private val redirectResolveClient: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(true)
    .followSslRedirects(true)
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()

/**
 * Rewrites [rawUrl]'s host to [ip], returning the IP URL plus a `Host: <original-host>` header (pure,
 * no I/O — unit-tested). A host that is ALREADY a bare IP is returned unchanged with no header (nothing
 * to route). Falls back to the original url when the input can't be parsed.
 */
internal fun rewriteHostToIp(rawUrl: String, ip: String): LivePlaybackResolution {
    val parsed = rawUrl.toHttpUrlOrNull() ?: return LivePlaybackResolution(rawUrl)
    val host = parsed.host
    // If the host is already the IP (or literally the same string), no rewrite/header is needed.
    if (host == ip) return LivePlaybackResolution(rawUrl)
    val rewritten = parsed.newBuilder().host(ip).build().toString()
    // The Host header must carry the ORIGINAL authority incl. a non-default port (IPTV panels commonly
    // run on :8080/:25461/etc.) — dropping the port misroutes on load balancers that split by port.
    val defaultPort = if (parsed.scheme == "https") 443 else 80
    val hostHeader = if (parsed.port != defaultPort) "$host:${parsed.port}" else host
    return LivePlaybackResolution(rewritten, mapOf("Host" to hostHeader))
}
