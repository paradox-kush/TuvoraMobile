package com.nuvio.app.features.servers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ServerProfileDeviceIdTest {
    @Test
    fun primaryProfileKeepsTheInstallationId() {
        assertEquals("device-1", profileScopedDeviceId("device-1", 1))
    }

    @Test
    fun secondaryProfilesGetTheirOwnDeviceId() {
        assertEquals("device-1-p2", profileScopedDeviceId("device-1", 2))
        assertEquals("device-1-p3", profileScopedDeviceId("device-1", 3))
        assertNotEquals(profileScopedDeviceId("device-1", 2), profileScopedDeviceId("device-1", 3))
    }

    @Test
    fun forkStoredServerIdIsScopedPerProfile() {
        // The fork keeps its own stored server device id (Tuvora prefix, or the shared id a device already signed in
        // with); upstream's per-profile scoping applies on top of it, and the primary profile keeps it unchanged.
        val stored = ServerDeviceIdPolicy.choose(stored = null, sharedClientId = "nuvio-mobile-abc", hasSignedInServers = false)
        assertEquals("tuvora-mobile-abc", profileScopedDeviceId(stored, 1))
        assertEquals("tuvora-mobile-abc-p2", profileScopedDeviceId(stored, 2))
    }
}
