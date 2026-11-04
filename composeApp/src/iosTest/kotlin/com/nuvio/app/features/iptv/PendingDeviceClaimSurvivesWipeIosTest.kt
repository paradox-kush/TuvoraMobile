package com.nuvio.app.features.iptv

import com.nuvio.app.core.storage.PlatformLocalAccountDataCleaner
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** B205 on iOS: the guest-data wipe (every `_<profileId>` key and a fixed list) must leave the pending claim. */
class PendingDeviceClaimSurvivesWipeIosTest {
    private val claim = PendingDeviceClaim(
        token = "cd".repeat(32), providerName = "Acme TV", createdAtMs = 5L,
        playlists = listOf(DevicePlaylist("k1", "Live", "xtream", baseUrl = "http://p.example.com", username = "u", password = "p")),
    )

    @AfterTest
    fun cleanUp() {
        PendingDeviceClaimStorage.save(null)
        NSUserDefaults.standardUserDefaults.removeObjectForKey("xtream_accounts_1")
    }

    @Test
    fun theAccountWipeRemovesLocalPlaylistsButNotThePendingClaim() {
        PendingDeviceClaimStore.shared.put(claim)
        XtreamAccountStorage.saveAccountsJson(1, "[]")

        PlatformLocalAccountDataCleaner.wipe()

        assertEquals(null, XtreamAccountStorage.loadAccountsJson(1), "the guest's playlists are wiped, as before")
        assertEquals(listOf(claim), PendingDeviceClaimStore.shared.list())
    }
}
