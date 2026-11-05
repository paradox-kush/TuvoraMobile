package com.nuvio.app.features.iptv

import com.nuvio.app.features.addons.HttpStatusException
import com.nuvio.app.features.iptv.stalker.StalkerDeviceConflictException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UX11 / UX20 / UX21 / B23 — what the Add/Edit Playlist form says when a save fails. One sentence per
 * failure the user can act on, never the platform's raw exception text, and never "enter a server URL,
 * username and password" when all three are filled in.
 */
class PlaylistSaveErrorPolicyTest {

    private fun form(
        server: String = "http://panel.example:8080",
        username: String = "u",
        password: String = "p",
        sourceType: String = SOURCE_TYPE_XTREAM,
        m3uUrl: String = "",
        mac: String = "",
    ) = XtreamFormInput(
        serverUrl = server, username = username, password = password, name = null, epgUrl = null,
        dnsProvider = "system", autoRefreshHours = 24, sourceType = sourceType, m3uUrl = m3uUrl, macAddress = mac,
    )

    /** Stand-in for the platform transport mapping, so the shared rules test without a network stack. */
    private fun transport(map: Map<String, PlaylistTransportFailure>): (Throwable) -> PlaylistTransportFailure? =
        { t -> map[t.message] }

    private fun known(e: PlaylistSaveError) = PlaylistSaveMessage.Known(e)

    // --- form validation (before any network) -------------------------------------------------

    @Test
    fun `a complete Xtream form is valid`() {
        assertNull(PlaylistSaveErrorPolicy.validate(form()))
        assertNull(PlaylistSaveErrorPolicy.validate(form(server = "panel.example")), "a bare host gets http:// later")
    }

    @Test
    fun `truly empty Xtream fields keep the enter-all-three message`() {
        assertEquals(PlaylistSaveError.MISSING_XTREAM_FIELDS, PlaylistSaveErrorPolicy.validate(form(username = " ")))
        assertEquals(PlaylistSaveError.MISSING_XTREAM_FIELDS, PlaylistSaveErrorPolicy.validate(form(server = "")))
    }

    @Test
    fun `an invalid port is an invalid address - not a missing field - UX21`() {
        assertEquals(PlaylistSaveError.INVALID_ADDRESS, PlaylistSaveErrorPolicy.validate(form(server = "http://panel.example:99999")))
        assertEquals(PlaylistSaveError.INVALID_ADDRESS, PlaylistSaveErrorPolicy.validate(form(server = "panel.example:abc")))
        assertEquals(PlaylistSaveError.INVALID_ADDRESS, PlaylistSaveErrorPolicy.validate(form(server = "http://panel.example:0")))
        assertEquals(PlaylistSaveError.INVALID_ADDRESS, PlaylistSaveErrorPolicy.validate(form(server = "http://")))
        assertEquals(PlaylistSaveError.INVALID_ADDRESS, PlaylistSaveErrorPolicy.validate(form(server = "http://pa nel.example")))
    }

    @Test
    fun `valid ports and IPv6 literals pass`() {
        assertNull(PlaylistSaveErrorPolicy.validate(form(server = "http://panel.example:65535")))
        assertNull(PlaylistSaveErrorPolicy.validate(form(server = "https://panel.example/")))
        assertNull(PlaylistSaveErrorPolicy.validate(form(server = "http://[::1]:8080")))
        assertNull(PlaylistSaveErrorPolicy.validate(form(server = "http://10.0.2.2:8999/c/")))
    }

    @Test
    fun `M3U and Stalker forms say what is missing for their own fields`() {
        assertEquals(PlaylistSaveError.MISSING_M3U_URL, PlaylistSaveErrorPolicy.validate(form(sourceType = SOURCE_TYPE_M3U_URL)))
        assertEquals(
            PlaylistSaveError.INVALID_ADDRESS,
            PlaylistSaveErrorPolicy.validate(form(sourceType = SOURCE_TYPE_M3U_URL, m3uUrl = "http://h:70000/list.m3u")),
        )
        assertNull(PlaylistSaveErrorPolicy.validate(form(sourceType = SOURCE_TYPE_M3U_URL, m3uUrl = "h.example/list.m3u")))
        assertEquals(PlaylistSaveError.MISSING_STALKER_FIELDS, PlaylistSaveErrorPolicy.validate(form(sourceType = SOURCE_TYPE_STALKER)))
        assertEquals(
            PlaylistSaveError.INVALID_ADDRESS,
            PlaylistSaveErrorPolicy.validate(form(sourceType = SOURCE_TYPE_STALKER, server = "portal:1234567", mac = "00:1A:79:00:00:01")),
        )
        assertNull(PlaylistSaveErrorPolicy.validate(form(sourceType = SOURCE_TYPE_STALKER, server = "portal.example/c/", mac = "00:1A:79:00:00:01")))
    }

