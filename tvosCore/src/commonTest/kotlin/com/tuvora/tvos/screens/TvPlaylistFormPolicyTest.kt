package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.SOURCE_TYPE_M3U_FILE
import com.nuvio.app.features.iptv.SOURCE_TYPE_M3U_URL
import com.nuvio.app.features.iptv.SOURCE_TYPE_STALKER
import com.nuvio.app.features.iptv.SOURCE_TYPE_XTREAM
import com.nuvio.app.features.iptv.XtreamAccount
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvPlaylistFormPolicyTest {
    private val blank = TvPlaylistFormPolicy.empty()

    @Test
    fun `xtream details need server username and password`() {
        assertFalse(TvPlaylistFormPolicy.canSubmit(blank.copy(server = "http://h:80", username = "u")))
        assertTrue(TvPlaylistFormPolicy.canSubmit(blank.copy(server = "http://h:80", username = "u", password = "p")))
    }

    @Test
    fun `xtream paste link needs only the link`() {
        assertFalse(TvPlaylistFormPolicy.canSubmit(blank.copy(pasteLink = true, server = "http://h", username = "u", password = "p")))
        assertTrue(TvPlaylistFormPolicy.canSubmit(blank.copy(pasteLink = true, playlistUrl = "http://h/get.php?username=u&password=p")))
    }

    @Test
    fun `m3u url stalker and file sources`() {
        assertTrue(TvPlaylistFormPolicy.canSubmit(TvPlaylistFormPolicy.empty(SOURCE_TYPE_M3U_URL).copy(m3uUrl = "http://h/list.m3u")))
        assertFalse(TvPlaylistFormPolicy.canSubmit(TvPlaylistFormPolicy.empty(SOURCE_TYPE_STALKER).copy(portalUrl = "http://p")))
        assertTrue(TvPlaylistFormPolicy.canSubmit(TvPlaylistFormPolicy.empty(SOURCE_TYPE_STALKER).copy(portalUrl = "http://p", macAddress = "00:1A:79:00:00:01")))
        // Apple TV has no document picker, so a file playlist is never submittable here.
        assertFalse(TvPlaylistFormPolicy.canSubmit(TvPlaylistFormPolicy.empty(SOURCE_TYPE_M3U_FILE).copy(name = "x")))
    }

    @Test
    fun `pasted xtream link is split into server and credentials`() {
        val input = assertNotNull(TvPlaylistFormPolicy.toInput(blank.copy(pasteLink = true, playlistUrl = " http://host:8080/get.php?username=alice&password=secret&type=m3u_plus ")))
        assertEquals("http://host:8080", input.serverUrl)
        assertEquals("alice", input.username)
        assertEquals("secret", input.password)
        assertEquals(SOURCE_TYPE_XTREAM, input.sourceType)
    }

    @Test
    fun `pasted link without credentials yields no input`() {
        assertNull(TvPlaylistFormPolicy.toInput(blank.copy(pasteLink = true, playlistUrl = "http://host:8080/")))
    }

    @Test
    fun `stalker input uses the portal as server and blanks xtream creds`() {
        val form = TvPlaylistFormPolicy.empty(SOURCE_TYPE_STALKER).copy(
            portalUrl = " http://portal:88 ", macAddress = "00:1A:79:AA:BB:CC", username = "ignored",
            stalkerUsername = "", serialNumber = "SN1", sendDeviceId = false,
        )
        val input = assertNotNull(TvPlaylistFormPolicy.toInput(form))
        assertEquals("http://portal:88", input.serverUrl)
        assertEquals("", input.username)
        assertEquals("00:1A:79:AA:BB:CC", input.macAddress)
        assertNull(input.stalkerUsername)
        assertEquals("SN1", input.serialNumber)
        assertEquals(false, input.sendDeviceId)
    }

    @Test
    fun `optional fields become null and dns stays system`() {
        val input = assertNotNull(TvPlaylistFormPolicy.toInput(blank.copy(server = "http://h", username = "u", password = "p", name = "  ", epgUrl = "", userAgent = " ")))
        assertNull(input.name)
        assertNull(input.epgUrl)
        assertNull(input.userAgent)
        assertEquals("system", input.dnsProvider)
        assertEquals(24, input.autoRefreshHours)
    }

    @Test
    fun `edit prefill follows the source type`() {
        val xtream = XtreamAccount(id = "x", name = "Home", baseUrl = "http://h:80", username = "u", password = "p", autoRefreshHours = 12)
        val form = TvPlaylistFormPolicy.fromAccount(xtream)
        assertEquals("http://h:80", form.server)
        assertEquals("u", form.username)
        assertEquals(12, form.autoRefreshHours)
        assertFalse(form.pasteLink)

        val m3u = XtreamAccount(id = "m", name = "List", baseUrl = "http://h/l.m3u", username = "", password = "", sourceType = SOURCE_TYPE_M3U_URL)
        assertEquals("http://h/l.m3u", TvPlaylistFormPolicy.fromAccount(m3u).m3uUrl)

        val stalker = XtreamAccount(id = "s", name = "Box", baseUrl = "http://p", username = "", password = "", sourceType = SOURCE_TYPE_STALKER, macAddress = "00:1A:79:01:02:03")
        val sf = TvPlaylistFormPolicy.fromAccount(stalker)
        assertEquals("http://p", sf.portalUrl)
        assertEquals("00:1A:79:01:02:03", sf.macAddress)
    }

    @Test
    fun `random mac stays in the infomir range`() {
        val mac = TvPlaylistFormPolicy.randomStbMac(Random(7))
        assertTrue(Regex("^00:1A:79:[0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2}$").matches(mac), mac)
    }

    @Test
    fun `auto refresh labels match nuviotv`() {
        assertEquals("Off", TvPlaylistFormPolicy.autoRefreshLabel(0))
        assertEquals("24h", TvPlaylistFormPolicy.autoRefreshLabel(24))
    }

    // --- ManagedEditPolicy on the Apple TV edit path (contract section 6) --------------------------

    /** Provider-owned fields chosen so any trim / lower-case / default-port / trailing-slash pass changes a byte. */
    private val managedXtream = XtreamAccount(
        id = "mx", name = "Acme Live", baseUrl = "HTTP://Panel.Example.COM:80/", username = " Alice ",
        password = " p@ss ", userAgent = " Acme/1.0 ", epgUrl = " http://EPG.example.com:80/x.xml ",
        backupUrls = listOf("http://Backup.Example.com:80/", "HTTPS://b2.example.com:443/"), autoRefreshHours = 12,
    )

    private fun assertProviderFieldsUntouched(pulled: XtreamAccount, input: com.nuvio.app.features.iptv.XtreamFormInput) {
        val expectedServer = when (pulled.sourceType) {
            SOURCE_TYPE_M3U_URL, SOURCE_TYPE_STALKER -> pulled.baseUrl
            else -> pulled.baseUrl
        }
        assertEquals(expectedServer, input.serverUrl, "server address must be byte-identical")
        assertEquals(pulled.username, input.username, "username")
        assertEquals(pulled.password, input.password, "password")
        assertEquals(pulled.epgUrl, input.epgUrl, "epg url")
        assertEquals(pulled.userAgent, input.userAgent, "user agent")
        assertEquals(pulled.backupUrls, input.backupUrls, "backup servers")
        assertEquals(pulled.macAddress, input.macAddress, "mac")
        assertEquals(pulled.stalkerUsername, input.stalkerUsername, "stalker username")
        assertEquals(pulled.stalkerPassword, input.stalkerPassword, "stalker password")
        assertEquals(pulled.serialNumber, input.serialNumber, "serial number")
        assertEquals(pulled.deviceId, input.deviceId, "device id")
        assertEquals(pulled.sendDeviceId, input.sendDeviceId, "send device id")
        assertEquals(pulled.deviceId2, input.deviceId2, "device id 2")
        assertEquals(pulled.signature, input.signature, "signature")
        assertEquals(pulled.stbModel, input.stbModel, "stb model")
        assertEquals(pulled.hwVersion, input.hwVersion, "hw version")
        assertEquals(pulled.sourceType, input.sourceType, "source type")
    }

    /** F46 on Apple TV: the four extra STB identity fields reach the shared input and pre-fill Edit. */
    @Test
    fun `stalker identity overrides reach the input and blank ones stay unset`() {
        val form = TvPlaylistFormPolicy.empty(SOURCE_TYPE_STALKER).copy(
            portalUrl = "http://portal", macAddress = "00:1A:79:AA:BB:CC",
            deviceId2 = " d2 ", signature = " sig ", stbModel = " MAG254 ", hwVersion = "",
        )
        val input = assertNotNull(TvPlaylistFormPolicy.toInput(form))
        assertEquals("d2", input.deviceId2)
        assertEquals("sig", input.signature)
        assertEquals("MAG254", input.stbModel)
        assertNull(input.hwVersion, "blank = keep the preset")
    }

    @Test
    fun `stalker identity overrides pre-fill edit`() {
        val stalker = XtreamAccount(
            id = "s", name = "Box", baseUrl = "http://p", username = "", password = "", sourceType = SOURCE_TYPE_STALKER,
            macAddress = "00:1A:79:01:02:03", deviceId2 = "d2", signature = "sig", stbModel = "MAG322", hwVersion = "2.6-IB-00",
        )
        val f = TvPlaylistFormPolicy.fromAccount(stalker)
        assertEquals(listOf("d2", "sig", "MAG322", "2.6-IB-00"), listOf(f.deviceId2, f.signature, f.stbModel, f.hwVersion))
    }

    @Test
    fun `a managed xtream edit keeps every provider field byte identical`() {
        val form = TvPlaylistFormPolicy.fromAccount(managedXtream).copy(name = "Renamed", autoRefreshHours = 6)
        val input = assertNotNull(TvPlaylistFormPolicy.toEditInput(form, managedXtream, managed = true))
        assertProviderFieldsUntouched(managedXtream, input)
        assertEquals("Renamed", input.name)
        assertEquals(6, input.autoRefreshHours, "a non-provider option still follows the form")
    }

    @Test
    fun `a managed m3u url edit keeps the playlist address byte identical`() {
        val pulled = XtreamAccount(
            id = "mm", name = "List", baseUrl = " HTTP://Lists.Example.COM:80/get.php?username=U&password=P ", username = "", password = "",
            sourceType = SOURCE_TYPE_M3U_URL, userAgent = " UA ", backupUrls = listOf("http://Mirror.Example.com:80/l.m3u/"),
        )
        val input = assertNotNull(TvPlaylistFormPolicy.toEditInput(TvPlaylistFormPolicy.fromAccount(pulled).copy(name = "Mine"), pulled, managed = true))
        assertProviderFieldsUntouched(pulled, input)
        assertEquals(pulled.baseUrl, input.m3uUrl, "the m3u address rides in both fields")
    }

    @Test
    fun `a managed stalker edit keeps portal mac and portal login byte identical`() {
        val pulled = XtreamAccount(
            id = "ms", name = "Box", baseUrl = "HTTP://Portal.Example.COM:80/", username = "", password = "",
            sourceType = SOURCE_TYPE_STALKER, macAddress = "00:1a:79:aa:bb:cc", stalkerUsername = " su ", stalkerPassword = " sp ",
            serialNumber = " SN ", deviceId = " dev ", sendDeviceId = false, backupUrls = listOf("http://P2.example.com:80/"),
            deviceId2 = " d2 ", signature = " sig ", stbModel = " MAG254 ", hwVersion = " 2.6 ",
        )
        val input = assertNotNull(TvPlaylistFormPolicy.toEditInput(TvPlaylistFormPolicy.fromAccount(pulled).copy(name = "Lounge"), pulled, managed = true))
        assertProviderFieldsUntouched(pulled, input)
    }

    @Test
    fun `a managed edit ignores form fields the screen should not have offered`() {
        // Even if a form somehow carried a changed server or login, a managed playlist's edit never reads them.
        val tampered = TvPlaylistFormPolicy.fromAccount(managedXtream).copy(
            server = "http://other.example.com", username = "mallory", password = "x", userAgent = "evil", epgUrl = "http://evil", backupUrls = emptyList(),
        )
        val input = assertNotNull(TvPlaylistFormPolicy.toEditInput(tampered, managedXtream, managed = true))
        assertProviderFieldsUntouched(managedXtream, input)
    }

    @Test
    fun `an unmanaged edit behaves exactly as before`() {
        val form = TvPlaylistFormPolicy.fromAccount(managedXtream).copy(name = "Mine")
        val expected = assertNotNull(TvPlaylistFormPolicy.toInput(form))
        assertEquals(expected, TvPlaylistFormPolicy.toEditInput(form, managedXtream, managed = false))
        // ...which is the normal trimming: nothing about it is byte-identical.
        assertEquals("Alice", expected.username)
    }

    @Test
    fun `a pasted link on an unmanaged edit still falls back to the url route`() {
        val form = TvPlaylistFormPolicy.empty().copy(pasteLink = true, playlistUrl = "http://host:8080/")
        assertNull(TvPlaylistFormPolicy.toEditInput(form, managedXtream, managed = false))
    }
}
