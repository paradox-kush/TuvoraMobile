package com.nuvio.app.core.network

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-playlist DNS-over-HTTPS (P3, Android only). Maps an [XtreamAccount.dnsProvider] value to a
 * cached [OkHttpClient] whose DNS is resolved over an encrypted DoH endpoint, so a user on a network
 * that hijacks/blocks plain DNS can still reach their IPTV panel + media hosts. iOS has no per-app
 * DNS hook (URLSession/Ktor Darwin), so the field is a no-op there (documented on the settings form).
 *
 * Endpoint choice rule (memory `nuvio-iptv-project`): NEVER use the system resolver to find the
 * resolver. A pure-IP endpoint (cloudflare 1.1.1.1, dnssb 185.222.222.222) needs no bootstrap; the
 * hostname endpoints (google/mullvad/quad9) are bootstrapped from hardcoded resolver IPs so the very
 * first lookup of the DoH host itself doesn't fall back to the (possibly poisoned) system DNS.
 *
 * HTTPS-over-DoH caveat: this only rewrites/pins DNS for the *fetch client*. It does NOT rewrite a
 * media URL's host to an IP (which would break TLS SNI) — that IP-rewrite lives in the live-mpv path
 * and is applied to plain-`http://` streams only. See [resolveLivePlaybackUrl].
 */
object PlaylistDns {

    /** Canonical provider ids persisted on XtreamAccount.dnsProvider. */
    const val SYSTEM = "system"

    /**
     * DoH endpoint + optional bootstrap hosts per provider. Bootstrap hosts are the resolver's own
     * IPs, parsed as [InetAddress] so the endpoint hostname resolves without the system DNS. A pure-IP
     * URL has an empty bootstrap list (the host IS an IP — nothing to resolve).
     */
    private data class DohSpec(val url: String, val bootstrap: List<String>)

    private val specs: Map<String, DohSpec> = mapOf(
        // Pure-IP endpoints — no bootstrap needed.
        "cloudflare" to DohSpec("https://1.1.1.1/dns-query", emptyList()),
        "dnssb" to DohSpec("https://185.222.222.222/dns-query", emptyList()),
        // Hostname endpoints — bootstrapped from the resolver's published IPs.
        "google" to DohSpec("https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4")),
        "mullvad" to DohSpec("https://dns.mullvad.net/dns-query", listOf("194.242.2.2")),
        "quad9" to DohSpec("https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112")),
    )

    /** Whether a provider id maps to a real DoH resolver (i.e. anything other than system/blank). */
    fun isDohProvider(dnsProvider: String?): Boolean =
        dnsProvider != null && dnsProvider != SYSTEM && specs.containsKey(dnsProvider)

    // One shared bootstrap client for building the DnsOverHttps instances (its own connection pool is
    // reused by the per-provider clients via newBuilder()). Lazy so nothing is built until a non-system
    // playlist is actually used.
    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder().build()
    }

    private val clientCache = ConcurrentHashMap<String, OkHttpClient>()

    /**
     * A cached [OkHttpClient] resolving DNS over the given provider's DoH endpoint, or null when the
     * provider is system/unknown (the caller then uses its default client). Clients share the bootstrap
     * client's connection pool + dispatcher (via [OkHttpClient.newBuilder]); only the [Dns] differs.
     */
    fun clientFor(dnsProvider: String?, base: OkHttpClient): OkHttpClient? {
        if (!isDohProvider(dnsProvider)) return null
        val provider = dnsProvider!!
        return clientCache.getOrPut(provider) {
            val dns = dohDnsFor(provider) ?: return null
            base.newBuilder().dns(dns).build()
        }
    }

    /** Builds (uncached) the [Dns] for a provider — exposed for tests; null for system/unknown. */
    fun dohDnsFor(dnsProvider: String?): Dns? {
        val spec = dnsProvider?.let { specs[it] } ?: return null
        return DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url(spec.url.toHttpUrl())
            .apply {
                if (spec.bootstrap.isNotEmpty()) {
                    bootstrapDnsHosts(spec.bootstrap.map { InetAddress.getByName(it) })
                }
            }
            .build()
    }

    // --- test seams (pure, no network) --------------------------------------------

    /** The DoH query URL a provider resolves through (for endpoint-selection tests). */
    internal fun endpointUrlFor(dnsProvider: String?): String? = dnsProvider?.let { specs[it]?.url }

    /** The bootstrap resolver IPs for a provider — empty for pure-IP endpoints (for tests). */
    internal fun bootstrapHostsFor(dnsProvider: String?): List<String> =
        dnsProvider?.let { specs[it]?.bootstrap } ?: emptyList()
}
