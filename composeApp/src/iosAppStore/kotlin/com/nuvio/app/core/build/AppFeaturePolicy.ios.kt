package com.nuvio.app.core.build

actual object AppFeaturePolicy {
    actual val pluginsEnabled: Boolean = false
    // App Store builds hide the addon system entirely (guideline 5.2.3).
    actual val addonsEnabled: Boolean = false
    actual val supportersContributorsPageEnabled: Boolean = false
    actual val accountDeletionEnabled: Boolean = true
    actual val personalMediaAddonCopyEnabled: Boolean = true
    actual val p2pEnabled: Boolean = false
    actual val trailerPlaybackMode: TrailerPlaybackMode = TrailerPlaybackMode.EXTERNAL
    actual val heroTrailerPlaybackSupported: Boolean = false
    actual val inAppUpdaterEnabled: Boolean = false
    actual val imdbRatingLogoEnabled: Boolean = false
    actual val debugBackendSwitcherEnabled: Boolean = AppBuildConfig.IS_DEBUG_BUILD
}
