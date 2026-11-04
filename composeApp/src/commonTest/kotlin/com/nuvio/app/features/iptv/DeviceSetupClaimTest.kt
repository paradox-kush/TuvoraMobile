package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** B205 pure parts: the claim policy, the device-playlist mapper, the token and the pending-claim store. */
class DeviceSetupClaimTest {

    // ---- DeviceClaimPolicy ---------------------------------------------------------------------------

    @Test
    fun `a claimed setup is dropped and pulled`() {
        assertEquals(ClaimDecision.DROP_AND_PULL, DeviceClaimPolicy.decide(DeviceClaimResult.Claimed(alreadyClaimed = false)))
        assertEquals(ClaimDecision.DROP_AND_PULL, DeviceClaimPolicy.decide(DeviceClaimResult.Claimed(alreadyClaimed = true)))
    }

    @Test
    fun `a setup that can never be claimed restores the playlists locally and drops the claim`() {
        for (code in listOf("not_found", "already_used", "revoked", "suspended", "unavailable")) {
            assertEquals(ClaimDecision.RESTORE_LOCAL_AND_DROP, DeviceClaimPolicy.decide(DeviceClaimResult.Failed(code)), code)
        }
    }

    @Test
    fun `anything undecided keeps the claim for the next sign-in or start`() {
        for (code in listOf("rate_limited", "profile_not_found", null, "not_authenticated", "anonymous_not_allowed", "something_new")) {
            assertEquals(ClaimDecision.KEEP_FOR_RETRY, DeviceClaimPolicy.decide(DeviceClaimResult.Failed(code)), "$code")
        }
    }

    // ---- DevicePlaylistMapper ------------------------------------------------------------------------

    private fun xtream(key: String = "k-x") = DevicePlaylist(
        playlistKey = key, name = "Acme Live", sourceType = "xtream", baseUrl = "http://p.example.com:8080",
        username = "u1", password = "p1", epgUrl = "http://p.example.com/epg.xml", userAgent = "AcmeUA",
        backupUrls = listOf("http://b.example.com"), contentTypes = listOf("live"),
    )

    @Test
    fun `an xtream playlist maps like a pulled row`() {
        val account = DevicePlaylistMapper.toAccount(xtream())!!
        assertEquals("k-x", account.id, "the id is the server's playlist_key")
        assertEquals("Acme Live", account.name)
        assertEquals("xtream", account.sourceType)
        assertEquals("http://p.example.com:8080", account.baseUrl)
        assertEquals("u1", account.username)
        assertEquals("p1", account.password)
        assertEquals("http://p.example.com/epg.xml", account.epgUrl)
        assertEquals("AcmeUA", account.userAgent)
        assertEquals(listOf("http://b.example.com"), account.backupUrls)
        assertEquals(setOf("live"), account.contentTypes)
        assertTrue(account.enabled)
        // The same function a pull uses gives the same account for the same columns.
        val viaRow = PlaylistRow(
            playlistKey = "k-x", backupUrls = listOf("http://b.example.com"), name = "Acme Live", baseUrl = "http://p.example.com:8080",
            username = "u1", password = "p1", epgUrl = "http://p.example.com/epg.xml", autoRefreshHours = account.autoRefreshHours,
            contentTypes = listOf("live"), userAgent = "AcmeUA",
        ).toAccount()
        assertEquals(viaRow, account)
    }

    @Test
    fun `an m3u playlist maps its url and a stalker playlist its portal and mac`() {
        val m3u = DevicePlaylistMapper.toAccount(DevicePlaylist(playlistKey = "k-m", name = "List", sourceType = "m3u_url", url = "http://m.example.com/list.m3u"))!!
        assertEquals("k-m", m3u.id)
        assertEquals("m3u_url", m3u.sourceType)
        assertEquals("http://m.example.com/list.m3u", m3u.baseUrl)
        val stalker = DevicePlaylistMapper.toAccount(
            DevicePlaylist(playlistKey = "k-s", name = "Portal", sourceType = "stalker", portalUrl = "http://s.example.com/c", macAddress = "00:1A:79:AA:BB:CC", stalkerUsername = "su", stalkerPassword = "sp"),
        )!!
        assertEquals("k-s", stalker.id)
        assertEquals("http://s.example.com/c", stalker.baseUrl)
        assertEquals("00:1A:79:AA:BB:CC", stalker.macAddress)
        assertEquals("su", stalker.stalkerUsername)
        assertEquals("sp", stalker.stalkerPassword)
    }

