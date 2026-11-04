package com.nuvio.app.core.deeplink

import kotlin.test.Test
import kotlin.test.assertEquals

class SetupLinkRoutePolicyTest {
    @Test
    fun aLinkOnTheShellOpensThePreviewInPlace_signedInOrNot() {
        val r = SetupLinkRoutePolicy.decide(onTabs = true, canPopToTabs = true)
        assertEquals("IptvSetupPreview", r.settingsPage, "no account is needed to look at a code (B205)")
        assertEquals(false, r.leaveToTabsFirst, "already on the shell: nothing to pop")
    }

    @Test
    fun linkOverPlaybackLeavesToTheShellFirst() {
        val r = SetupLinkRoutePolicy.decide(onTabs = false, canPopToTabs = true)
        assertEquals("IptvSetupPreview", r.settingsPage)
        assertEquals(true, r.leaveToTabsFirst, "the preview lives in the tab shell, hidden behind the player")
    }

    @Test
    fun nativeNavigationNeverPopsFromCommonCode() {
        val r = SetupLinkRoutePolicy.decide(onTabs = false, canPopToTabs = false)
        assertEquals(false, r.leaveToTabsFirst, "UIKit owns that stack")
    }
}
