package com.nuvio.app.core.build

enum class TrailerPlaybackMode {
    IN_APP,
    EXTERNAL,
}

expect object AppFeaturePolicy {
    val pluginsEnabled: Boolean
    /** Stremio-style addon system (user-installable catalog/stream sources). Off in store builds. */
    val addonsEnabled: Boolean
    val supportersContributorsPageEnabled: Boolean
    val accountDeletionEnabled: Boolean
    val personalMediaAddonCopyEnabled: Boolean
    val p2pEnabled: Boolean
    val trailerPlaybackMode: TrailerPlaybackMode
    val heroTrailerPlaybackSupported: Boolean
    val inAppUpdaterEnabled: Boolean
    val imdbRatingLogoEnabled: Boolean
    val debugBackendSwitcherEnabled: Boolean
}