    @Test
    fun `blank optional fields become absent and empty content types mean all`() {
        val account = DevicePlaylistMapper.toAccount(xtream().copy(epgUrl = " ", userAgent = "", contentTypes = emptyList(), backupUrls = listOf("", " ")))!!
        assertNull(account.epgUrl)
        assertNull(account.userAgent)
        assertEquals(ALL_CONTENT_TYPES, account.contentTypes)
        assertEquals(emptyList(), account.backupUrls)
    }

    @Test
    fun `an incomplete or skipped playlist is never added`() {
        assertNull(DevicePlaylistMapper.toAccount(xtream().copy(username = null)), "xtream needs a login")
        assertNull(DevicePlaylistMapper.toAccount(xtream().copy(playlistKey = " ")))
        assertNull(DevicePlaylistMapper.toAccount(xtream().copy(sourceType = "from_the_future")))
        val skipped = xtream("k-skip").copy(action = "skipped", reason = "missing_login", username = null)
        assertEquals(listOf("k-x"), DevicePlaylistMapper.accountsToAdd(listOf(xtream(), skipped)).map { it.id })
    }

    @Test
    fun `restoring matches by playlist key so nothing is duplicated`() {
        val a = xtream("k-a")
        val b = xtream("k-b")
        assertEquals(listOf("k-b"), DevicePlaylistMapper.missing(listOf(a, b), localIds = setOf("k-a")).map { it.id })
        assertEquals(emptyList(), DevicePlaylistMapper.missing(listOf(a, b), localIds = setOf("k-a", "k-b")))
        assertEquals(1, DevicePlaylistMapper.missing(listOf(b, b), localIds = emptySet()).size, "twice in the claim, once locally")
    }

    @Test
    fun `a device playlist never prints its login`() {
        val text = xtream().toString() + PendingDeviceClaim("tok", "Acme", 0, listOf(xtream())).toString()
        assertFalse(text.contains("u1") || text.contains("p1") || text.contains("tok"), text)
    }

    // ---- the claim token -----------------------------------------------------------------------------

    @Test
    fun `a token is 64 lowercase hex characters and never repeats`() {
        val tokens = (1..20).map { DeviceClaimToken.newToken() }
        for (t in tokens) {
            assertEquals(64, t.length)
            assertTrue(t.all { it in '0'..'9' || it in 'a'..'f' }, t)
        }
        assertEquals(20, tokens.toSet().size)
    }

    @Test
    fun `the claim hash is the sha-256 hex of the token text`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", DeviceClaimToken.hash("abc"))
        val token = DeviceClaimToken.newToken()
        assertEquals(64, DeviceClaimToken.hash(token).length)
        assertNotEquals(token, DeviceClaimToken.hash(token))
    }

    // ---- the pending-claim store ---------------------------------------------------------------------

    private class MemoryBacking {
        var raw: String? = null
        val store = PendingDeviceClaimStore(load = { raw }, save = { raw = it })
    }

    private fun claim(token: String) = PendingDeviceClaim(token, "Acme TV", 1_000L, listOf(xtream()))

    @Test
    fun `claims round trip with their playlists`() {
        val m = MemoryBacking()
        assertEquals(emptyList(), m.store.list())
        m.store.put(claim("t1"))
        assertEquals(listOf(claim("t1")), m.store.list())
    }

    @Test
    fun `putting the same token again replaces it and removing the last claim empties the store`() {
        val m = MemoryBacking()
        m.store.put(claim("t1"))
        m.store.put(claim("t1").copy(providerName = "Renamed"))
        m.store.put(claim("t2"))
        assertEquals(listOf("Renamed", "Acme TV"), m.store.list().map { it.providerName })
        m.store.remove("t1")
        assertEquals(listOf("t2"), m.store.list().map { it.token })
        m.store.remove("t2")
        assertNull(m.raw, "an empty store holds no credentials")
    }

    @Test
    fun `an unreadable blob reads as no claims and never throws`() {
        val m = MemoryBacking()
        m.raw = "{not json"
        assertEquals(emptyList(), m.store.list())
    }
}
