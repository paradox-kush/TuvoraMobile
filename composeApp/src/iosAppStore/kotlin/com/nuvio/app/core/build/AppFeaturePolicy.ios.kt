package com.nuvio.app.core.build

actual object AppFeaturePolicy {
    actual val buildFlavor: String = "store"
    actual val pluginsEnabled: Boolean = false
    // App Store builds hide the addon system entirely (guideline 5.2.3).
    actual val addonsEnabled: Boolean = false
    // Debrid (Real-Debrid/Premiumize/TorBox) is a torrent-cache service; with addons gone it has no
    // legitimate job here and reads as a piracy tool to App Review (guideline 5.2.3).
    actual val debridEnabled: Boolean = false
    // Add-ons stay for discovery (catalogs, metadata, subtitles), never as stream sources.
    actual val addonStreamSourcesEnabled: Boolean = false
    actual val supportersContributorsPageEnabled: Boolean = false
    actual val donationActionsEnabled: Boolean = false
    actual val donationProgressEnabled: Boolean = true
    actual val accountDeletionEnabled: Boolean = true
    actual val personalMediaAddonCopyEnabled: Boolean = true
    actual val p2pEnabled: Boolean = false
    actual val trailerPlaybackMode: TrailerPlaybackMode = TrailerPlaybackMode.EXTERNAL
    actual val heroTrailerPlaybackSupported: Boolean = false
    actual val inAppUpdaterEnabled: Boolean = false
    actual val imdbRatingLogoEnabled: Boolean = false
    actual val debugBackendSwitcherEnabled: Boolean = AppBuildConfig.IS_DEBUG_BUILD
    actual val mediaPlaybackForegroundServiceEnabled: Boolean = false
    actual val downloadForegroundServiceEnabled: Boolean = false
    actual val customServerConnectionsEnabled: Boolean = false
}