    // --- failures from the live check ---------------------------------------------------------

    @Test
    fun `transport failures map to their plain sentences - UX20 and B23`() {
        val t = transport(
            mapOf(
                "dns" to PlaylistTransportFailure.UNREACHABLE,
                "tls" to PlaylistTransportFailure.SECURE_CONNECTION,
                "url" to PlaylistTransportFailure.INVALID_ADDRESS,
            ),
        )
        assertEquals(known(PlaylistSaveError.UNREACHABLE), PlaylistSaveErrorPolicy.classify(RuntimeException("dns"), SOURCE_TYPE_XTREAM, t))
        assertEquals(known(PlaylistSaveError.SECURE_CONNECTION_FAILED), PlaylistSaveErrorPolicy.classify(RuntimeException("tls"), SOURCE_TYPE_M3U_URL, t))
        assertEquals(known(PlaylistSaveError.INVALID_ADDRESS), PlaylistSaveErrorPolicy.classify(RuntimeException("url"), SOURCE_TYPE_STALKER, t))
    }

    @Test
    fun `a transport failure wrapped as a cause is still found`() {
        val t = transport(mapOf("tls" to PlaylistTransportFailure.SECURE_CONNECTION))
        val wrapped = IllegalStateException("wrapper", RuntimeException("tls"))
        assertEquals(known(PlaylistSaveError.SECURE_CONNECTION_FAILED), PlaylistSaveErrorPolicy.classify(wrapped, SOURCE_TYPE_XTREAM, t))
    }

    @Test
    fun `a credential rejection is wrong username or password`() {
        val none = transport(emptyMap())
        assertEquals(known(PlaylistSaveError.WRONG_CREDENTIALS), PlaylistSaveErrorPolicy.classify(XtreamAuthRejectedException(), SOURCE_TYPE_XTREAM, none))
        assertEquals(known(PlaylistSaveError.WRONG_CREDENTIALS), PlaylistSaveErrorPolicy.classify(HttpStatusException(401, "HTTP 401"), SOURCE_TYPE_XTREAM, none))
    }

    @Test
    fun `a login every backup server refused is wrong username or password`() {
        // Step 0.3 failover: a definitive refusal from the validation probe surfaces as FailoverAuthRejectedException.
        val none = transport(emptyMap())
        val refused = FailoverAuthRejectedException("Xtream login probe: the account was refused")
        assertEquals(known(PlaylistSaveError.WRONG_CREDENTIALS), PlaylistSaveErrorPolicy.classify(refused, SOURCE_TYPE_XTREAM, none))
    }

    @Test
    fun `a provider firewall status is a block - not a username or password problem`() {
        // 403/429/456 come from the provider's edge (WAF / Cloudflare), measured on real providers:
        // telling the viewer their password is wrong would send them to retype a correct one.
        val none = transport(emptyMap())
        for (status in listOf(403, 419, 429, 451, 456)) {
            for (type in listOf(SOURCE_TYPE_XTREAM, SOURCE_TYPE_M3U_URL, SOURCE_TYPE_STALKER)) {
                assertEquals(
                    known(PlaylistSaveError.PROVIDER_BLOCKED),
                    PlaylistSaveErrorPolicy.classify(HttpStatusException(status, "HTTP $status"), type, none),
                    "HTTP $status for $type",
                )
            }
        }
    }

    @Test
    fun `other HTTP statuses and an open breaker read as unreachable`() {
        val none = transport(emptyMap())
        assertEquals(known(PlaylistSaveError.UNREACHABLE), PlaylistSaveErrorPolicy.classify(HttpStatusException(404, "HTTP 404"), SOURCE_TYPE_XTREAM, none))
        assertEquals(known(PlaylistSaveError.UNREACHABLE), PlaylistSaveErrorPolicy.classify(HttpStatusException(503, "HTTP 503"), SOURCE_TYPE_XTREAM, none))
        assertEquals(
            known(PlaylistSaveError.UNREACHABLE),
            PlaylistSaveErrorPolicy.classify(PanelHostFastFailException("http://dead.invalid", 0L), SOURCE_TYPE_XTREAM, none),
        )
    }

