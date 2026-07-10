package com.nuvio.app.core.build

actual object AppFeaturePolicy {
    actual val pluginsEnabled: Boolean = false
    // Store builds hide the addon system: pure BYO-IPTV player posture (Play policy 4.2.2).
    actual val addonsEnabled: Boolean = false
    actual val supportersContributorsPageEnabled: Boolean = true
    // Google Play requires in-app account deletion when the app offers account creation.
    actual val accountDeletionEnabled: Boolean = true
    actual val personalMediaAddonCopyEnabled: Boolean = false
    actual val p2pEnabled: Boolean = true
    actual val trailerPlaybackMode: TrailerPlaybackMode = TrailerPlaybackMode.EXTERNAL
    actual val heroTrailerPlaybackSupported: Boolean = false
    actual val inAppUpdaterEnabled: Boolean = false
    actual val imdbRatingLogoEnabled: Boolean = false
    actual val debugBackendSwitcherEnabled: Boolean = AppBuildConfig.IS_DEBUG_BUILD
}
