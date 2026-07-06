package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P3 DoH endpoint/bootstrap selection ([PlaylistDns]). Pure mapping (no network): every provider maps
 * to its validated DoH URL; pure-IP endpoints (cloudflare, dnssb) carry NO bootstrap, the hostname
 * endpoints (google/mullvad/quad9) carry their published resolver IPs as bootstrap so the endpoint
 * host resolves without the (possibly poisoned) system DNS. "system"/unknown/null map to nothing.
 *
 * Also asserts the DnsOverHttps instances actually build for each provider (that the bootstrap IPs are
 * parseable and the endpoint URLs are valid https) — construction only, still no live lookup.
 */
class PlaylistDnsTest {

    @Test
    fun systemAndUnknownAreNotDohProviders() {
        assertFalse(PlaylistDns.isDohProvider(null))
        assertFalse(PlaylistDns.isDohProvider("system"))
        assertFalse(PlaylistDns.isDohProvider(""))
        assertFalse(PlaylistDns.isDohProvider("nordvpn"))   // not in the validated set
    }

    @Test
    fun realProvidersAreDohProviders() {
        for (p in listOf("cloudflare", "google", "mullvad", "quad9", "dnssb")) {
            assertTrue(PlaylistDns.isDohProvider(p), "expected $p to be a DoH provider")
        }
    }

    @Test
    fun endpointUrlsMatchValidatedSet() {
        assertEquals("https://1.1.1.1/dns-query", PlaylistDns.endpointUrlFor("cloudflare"))
        assertEquals("https://dns.google/dns-query", PlaylistDns.endpointUrlFor("google"))
        assertEquals("https://dns.mullvad.net/dns-query", PlaylistDns.endpointUrlFor("mullvad"))
        assertEquals("https://dns.quad9.net/dns-query", PlaylistDns.endpointUrlFor("quad9"))
        assertEquals("https://185.222.222.222/dns-query", PlaylistDns.endpointUrlFor("dnssb"))
        assertNull(PlaylistDns.endpointUrlFor("system"))
        assertNull(PlaylistDns.endpointUrlFor(null))
    }

    @Test
    fun pureIpEndpointsHaveNoBootstrapAndHostnameEndpointsDo() {
        // Pure-IP endpoints: the host IS an IP, so nothing to bootstrap.
        assertTrue(PlaylistDns.bootstrapHostsFor("cloudflare").isEmpty())
        assertTrue(PlaylistDns.bootstrapHostsFor("dnssb").isEmpty())
        // Hostname endpoints: bootstrapped from the resolver's own IPs (never system DNS).
        assertEquals(listOf("8.8.8.8", "8.8.4.4"), PlaylistDns.bootstrapHostsFor("google"))
        assertEquals(listOf("194.242.2.2"), PlaylistDns.bootstrapHostsFor("mullvad"))
        assertEquals(listOf("9.9.9.9", "149.112.112.112"), PlaylistDns.bootstrapHostsFor("quad9"))
    }

    @Test
    fun dohDnsBuildsForEveryProviderAndIsNullForSystem() {
        for (p in listOf("cloudflare", "google", "mullvad", "quad9", "dnssb")) {
            assertNotNull(PlaylistDns.dohDnsFor(p), "DnsOverHttps should build for $p")
        }
        assertNull(PlaylistDns.dohDnsFor("system"))
        assertNull(PlaylistDns.dohDnsFor(null))
        assertNull(PlaylistDns.dohDnsFor("unknown"))
    }
}
