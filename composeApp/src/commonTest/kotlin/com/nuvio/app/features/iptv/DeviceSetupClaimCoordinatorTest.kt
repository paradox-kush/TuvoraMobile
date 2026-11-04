package com.nuvio.app.features.iptv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What happens to a pending device claim around sign-in: the event, the call, the decision, no loop. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSetupClaimCoordinatorTest {

    private class Backing {
        var raw: String? = null
        val store = PendingDeviceClaimStore(load = { raw }, save = { raw = it })
    }

    private class FakeApi : ProviderSetupApi {
        val claimed = mutableListOf<Triple<String, Int, Boolean>>()
        var answer: (String) -> DeviceClaimResult = { DeviceClaimResult.Claimed(false) }
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun preview(code: String): SetupCodeOutcome = error("not used")
        override suspend fun redeem(code: String, profileIndex: Int, skipAddons: Boolean): RedeemResult = error("not used")
        override suspend fun deviceRedeem(code: String, claimToken: String): DeviceRedeemResult = error("not used")
        override suspend fun claimDeviceSetup(claimToken: String, profileIndex: Int, skipAddons: Boolean): DeviceClaimResult {
            claimed += Triple(claimToken, profileIndex, skipAddons)
            gate?.await()
            return answer(claimToken)
        }
        override suspend fun managedPlaylists(profileId: Int): List<ManagedInfo> = error("not used")
        override suspend fun detach(profileId: Int, playlistKey: String): Boolean = error("not used")
    }

    private val backing = Backing()
    private val api = FakeApi()
    private val ready = MutableStateFlow<String?>(null)
    private var profile = 2
    private var skipAddons = false
    private var local = setOf<String>()
    private val restored = mutableListOf<List<String>>()
    private var restoreResult = 1
    private val pulls = mutableListOf<Int>()
    private val outcomes = mutableListOf<String>()

    private fun playlist(key: String) = DevicePlaylist(
        playlistKey = key, name = key, sourceType = "xtream", baseUrl = "http://p.example.com", username = "u-$key", password = "p",
    )

    private fun pending(token: String, vararg keys: String) =
        PendingDeviceClaim(token, "Acme TV", 0L, keys.map(::playlist))

    private fun coordinator(scope: CoroutineScope) = DeviceSetupClaimCoordinator(
        claims = backing.store, api = { api }, realAccountReady = ready, activeProfileIndex = { profile },
        skipAddons = { skipAddons }, localAccountIds = { local },
        addLocalPlaylists = { accounts -> restored += accounts.map { it.id }; restoreResult },
        pullPlaylists = { pulls += it }, telemetry = { outcomes += it }, scope = scope,
    )

    @Test
    fun `after a real sign-in the claim is made for the active profile then dropped and one pull runs`() = runTest {
        backing.store.put(pending("t1", "k1"))
        val c = coordinator(TestScope(StandardTestDispatcher(testScheduler)))
        c.start()
        advanceUntilIdle()
        assertTrue(api.claimed.isEmpty(), "signed out: nothing to claim into")
        ready.value = "user-a"
        advanceUntilIdle()
        assertEquals(listOf(Triple("t1", 2, false)), api.claimed)
        assertEquals(emptyList(), backing.store.list(), "claimed: the claim is dropped")
        assertEquals(listOf(2), pulls)
        assertEquals(emptyList(), restored)
        assertEquals(listOf("drop_and_pull"), outcomes)
    }

    @Test
    fun `already signed in at app start the pending claim is made once`() = runTest {
        backing.store.put(pending("t1", "k1"))
        ready.value = "user-a"
        val c = coordinator(TestScope(StandardTestDispatcher(testScheduler)))
        c.start()
        c.start()
        advanceUntilIdle()
        assertEquals(1, api.claimed.size)
    }

    @Test
    fun `store builds ask the server to skip add-ons`() = runTest {
        skipAddons = true
        backing.store.put(pending("t1", "k1"))
        ready.value = "user-a"
        coordinator(TestScope(StandardTestDispatcher(testScheduler))).start()
        advanceUntilIdle()
        assertEquals(true, api.claimed.single().third)
    }

    @Test
    fun `nothing pending makes no request at all`() = runTest {
        ready.value = "user-a"
        coordinator(TestScope(StandardTestDispatcher(testScheduler))).start()
        advanceUntilIdle()
        assertEquals(0, api.claimed.size)
        assertEquals(emptyList(), pulls)
    }

    @Test
    fun `an unclaimable setup restores only the missing playlists and drops the claim`() = runTest {
        backing.store.put(pending("t1", "k1", "k2"))
        local = setOf("k1")                       // k2 was wiped with the guest data on sign-in
        api.answer = { DeviceClaimResult.Failed("unavailable") }
        ready.value = "user-a"
        coordinator(TestScope(StandardTestDispatcher(testScheduler))).start()
        advanceUntilIdle()
        assertEquals(listOf(listOf("k2")), restored, "matched by playlist key: k1 is not duplicated")
        assertEquals(emptyList(), backing.store.list())
        assertEquals(emptyList(), pulls, "no managed rows to pull")
        assertEquals(listOf("restore_local_and_drop"), outcomes)
    }

    @Test
    fun `an unclaimable setup whose playlists are all still here adds nothing`() = runTest {
        backing.store.put(pending("t1", "k1"))
        local = setOf("k1")
        api.answer = { DeviceClaimResult.Failed("revoked") }
        ready.value = "user-a"
        coordinator(TestScope(StandardTestDispatcher(testScheduler))).start()
        advanceUntilIdle()
        assertEquals(emptyList(), restored)
        assertEquals(emptyList(), backing.store.list())
    }

    @Test
    fun `a failed local write keeps the claim so the logins are not lost`() = runTest {
        backing.store.put(pending("t1", "k1"))
        restoreResult = -1
        api.answer = { DeviceClaimResult.Failed("not_found") }
        ready.value = "user-a"
        coordinator(TestScope(StandardTestDispatcher(testScheduler))).start()
        advanceUntilIdle()
        assertEquals(1, backing.store.list().size)
    }

    @Test
    fun `network and rate limit keep the claim and are retried only by the next sign-in event`() = runTest {
        backing.store.put(pending("t1", "k1"))
        api.answer = { DeviceClaimResult.Failed(null) }
        ready.value = "user-a"
        coordinator(TestScope(StandardTestDispatcher(testScheduler))).start()
        advanceUntilIdle()
        assertEquals(1, api.claimed.size)
        assertEquals(1, backing.store.list().size, "kept for the next event")
        testScheduler.advanceTimeBy(24 * 60 * 60 * 1000L)
        advanceUntilIdle()
        assertEquals(1, api.claimed.size, "no timer: a day later it has not asked again")
        // Sign out and back in: a new event.
        ready.value = null
        advanceUntilIdle()
        api.answer = { DeviceClaimResult.Claimed(false) }
        ready.value = "user-a"
        advanceUntilIdle()
        assertEquals(2, api.claimed.size)
        assertEquals(emptyList(), backing.store.list())
        assertEquals(listOf(2), pulls)
    }

    @Test
    fun `a profile that is not there yet keeps the claim`() = runTest {
        backing.store.put(pending("t1", "k1"))
        api.answer = { DeviceClaimResult.Failed("profile_not_found") }
        ready.value = "user-a"
        coordinator(TestScope(StandardTestDispatcher(testScheduler))).start()
        advanceUntilIdle()
        assertEquals(1, backing.store.list().size)
        assertEquals(emptyList(), pulls)
    }

    @Test
    fun `two claims make two requests and exactly one pull`() = runTest {
        backing.store.put(pending("t1", "k1"))
        backing.store.put(pending("t2", "k2"))
        ready.value = "user-a"
        coordinator(TestScope(StandardTestDispatcher(testScheduler))).start()
        advanceUntilIdle()
        assertEquals(listOf("t1", "t2"), api.claimed.map { it.first })
        assertEquals(listOf(2), pulls)
    }

    @Test
    fun `overlapping events run one pass at a time`() = runTest {
        backing.store.put(pending("t1", "k1"))
        api.gate = CompletableDeferred()
        ready.value = "user-a"
        val c = coordinator(TestScope(StandardTestDispatcher(testScheduler)))
        c.start()
        advanceUntilIdle()
        assertEquals(1, api.claimed.size, "the first pass is waiting on the server")
        val second = launch { c.runPending() }
        advanceUntilIdle()
        second.join()
        assertEquals(1, api.claimed.size, "single flight: the overlapping pass did not ask again")
        api.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(emptyList(), backing.store.list())
        assertEquals(listOf(2), pulls)
    }

    @Test
    fun `a pull that throws does not undo the claim`() = runTest {
        backing.store.put(pending("t1", "k1"))
        ready.value = "user-a"
        val c = DeviceSetupClaimCoordinator(
            claims = backing.store, api = { api }, realAccountReady = ready, activeProfileIndex = { 2 }, skipAddons = { false },
            localAccountIds = { emptySet() }, addLocalPlaylists = { 0 }, pullPlaylists = { error("offline") },
            telemetry = {}, scope = TestScope(StandardTestDispatcher(testScheduler)),
        )
        c.start()
        advanceUntilIdle()
        assertEquals(emptyList(), backing.store.list())
    }
}
