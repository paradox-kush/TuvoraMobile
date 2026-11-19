package com.nuvio.app.core.build

actual object AppFeaturePolicy {
    actual val buildFlavor: String = "store"
    actual val pluginsEnabled: Boolean = false
    // Store builds hide the addon system: pure BYO-IPTV player posture (Play policy 4.2.2).
    actual val addonsEnabled: Boolean = false
    // Debrid is a torrent-cache service: same store posture as addons and P2P.
    actual val debridEnabled: Boolean = false
    // Add-ons stay for discovery (catalogs, metadata, subtitles), never as stream sources.
    actual val addonStreamSourcesEnabled: Boolean = false
    actual val supportersContributorsPageEnabled: Boolean = false
    // Google Play requires in-app account deletion when the app offers account creation.
    actual val accountDeletionEnabled: Boolean = true
    actual val personalMediaAddonCopyEnabled: Boolean = false
    // Store builds ship without torrent streaming, same posture as addons above;
    // sideload full builds keep it (upstream sets true here).
    actual val p2pEnabled: Boolean = false
    actual val trailerPlaybackMode: TrailerPlaybackMode = TrailerPlaybackMode.EXTERNAL
    actual val heroTrailerPlaybackSupported: Boolean = false
    actual val inAppUpdaterEnabled: Boolean = false
    actual val imdbRatingLogoEnabled: Boolean = false
    actual val debugBackendSwitcherEnabled: Boolean = AppBuildConfig.IS_DEBUG_BUILD
    actual val mediaPlaybackForegroundServiceEnabled: Boolean = false
    // Upstream custom-server + donation flags the fork does not use (fork = SyncBackend + its own
    // Donate row via DONATIONS_DONATE_URL); kept off.
    actual val donationActionsEnabled: Boolean = false
    actual val donationProgressEnabled: Boolean = false
    actual val downloadForegroundServiceEnabled: Boolean = false
    actual val customServerConnectionsEnabled: Boolean = false
}