    @Test
    fun `a Stalker 403 is not reported as a username or password problem`() {
        // Stalker signs in by MAC; there is no username/password for the user to fix.
        assertEquals(
            known(PlaylistSaveError.PROVIDER_BLOCKED),
            PlaylistSaveErrorPolicy.classify(HttpStatusException(403, "HTTP 403"), SOURCE_TYPE_STALKER, transport(emptyMap())),
        )
    }

    @Test
    fun `our own worded explanations pass through unchanged`() {
        val none = transport(emptyMap())
        val remedy = "This MAC is bound to another device — ask your provider to reset it"
        assertEquals(PlaylistSaveMessage.Authored(remedy), PlaylistSaveErrorPolicy.classify(StalkerDeviceConflictException(remedy), SOURCE_TYPE_STALKER, none))
        assertEquals(
            PlaylistSaveMessage.Authored("Account status: Expired"),
            PlaylistSaveErrorPolicy.classify(XtreamAccountInactiveException("Expired"), SOURCE_TYPE_XTREAM, none),
        )
        assertEquals(
            PlaylistSaveMessage.Authored(M3U_NO_CONTENT_MESSAGE),
            PlaylistSaveErrorPolicy.classify(M3UNoContentException(), SOURCE_TYPE_M3U_URL, none),
        )
    }

    @Test
    fun `raw unknown errors never reach the user - UX11`() {
        val raw = RuntimeException("Failed to connect to /10.0.2.2:8999")
        assertEquals(known(PlaylistSaveError.UNREACHABLE), PlaylistSaveErrorPolicy.classify(raw, SOURCE_TYPE_XTREAM, transport(emptyMap())))
        assertEquals(known(PlaylistSaveError.FILE_UNREADABLE), PlaylistSaveErrorPolicy.classify(raw, SOURCE_TYPE_M3U_FILE, transport(emptyMap())))
    }

    @Test
    fun `the approved wording is pinned`() {
        assertEquals("Couldn't reach the server — check the address", PlaylistSaveError.UNREACHABLE.fallbackText)
        assertEquals("Wrong username or password", PlaylistSaveError.WRONG_CREDENTIALS.fallbackText)
        assertEquals("That server address isn't valid", PlaylistSaveError.INVALID_ADDRESS.fallbackText)
        assertEquals("Enter a server URL, username and password", PlaylistSaveError.MISSING_XTREAM_FIELDS.fallbackText)
        assertEquals("Secure connection failed — try http:// or check the certificate", PlaylistSaveError.SECURE_CONNECTION_FAILED.fallbackText)
    }

    // --- the save-anyway warning (UX11) -------------------------------------------------------

    @Test
    fun `a failed edit check carries the mapped failure - never the raw text`() {
        val outcome = PlaylistEditVerifyPolicy.outcome(
            Result.failure(RuntimeException("Failed to connect to /10.0.2.2:8999")),
            SOURCE_TYPE_XTREAM,
        )
        assertEquals(true, outcome.save)
        assertEquals(known(PlaylistSaveError.UNREACHABLE), outcome.failure)
    }

    // --- B224: the device-source guard's refusal is not a network failure ---------------------------

    @Test
    fun `a device-source refusal is its own message and never unreachable`() {
        val refusal = com.nuvio.app.core.sources.SourceDisabledOnDeviceException("iptv", "loading")
        for (type in listOf(SOURCE_TYPE_XTREAM, SOURCE_TYPE_M3U_URL, SOURCE_TYPE_STALKER)) {
            assertEquals(known(PlaylistSaveError.SOURCE_DISABLED_ON_DEVICE), PlaylistSaveErrorPolicy.classify(refusal, type))
        }
        val text = PlaylistSaveError.SOURCE_DISABLED_ON_DEVICE.fallbackText
        assertTrue("reach" !in text && text.contains("this device"), text)
    }

    @Test
    fun `a plain cancellation is still not blamed on the device guard`() {
        val plain = kotlinx.coroutines.CancellationException("scope cancelled")
        assertEquals(known(PlaylistSaveError.UNREACHABLE), PlaylistSaveErrorPolicy.classify(plain, SOURCE_TYPE_XTREAM))
    }

    @Test
    fun `an account-wide refusal says so instead of blaming this device`() {
        val refusal = com.nuvio.app.core.sources.SourceDisabledOnDeviceException("iptv", "ready", com.nuvio.app.core.sources.RefusalScope.ACCOUNT)
        assertEquals(known(PlaylistSaveError.SOURCE_DISABLED_FOR_ACCOUNT), PlaylistSaveErrorPolicy.classify(refusal, SOURCE_TYPE_XTREAM))
        assertTrue("this device" !in PlaylistSaveError.SOURCE_DISABLED_FOR_ACCOUNT.fallbackText)
    }
}
