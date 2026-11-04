package com.nuvio.app.features.iptv

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The network rule for the one read this feature adds to a playlist pull (contract section 5). */
class ManagedInfoRefresherTest {

    private class FakeApi : ProviderSetupApi {
        var calls = 0
        var result: () -> List<ManagedInfo> = { emptyList() }
        override suspend fun preview(code: String): SetupCodeOutcome = error("not used")
        override suspend fun redeem(code: String, profileIndex: Int, skipAddons: Boolean): RedeemResult = error("not used")
        override suspend fun deviceRedeem(code: String, claimToken: String): DeviceRedeemResult = error("not used")
        override suspend fun claimDeviceSetup(claimToken: String, profileIndex: Int, skipAddons: Boolean): DeviceClaimResult = error("not used")
        override suspend fun managedPlaylists(profileId: Int): List<ManagedInfo> {
            calls++
            return result()
        }
        override suspend fun detach(profileId: Int, playlistKey: String): Boolean = error("not used")
    }

    private val account = XtreamAccount(id = "k1", name = "A", baseUrl = "http://a.example.com", username = "u", password = "p")
    private val api = FakeApi()
    private var accounts: List<XtreamAccount>? = listOf(account)
    private var revision = 5L
    private var signedIn = true
    private var repoReady = true
    private var userId: String? = "user-a"

    @BeforeTest
    fun setUp() {
        ManagedInfoRepository.store = InMemoryManagedInfoStore()
        ManagedInfoRefresher.api = api
        ManagedInfoRefresher.isSignedIn = { signedIn }
        ManagedInfoRefresher.accountsFor = { accounts }
        ManagedInfoRefresher.revisionFor = { revision }
        ManagedInfoRefresher.loadRepository = { }
        ManagedInfoRefresher.repositoryReady = { repoReady }
        ManagedInfoRepository.userId = { userId }
    }

    @AfterTest
    fun tearDown() {
        ManagedInfoRepository.resetForTest()
        ManagedInfoRefresher.resetForTest()
    }

    private fun pull() = runBlocking { ManagedInfoRefresher.afterPlaylistPull(1) }

    @Test
    fun `a profile with no playlists makes no request and clears the map`() {
        ManagedInfoRepository.installForTest(1, mapOf("old" to ManagedInfo("old", "Gone")))
        accounts = emptyList()
        pull()
        assertEquals(0, api.calls)
        assertEquals(emptyMap(), ManagedInfoRepository.forProfile(1))
    }

    @Test
    fun `a pull that returned playlists refreshes once and keys the map by playlist key`() {
        api.result = { listOf(ManagedInfo("k1", "Acme")) }
        pull()
        assertEquals(1, api.calls)
        assertTrue(ManagedInfoRepository.isManaged(1, "k1"))
        assertEquals("Acme", ManagedInfoRepository.infoFor(1, "k1")?.providerName)
    }

    @Test
    fun `a second pull at the same revision asks nothing`() {
        api.result = { listOf(ManagedInfo("k1", "Acme")) }
        pull(); pull(); pull()
        assertEquals(1, api.calls, "delta-shaped: nothing changed, no request")
    }

    @Test
    fun `a moved revision refreshes again`() {
        api.result = { listOf(ManagedInfo("k1", "Acme")) }
        pull()
        revision = 6
        api.result = { emptyList() }   // detached elsewhere
        pull()
        assertEquals(2, api.calls)
        assertFalse(ManagedInfoRepository.isManaged(1, "k1"))
    }

    @Test
    fun `a failed refresh keeps the cache and tries again at the next pull`() {
        ManagedInfoRepository.installForTest(1, mapOf("k1" to ManagedInfo("k1", "Acme")))
        api.result = { throw RuntimeException("offline") }
        pull()
        assertTrue(ManagedInfoRepository.isManaged(1, "k1"), "the cache survives a failed refresh")
        api.result = { listOf(ManagedInfo("k1", "Acme")) }
        pull()
        assertEquals(2, api.calls, "a failure does not mark the revision as refreshed")
    }

