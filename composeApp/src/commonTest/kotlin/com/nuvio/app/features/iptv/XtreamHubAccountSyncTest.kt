package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The hub keeps its own copy of the enabled playlists. [hubNeedsAccountSync] decides when that copy
 * is stale — a playlist added in Settings while the IPTV tab stayed composed used to leave the hub
 * on "No IPTV provider yet" until an app restart.
 */
class XtreamHubAccountSyncTest {

    private fun account(id: String, enabled: Boolean = true) = XtreamAccount(
        id = id,
        name = "Panel $id",
        baseUrl = "http://$id.example:8080",
        username = "u",
        password = "p",
        enabled = enabled,
    )

    @Test
    fun `a playlist added after the hub loaded empty needs a sync`() {
        val hub = XtreamHubUiState(accounts = emptyList(), accountsLoaded = true)

        assertTrue(hubNeedsAccountSync(hub, listOf(account("a"))), "the new playlist must reach the hub")
    }

    @Test
    fun `a removed playlist needs a sync`() {
        val hub = XtreamHubUiState(accounts = listOf(account("a"), account("b")), accountsLoaded = true)

        assertTrue(hubNeedsAccountSync(hub, listOf(account("a"))), "the removed playlist must leave the hub")
    }

    @Test
    fun `an edited playlist needs a sync`() {
        val hub = XtreamHubUiState(accounts = listOf(account("a")), accountsLoaded = true)

        assertTrue(
            hubNeedsAccountSync(hub, listOf(account("a").copy(name = "Renamed"))),
            "a rename must reach the provider picker",
        )
    }

    @Test
    fun `a hub that was reset or never loaded needs a sync`() {
        // A removal wipes the hub state (resetForProfile) while the tab may still be composed.
        val reset = XtreamHubUiState()

        assertTrue(hubNeedsAccountSync(reset, emptyList()), "a reset hub must reload even with no playlists")
    }

    @Test
    fun `an unchanged list does not re-drive the hub`() {
        val hub = XtreamHubUiState(accounts = listOf(account("a")), accountsLoaded = true)

        assertFalse(hubNeedsAccountSync(hub, listOf(account("a"))), "a repeat emission must be a no-op")
    }

    @Test
    fun `a disabled playlist does not count`() {
        // The hub only lists enabled playlists; toggling one that is already hidden changes nothing.
        val hub = XtreamHubUiState(accounts = listOf(account("a")), accountsLoaded = true)

        assertFalse(
            hubNeedsAccountSync(hub, listOf(account("a"), account("off", enabled = false))),
            "a disabled playlist is not shown, so it must not force a sync",
        )
    }
    @Test
    fun `adding or removing the last disabled playlist updates the empty state`() {
        val empty = XtreamHubUiState(accountsLoaded = true)
        val disabled = listOf(account("off", enabled = false))
        assertTrue(hubNeedsAccountSync(empty, disabled))
        val savedOff = empty.copy(hasConfiguredAccounts = true)
        assertFalse(hubNeedsAccountSync(savedOff, disabled))
        assertTrue(hubNeedsAccountSync(savedOff, emptyList()))
    }
}
