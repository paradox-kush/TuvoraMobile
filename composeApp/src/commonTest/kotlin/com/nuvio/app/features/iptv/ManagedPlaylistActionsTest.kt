package com.nuvio.app.features.iptv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Detach: the server call, then the managed map refreshed and ONE playlist pull — in that order. */
class ManagedPlaylistActionsTest {
    private val log = mutableListOf<String>()
    private var detachResult: () -> Boolean = { true }
    private val events = mutableListOf<String>()

    private val api = object : ProviderSetupApi {
        override suspend fun preview(code: String): SetupCodeOutcome = error("not used")
        override suspend fun redeem(code: String, profileIndex: Int, skipAddons: Boolean): RedeemResult = error("not used")
        override suspend fun deviceRedeem(code: String, claimToken: String): DeviceRedeemResult = error("not used")
        override suspend fun claimDeviceSetup(claimToken: String, profileIndex: Int, skipAddons: Boolean): DeviceClaimResult = error("not used")
        override suspend fun managedPlaylists(profileId: Int): List<ManagedInfo> = error("not used")
        override suspend fun detach(profileId: Int, playlistKey: String): Boolean {
            log += "detach($profileId,$playlistKey)"
            return detachResult()
        }
    }

    private val actions = ManagedPlaylistActions(
        api = { api }, activeProfile = { 2 },
        pull = { log += "pull($it)" }, refresh = { log += "refresh($it)"; true },
        telemetry = ProviderSetupTelemetry,
    )

    @BeforeTest
    fun setUp() {
        ProviderSetupTelemetry.capture = { name, _ -> events += name }
    }

    @AfterTest
    fun tearDown() {
        ProviderSetupTelemetry.capture = com.nuvio.app.core.analytics.AnalyticsSink::capture
    }

    @Test
    fun `detach asks the server then refreshes the map then pulls once`() = runBlocking {
        assertTrue(actions.detach("key-1"))
        assertEquals(listOf("detach(2,key-1)", "refresh(2)", "pull(2)"), log)
        assertEquals(listOf("playlist_detached"), events)
    }

    @Test
    fun `a playlist that was already detached elsewhere still ends managed`() = runBlocking {
        detachResult = { false }
        assertTrue(actions.detach("key-1"))
        assertEquals(listOf("detach(2,key-1)", "refresh(2)", "pull(2)"), log)
    }

    @Test
    fun `a failed detach changes nothing and reports it`() = runBlocking {
        detachResult = { throw RuntimeException("offline") }
        assertFalse(actions.detach("key-1"))
        assertEquals(listOf("detach(2,key-1)"), log, "no refresh, no pull, nothing logged for the provider")
        assertEquals(emptyList(), events)
    }

    // ---- code review L8: leaving the details page mid-detach must not cancel the refresh + pull ---------

    @Test
    fun `a detach started from a page finishes even when that page is gone`() {
        val gate = CompletableDeferred<Unit>()
        val own = ManagedPlaylistActions(
            api = { api }, activeProfile = { 2 },
            pull = { gate.await(); log += "pull($it)" }, refresh = { log += "refresh($it)"; true },
            telemetry = ProviderSetupTelemetry, scope = CoroutineScope(Dispatchers.Unconfined),
        )
        val page = Job()
        var result: Boolean? = null
        CoroutineScope(Dispatchers.Unconfined + page).launch { own.detachInBackground("key-1") { result = it } }
        assertEquals(listOf("detach(2,key-1)", "refresh(2)"), log, "the RPC is out and the pull is waiting")
        page.cancel()                                   // the person left the page
        gate.complete(Unit)
        assertEquals(listOf("detach(2,key-1)", "refresh(2)", "pull(2)"), log, "the pull still ran")
        assertEquals(true, result)
    }
}
