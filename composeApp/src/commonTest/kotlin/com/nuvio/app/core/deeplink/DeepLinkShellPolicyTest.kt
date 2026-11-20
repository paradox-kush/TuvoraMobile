package com.nuvio.app.core.deeplink

import kotlin.test.Test
import kotlin.test.assertEquals

class DeepLinkShellPolicyTest {
    private val meta = AppDeepLink.Meta(type = "series", id = "tt1")

    @Test
    fun `a title link waits while the profile picker or loader covers the shell`() {
        assertEquals(true, DeepLinkShellPolicy.shouldWaitForShell(meta, hasGate = true, shellReady = false), "meta, shell not ready")
    }

    @Test
    fun `a title link opens at once once the shell is on screen`() {
        assertEquals(false, DeepLinkShellPolicy.shouldWaitForShell(meta, hasGate = true, shellReady = true), "meta, shell ready")
    }

    @Test
    fun `a host without a gate never waits`() {
        assertEquals(false, DeepLinkShellPolicy.shouldWaitForShell(meta, hasGate = false, shellReady = false), "no gate")
    }

    @Test
    fun `downloads and addon links that open a screen wait too`() {
        assertEquals(true, DeepLinkShellPolicy.shouldWaitForShell(AppDeepLink.Downloads, true, false), "downloads")
        assertEquals(true, DeepLinkShellPolicy.shouldWaitForShell(AppDeepLink.AddonInstall("https://x.y/manifest.json"), true, false), "addon")
    }

    @Test
    fun `the away reminder link still opens Home immediately`() {
        assertEquals(false, DeepLinkShellPolicy.shouldWaitForShell(AppDeepLink.Home, hasGate = true, shellReady = false), "home")
    }

    @Test
    fun `setup and party links keep their own timing`() {
        assertEquals(false, DeepLinkShellPolicy.shouldWaitForShell(AppDeepLink.SetupCode("abc"), true, false), "setup")
        assertEquals(false, DeepLinkShellPolicy.shouldWaitForShell(AppDeepLink.Party("E23TPG"), true, false), "party")
    }
}
