package com.nuvio.app.features.iptv

import android.app.Application
import android.content.Context
import com.nuvio.app.core.storage.LocalAccountDataCleaner
import com.nuvio.app.core.storage.PlatformLocalAccountDataCleaner
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B205: a guest who signs in is wiped (AuthRepository.signOut -> LocalAccountDataCleaner.wipe), and that is exactly
 * when the pending claim is needed. The claim must not live in anything the cleaner clears.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PendingDeviceClaimSurvivesWipeTest {

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("tuvora_device_setup_claims", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("nuvio_iptv", Context.MODE_PRIVATE).edit().clear().commit()
        PendingDeviceClaimStorage.initialize(context)
        XtreamAccountStorage.initialize(context)
        PlatformLocalAccountDataCleaner.initialize(context)
    }

    private val claim = PendingDeviceClaim(
        token = "ab".repeat(32), providerName = "Acme TV", createdAtMs = 5L,
        playlists = listOf(DevicePlaylist("k1", "Live", "xtream", baseUrl = "http://p.example.com", username = "u", password = "p")),
    )

    @Test
    fun theAccountWipeClearsLocalPlaylistsButLeavesThePendingClaim() {
        PendingDeviceClaimStore.shared.put(claim)
        XtreamAccountStorage.saveAccountsJson(1, "[{\"id\":\"k1\"}]")

        // The same storage-wipe step LocalAccountDataCleaner.wipe() runs for a guest signing in.
        LocalAccountDataCleaner.wipePlatformStorage { PlatformLocalAccountDataCleaner.wipe() }

        assertEquals(null, XtreamAccountStorage.loadAccountsJson(1), "the guest's local playlists are wiped, as before")
        assertEquals(listOf(claim), PendingDeviceClaimStore.shared.list(), "the claim is what brings them back")
    }

    @Test
    fun thePlatformCleanerAloneLeavesItToo() {
        PendingDeviceClaimStore.shared.put(claim)
        PlatformLocalAccountDataCleaner.wipe()
        assertTrue(PendingDeviceClaimStore.shared.list().isNotEmpty())
    }
}
