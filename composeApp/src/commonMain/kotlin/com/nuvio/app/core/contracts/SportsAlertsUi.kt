package com.nuvio.app.core.contracts

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * UI slots for in-app sports alerts (research/sports-alerts-spec-2026-11-24.md). The shared shell and the shared
 * Notifications settings page host them without importing the radar subsystem. No-op defaults: with nothing
 * registered there is no banner and no settings section. Compose-only (Apple TV draws its own in SwiftUI).
 */

/** The banner at the root of the app content, above the nav host, so it also shows over the player. */
interface SportsAlertsHost {
    /**
     * [ownsAppRuntime]: only the runtime owner starts the foreground-bound topic subscription (one per process);
     * every instance draws the shared banner. [onOpenSports] brings the Sports tab forward (the host has already
     * asked it, through [SportsGameRequest], to open the game); [onPlayChannel] plays a live channel id.
     */
    @Composable
    fun Render(
        ownsAppRuntime: Boolean,
        onOpenSports: () -> Unit,
        onPlayChannel: (String) -> Unit,
        modifier: Modifier,
    )
}

/** The "Sports alerts" section of the Notifications settings page. */
interface SportsAlertsSettingsSection {
    fun LazyListScope.sportsAlertsSettings(isTablet: Boolean)
}

object SportsAlertsHostAccess {
    private var host: SportsAlertsHost? = null
    fun register(h: SportsAlertsHost) { host = h }
    fun current(): SportsAlertsHost? = host
    fun resetForTest() { host = null }
}

object SportsAlertsSettingsSectionAccess {
    private var section: SportsAlertsSettingsSection? = null
    fun register(s: SportsAlertsSettingsSection) { section = s }
    fun current(): SportsAlertsSettingsSection? = section
    fun resetForTest() { section = null }
}
