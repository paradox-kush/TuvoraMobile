package com.nuvio.app.features.iptv

import com.nuvio.app.features.addons.EmptyResponseBodyException
import com.nuvio.app.features.addons.HttpStatusException
import com.nuvio.app.features.iptv.stalker.StalkerAuthException
import com.nuvio.app.features.iptv.stalker.StalkerDeviceConflictException
import com.nuvio.app.features.iptv.stalker.StalkerPortalRefusedException
import com.nuvio.app.features.iptv.stalker.StalkerSessionUnavailableException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The hub card's copy decision. Regression cover for a real support loop: a provider's Cloudflare
 * answered 403 to a live Stalker portal and the app told the viewer "Couldn't reach this playlist /
 * Check the portal is up and try again" — so they went and proved the portal was up, repeatedly,
 * while the app knew perfectly well it had been blocked.
 */
class IptvLoadFailurePolicyTest {

    private fun classify(t: Throwable?, host: String? = null) = IptvLoadFailurePolicy.classify(t, host)

    // --- the edge turned us away (portal is healthy) --------------------------

    @Test
    fun `a 403 reads as a provider block and not as a dead portal`() {
        val failure = classify(HttpStatusException(403, "Request failed with HTTP 403"))
        assertEquals(IptvLoadFailurePolicy.Kind.BLOCKED_BY_PROVIDER, failure.kind, "403 is a WAF block")
        assertEquals(403, failure.status, "the card shows the code it was given")
    }

    @Test
    fun `every blocking status classifies the same way`() {
        for (status in listOf(403, 419, 429, 451, 456)) {
            assertEquals(
                IptvLoadFailurePolicy.Kind.BLOCKED_BY_PROVIDER,
                classify(HttpStatusException(status, "HTTP $status")).kind,
                "HTTP $status means the edge refused us",
            )
        }
    }

    @Test
    fun `a wrong portal path stays unreachable rather than claiming a block`() {
        // 404 = the URL is wrong; telling the viewer they are blocked would send them nowhere useful.
        assertEquals(IptvLoadFailurePolicy.Kind.UNREACHABLE, classify(HttpStatusException(404, "HTTP 404")).kind)
    }

    @Test
    fun `a failing origin stays unreachable`() {
        for (status in listOf(500, 502, 503)) {
            assertEquals(
                IptvLoadFailurePolicy.Kind.UNREACHABLE,
                classify(HttpStatusException(status, "HTTP $status")).kind,
                "HTTP $status really is the portal being unwell",
            )
        }
    }

    // --- the portal answered and said no --------------------------------------

    @Test
    fun `a device conflict surfaces the portal's own remedy`() {
        val message = "Another device is using this MAC on portal — ask the provider to reset the MAC."
        val failure = classify(StalkerDeviceConflictException(message))
        assertEquals(IptvLoadFailurePolicy.Kind.REFUSED, failure.kind)
        assertEquals(message, failure.portalText, "the remedy is the whole point of surfacing it")
    }

    @Test
    fun `a plain portal refusal is a refusal too`() {
        val failure = classify(StalkerPortalRefusedException("Stalker portal refused portal."))
        assertEquals(IptvLoadFailurePolicy.Kind.REFUSED, failure.kind)
        assertEquals("Stalker portal refused portal.", failure.portalText)
    }

    @Test
    fun `a rejected device identity is a refusal`() {
        val failure = classify(StalkerAuthException("check the MAC address"))
        assertEquals(IptvLoadFailurePolicy.Kind.REFUSED, failure.kind)
        assertEquals("check the MAC address", failure.portalText)
    }

    @Test
    fun `a line held by another device is a refusal`() {
        val failure = classify(StalkerSessionUnavailableException("held by another device"))
        assertEquals(IptvLoadFailurePolicy.Kind.REFUSED, failure.kind)
        assertEquals("held by another device", failure.portalText)
    }

    @Test
    fun `a refusal with no text still classifies and lets the card fall back`() {
        val failure = classify(StalkerPortalRefusedException("   "))
        assertEquals(IptvLoadFailurePolicy.Kind.REFUSED, failure.kind)
        assertNull(failure.portalText, "blank text must not render as an empty message")
    }

    // --- everything else -------------------------------------------------------

    @Test
    fun `an unknown failure never leaks its message to the viewer`() {
        val failure = classify(IllegalStateException("java.net.UnknownHostException: tv.example.biz"))
        assertEquals(IptvLoadFailurePolicy.Kind.UNREACHABLE, failure.kind)
        assertNull(failure.portalText, "raw transport text is noise")
        assertNull(failure.status)
    }

    @Test
    fun `an empty body is not mistaken for a block`() {
        // The portal answered 200 with nothing — a session takeover the Stalker layer retries.
        assertEquals(IptvLoadFailurePolicy.Kind.UNREACHABLE, classify(EmptyResponseBodyException("Empty response body")).kind)
    }

    // --- the support breadcrumb ------------------------------------------------

    @Test
    fun `a block names the status and the provider for a screenshot`() {
        val failure = classify(HttpStatusException(403, "Request failed with HTTP 403"), host = "http://tv.example.biz")
        assertEquals("HTTP 403 · http://tv.example.biz", failure.detail, "one line support can read off a photo")
    }

    @Test
    fun `an unknown failure still names its type`() {
        assertEquals("IllegalStateException · http://p.example", classify(IllegalStateException("boom"), host = "http://p.example").detail)
    }

    @Test
    fun `the breadcrumb never carries the exception message`() {
        // Messages embed the playlist's name; viewers post these screenshots in public channels.
        val failure = classify(StalkerDeviceConflictException("Another device is using this MAC on Kev's Portal"), host = "http://p.example")
        assertTrue("Kev" !in failure.detail, "the account name must not ride the breadcrumb")
        assertEquals("StalkerDeviceConflictException · http://p.example", failure.detail)
    }

    @Test
    fun `a playlist with no panel origin still gets a reason`() {
        assertEquals("HTTP 429", classify(HttpStatusException(429, "HTTP 429"), host = null).detail)
    }

    @Test
    fun `a missing throwable degrades to the generic copy`() {
        val failure = classify(null)
        assertEquals(IptvLoadFailurePolicy.Kind.UNREACHABLE, failure.kind)
        assertTrue(failure.status == null && failure.portalText == null)
        assertEquals("unknown error", failure.detail, "even a missing cause leaves something to report")
    }
}
