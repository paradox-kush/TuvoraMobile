package com.nuvio.app.core.deeplink

/**
 * A link that opens a screen (a title, Downloads, an add-on install) must not be routed while the profile picker or
 * the launch loader covers the shell: choosing a profile rebuilds the content and the screen opened underneath is
 * lost, so a tapped episode alert landed on Home. Such a link waits, still pending, until the shell is on screen.
 * Home (the away reminder) is already where the shell lands; setup and party links have their own timing.
 * Pure, so it is tested without a UI.
 */
internal object DeepLinkShellPolicy {
    fun shouldWaitForShell(link: AppDeepLink, hasGate: Boolean, shellReady: Boolean): Boolean {
        if (!hasGate || shellReady) return false
        return when (link) {
            is AppDeepLink.Meta,
            AppDeepLink.Downloads,
            is AppDeepLink.AddonInstall,
            -> true
            AppDeepLink.Home,
            is AppDeepLink.SetupCode,
            is AppDeepLink.Party,
            -> false
        }
    }
}
