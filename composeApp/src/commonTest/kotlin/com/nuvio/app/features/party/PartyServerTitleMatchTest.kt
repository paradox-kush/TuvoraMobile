package com.nuvio.app.features.party

import com.nuvio.app.features.servers.ServerConnection
import com.nuvio.app.features.servers.ServerItemRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PartyServerTitleMatchTest {
    private fun server(id: String, provider: String = "jellyfin", remote: String = "fe89machine", enabled: Boolean = true) = ServerConnection(
        id = id, providerId = provider, name = "Home", address = "http://192.168.1.5:8096", remoteServerId = remote,
        remoteUserId = "u-$id", userName = "user", credentialRef = "c-$id", enabled = enabled,
    )

    // The host's device stores the Jellyfin as record cc3e...; the guest's device stores the SAME Jellyfin as 77aa...
    private val hostServers = listOf(server("cc3efd306b9f8512"))
    private val guestServers = listOf(server("77aa00000000beef"))
    private val itemId = "fe8927be7a9ffe98ee7a8250b07f3664"

    private fun hostMovie() = PartyMedia(
        type = "movie", metaId = ServerItemRef("cc3efd306b9f8512", itemId).encode(), metaType = "movie", title = "Film",
        source = PartySource(addonId = "server:cc3efd306b9f8512", name = "1080p"),
    )

    @Test
    fun `the host's media carries the server's stable identity`() {
        val shared = PartyServerTitleMatch.withServer(hostMovie(), hostServers)
        assertEquals(PartyServerRef(provider = "jellyfin", remoteServerId = "fe89machine", itemId = itemId), shared.server)
        assertEquals(hostMovie().metaId, shared.metaId, "old clients still see the host's own id")
    }

    @Test
    fun `same server on another account resolves to the guest's own record`() {
        val shared = PartyServerTitleMatch.withServer(hostMovie(), hostServers)
        val local = assertIs<PartyServerResolution.Local>(PartyServerTitleMatch.localize(shared, guestServers)).media
        assertEquals(ServerItemRef("77aa00000000beef", itemId).encode(), local.metaId)
        assertEquals("server:77aa00000000beef", local.source?.addonId)
    }

    @Test
    fun `host and guest compute the same party key for the same server title`() {
        val shared = PartyServerTitleMatch.withServer(hostMovie(), hostServers)
        val hostKey = partyMediaKey("movie", PartyServerTitleMatch.portableMetaId(hostMovie().metaId, hostServers)!!, null, null)
        val guestLocal = ServerItemRef("77aa00000000beef", itemId).encode()
        val guestKey = partyMediaKey("movie", PartyServerTitleMatch.portableMetaId(guestLocal, guestServers)!!, null, null)
        assertEquals(hostKey, guestKey)
        assertEquals(hostKey, shared.key)
    }

    @Test
    fun `a guest not connected to that server is told so`() {
        val shared = PartyServerTitleMatch.withServer(hostMovie(), hostServers)
        assertEquals(PartyServerResolution.NotConnected, PartyServerTitleMatch.localize(shared, emptyList()))
        assertEquals(PartyServerResolution.NotConnected, PartyServerTitleMatch.localize(shared, listOf(server("x", remote = "another"))))
        assertEquals(PartyServerResolution.NotConnected, PartyServerTitleMatch.localize(shared, listOf(server("y", enabled = false))))
    }

    @Test
    fun `an Emby server with the same machine id as a Jellyfin one is a different server`() {
        val emby = listOf(server("e1", provider = "emby", remote = "embymachine"))
        val embyMovie = PartyMedia(type = "movie", metaId = ServerItemRef("e1", "42").encode(), metaType = "movie")
        val shared = PartyServerTitleMatch.withServer(embyMovie, emby)
        assertEquals("emby", shared.server?.provider)
        val guest = listOf(server("j1", provider = "jellyfin", remote = "embymachine"), server("e9", provider = "emby", remote = "EMBYMACHINE"))
        val local = assertIs<PartyServerResolution.Local>(PartyServerTitleMatch.localize(shared, guest)).media
        assertEquals(ServerItemRef("e9", "42").encode(), local.metaId)
    }

    @Test
    fun `episodes map the episode item too`() {
        val episode = PartyMedia(
            type = "series", metaId = ServerItemRef("cc3efd306b9f8512", "series1").encode(), metaType = "series",
            videoId = ServerItemRef("cc3efd306b9f8512", "ep7").encode(), season = 1, episode = 7,
        )
        val shared = PartyServerTitleMatch.withServer(episode, hostServers)
        assertEquals("ep7", shared.server?.videoItemId)
        val local = assertIs<PartyServerResolution.Local>(PartyServerTitleMatch.localize(shared, guestServers)).media
        assertEquals(ServerItemRef("77aa00000000beef", "ep7").encode(), local.videoId)
    }

    @Test
    fun `a stale record id in meta_id loses to the server identity`() {
        // The guest happens to have a record with the host's id, but for a different server.
        val shared = PartyServerTitleMatch.withServer(hostMovie(), hostServers)
        val guest = listOf(server("cc3efd306b9f8512", remote = "otherbox"), server("good", remote = "fe89machine"))
        val local = assertIs<PartyServerResolution.Local>(PartyServerTitleMatch.localize(shared, guest)).media
        assertEquals(ServerItemRef("good", itemId).encode(), local.metaId)
    }

    @Test
    fun `an old host without the identity still works on its own account and otherwise says not connected`() {
        assertIs<PartyServerResolution.Local>(PartyServerTitleMatch.localize(hostMovie(), hostServers))
        assertEquals(PartyServerResolution.NotConnected, PartyServerTitleMatch.localize(hostMovie(), guestServers))
    }

    @Test
    fun `catalog titles and IPTV are untouched`() {
        val catalog = PartyMedia(type = "movie", metaId = "tt0111161", metaType = "movie")
        assertEquals(PartyServerResolution.NotServer, PartyServerTitleMatch.localize(catalog, guestServers))
        assertNull(PartyServerTitleMatch.withServer(catalog, hostServers).server)
        assertNull(PartyServerTitleMatch.portableMetaId("tt0111161", hostServers))
        assertNull(PartyServerTitleMatch.refOf(ServerItemRef("unknown", "1").encode(), null, hostServers), "unknown record")
    }
}
