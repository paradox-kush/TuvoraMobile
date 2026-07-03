package com.nuvio.app.features.iptv.stalker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure-helper tests for the Stalker protocol (no network). MAC matches the validated test portal.
 *  Mirrors NuvioTV's StalkerProtocolTest so both apps derive the SAME device identity for a MAC. */
class StalkerProtocolTest {

    private val mac = "00:1A:79:58:B3:A6"

    @Test
    fun deviceIdentityDerivesSnFromMd5AndDeviceIdFromSha256Deterministically() {
        val id = StalkerProtocol.deriveDeviceIdentity(mac)
        // sn = md5(mac).hex.upper()[:13]  (md5 hex is 32 chars -> take 13)
        assertEquals(13, id.serialNumber.length)
        assertEquals(id.serialNumber, id.serialNumber.uppercase())
        // deviceId = deviceId2 = sha256(mac).hex.upper()  (64 hex chars)
        assertEquals(64, id.deviceId.length)
        assertEquals(id.deviceId, id.deviceId2)
        assertEquals(64, id.signature.length)
        // deterministic for the same MAC
        assertEquals(id.serialNumber, StalkerProtocol.deriveDeviceIdentity(mac).serialNumber)
        assertEquals(id.deviceId, StalkerProtocol.deriveDeviceIdentity(mac).deviceId)
    }

    @Test
    fun serialAndDeviceIdOverridesWinOverMacDerivedValues() {
        val id = StalkerProtocol.deriveDeviceIdentity(mac, serialOverride = "MYSERIAL123", deviceIdOverride = "MYDEVICE")
        assertEquals("MYSERIAL123", id.serialNumber)
        assertEquals("MYDEVICE", id.deviceId)
        // signature folds in the overridden values, so it differs from the pure-MAC identity
        assertTrue(id.signature != StalkerProtocol.deriveDeviceIdentity(mac).signature)
    }

    @Test
    fun extractStreamUrlStripsEveryKnownLauncherPrefix() {
        val url = "http://host:8080/live/u/p/745149.ts?play_token=abc"
        assertEquals(url, StalkerProtocol.extractStreamUrl("ffmpeg $url"))
        assertEquals(url, StalkerProtocol.extractStreamUrl("auto $url"))
        assertEquals(url, StalkerProtocol.extractStreamUrl("ffrt3 $url"))
        assertEquals(url, StalkerProtocol.extractStreamUrl(url))            // already bare
        assertNull(StalkerProtocol.extractStreamUrl(null))
        assertNull(StalkerProtocol.extractStreamUrl("no url here"))
    }

    @Test
    fun macCookieEncodingReplacesColons() {
        assertEquals("00%3A1A%3A79%3A58%3AB3%3AA6", StalkerProtocol.encodeMacForCookie(mac))
    }

    @Test
    fun refererDerivesTheCDirectoryFromTheEndpoint() {
        val base = "http://host:8080"
        assertEquals("$base/c/", StalkerProtocol.refererFor(base, "/portal.php"))
        assertEquals("$base/c/", StalkerProtocol.refererFor(base, "/server/load.php"))
        assertEquals("$base/stalker_portal/c/", StalkerProtocol.refererFor(base, "/stalker_portal/server/load.php"))
        assertEquals("$base/stb/c/", StalkerProtocol.refererFor(base, "/stb/server/load.php"))
    }
}
