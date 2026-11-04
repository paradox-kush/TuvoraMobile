package com.nuvio.app.core.deeplink

/** Where a tapped provider setup link sends the app, decided apart from navigation so it is testable. */
internal data class SetupLinkRoute(
    /** The settings page to open (a `SettingsPageRequest` name). */
    val settingsPage: String,
    /** True when the link arrived over a screen that hides the settings shell (player, stream list, detail). */
    val leaveToTabsFirst: Boolean,
)

internal object SetupLinkRoutePolicy {
    const val PREVIEW_PAGE = "IptvSetupPreview"
    const val ADD_PLAYLIST_PAGE = "IptvAddPlaylist"

    /**
     * A setup link opens the preview for everyone: a code needs no account to be looked at or added to the
     * device (B205), so a signed-out person or a guest is no longer detoured through the Add Playlist page.
     * @param onTabs the visible route is already the tab shell.
     * @param canPopToTabs this platform keeps the route stack here, so the shell can be reached by popping
     *   (native iOS navigation owns its stack in UIKit).
     */
    fun decide(onTabs: Boolean, canPopToTabs: Boolean): SetupLinkRoute =
        SetupLinkRoute(
            settingsPage = PREVIEW_PAGE,
            leaveToTabsFirst = !onTabs && canPopToTabs,
        )
}