    @Test
    fun `nothing is asked while signed out or when the profile is not the one in memory`() {
        signedIn = false
        pull()
        signedIn = true
        accounts = null
        pull()
        assertEquals(0, api.calls)
    }

    @Test
    fun `an explicit refresh after redeem or detach always asks`() {
        api.result = { listOf(ManagedInfo("k1", "Acme")) }
        runBlocking { assertTrue(ManagedInfoRefresher.refresh(1)); assertTrue(ManagedInfoRefresher.refresh(1)) }
        assertEquals(2, api.calls)
    }

    @Test
    fun `the managed map is cached across a restart`() {
        val store = InMemoryManagedInfoStore()
        ManagedInfoRepository.store = store
        ManagedInfoRepository.replace(1, mapOf("k1" to ManagedInfo("k1", "Acme", support = ProviderSupport(telegram = "acme_tv"))))
        ManagedInfoRepository.clearLocalState()   // a fresh process holds nothing in memory
        assertTrue(ManagedInfoRepository.isManaged(1, "k1"), "an offline cold start still knows the playlist is managed")
        assertEquals("acme_tv", ManagedInfoRepository.infoFor(1, "k1")?.support?.telegram)
    }

    @Test
    fun `the refresh policy table`() {
        assertFalse(ManagedInfoRefreshPolicy.shouldRefresh(0, 9, null), "no playlists")
        assertTrue(ManagedInfoRefreshPolicy.shouldRefresh(1, 9, null), "first pull of a launch")
        assertFalse(ManagedInfoRefreshPolicy.shouldRefresh(3, 9, 9), "same revision")
        assertTrue(ManagedInfoRefreshPolicy.shouldRefresh(3, 10, 9), "moved revision")
    }

    // ---- code review M1: an unloaded or damaged repository never wipes the offline-cold-start cache ----

    @Test
    fun `an unloaded repository does not wipe the cached map`() {
        ManagedInfoRepository.replace(1, mapOf("k1" to ManagedInfo("k1", "Acme")))
        accounts = emptyList()      // what an unloaded repository reports
        repoReady = false
        pull()
        assertTrue(ManagedInfoRepository.isManaged(1, "k1"), "kept: nothing proves the profile has no playlists")
        assertEquals(0, api.calls)
    }

    @Test
    fun `the repository is loaded before its accounts are read`() {
        var loads = 0
        ManagedInfoRefresher.loadRepository = { loads++ }
        pull()
        assertEquals(1, loads)
    }

    // ---- security L8: a late answer after sign-out or a user switch writes nothing ----------------------

    @Test
    fun `a refresh that finishes after sign-out does not write the old account's providers`() {
        api.result = { signedIn = false; listOf(ManagedInfo("k1", "Acme")) }
        pull()
        assertFalse(ManagedInfoRepository.isManaged(1, "k1"), "the session ended during the call")
    }

    @Test
    fun `a refresh that finishes for another user does not write either`() {
        api.result = { userId = "user-b"; listOf(ManagedInfo("k1", "Acme")) }
        pull()
        assertFalse(ManagedInfoRepository.isManaged(1, "k1"))
    }

    @Test
    fun `the cached map belongs to the user who fetched it`() {
        val store = InMemoryManagedInfoStore()
        ManagedInfoRepository.store = store
        ManagedInfoRepository.replace(1, mapOf("k1" to ManagedInfo("k1", "Acme")))
        ManagedInfoRepository.clearLocalState()
        assertTrue(ManagedInfoRepository.isManaged(1, "k1"), "same user: still known after a restart")
        ManagedInfoRepository.clearLocalState()
        userId = "user-b"
        assertFalse(ManagedInfoRepository.isManaged(1, "k1"), "another account never inherits the previous account's providers")
        assertEquals(emptyMap(), ManagedInfoRepository.forProfile(1))
    }

    @Test
    fun `with nobody signed in nothing is read or written`() {
        userId = null
        ManagedInfoRepository.replace(1, mapOf("k1" to ManagedInfo("k1", "Acme")))
        ManagedInfoRepository.clearLocalState()
        assertEquals(emptyMap(), ManagedInfoRepository.forProfile(1))
    }
}
