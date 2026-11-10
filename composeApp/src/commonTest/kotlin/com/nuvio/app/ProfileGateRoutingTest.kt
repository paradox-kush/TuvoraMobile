package com.nuvio.app

import com.nuvio.app.ProfileGateRouting.SwitchRequestRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileGateRoutingTest {

    @Test
    fun `iOS settings cover with only the native switcher routes to it instead of a dead tap`() {
        assertEquals(
            SwitchRequestRoute.NativeSwitcher,
            ProfileGateRouting.switchRequestRoute(hasGateController = false, hasNativeSwitcher = true),
            "Settings cover has no AppGateController; Switch profile must reach the gate via the native switcher",
        )
    }

    @Test
    fun `gate controller wins when both exist`() {
        assertEquals(
            SwitchRequestRoute.GateController,
            ProfileGateRouting.switchRequestRoute(hasGateController = true, hasNativeSwitcher = true),
        )
    }

    @Test
    fun `no route means the entry point is hidden`() {
        assertEquals(
            SwitchRequestRoute.None,
            ProfileGateRouting.switchRequestRoute(hasGateController = false, hasNativeSwitcher = false),
        )
    }

    @Test
    fun `iOS home host that renders main content and owns the gate listens to the native switcher`() {
        assertTrue(
            ProfileGateRouting.listensForNativeSwitcher(
                bypassAppGate = false,
                hasNativeSwitcher = true,
            ),
            "Home host (renderMainContent=true, no controller) is the only gate; it must hear the switcher",
        )
    }

    @Test
    fun `bypassing hosts never listen so a request is handled exactly once`() {
        assertFalse(
            ProfileGateRouting.listensForNativeSwitcher(
                bypassAppGate = true,
                hasNativeSwitcher = true,
            ),
        )
    }

    @Test
    fun `hosts without a native switcher never listen`() {
        assertFalse(
            ProfileGateRouting.listensForNativeSwitcher(
                bypassAppGate = false,
                hasNativeSwitcher = false,
            ),
        )
    }
}
