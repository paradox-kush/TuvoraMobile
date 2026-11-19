package com.nuvio.app.core.build

enum class TrailerPlaybackMode {
    IN_APP,
    EXTERNAL,
}

expect object AppFeaturePolicy {
    /** Distribution flavor for analytics (`build_flavor`): "store" or "full". Must match the TV and Desktop apps. */
    val buildFlavor: String
    val pluginsEnabled: Boolean
    /** Stremio-style addon system (user-installable catalog/stream sources). Off in store builds. */
    val addonsEnabled: Boolean
    /** Debrid services + their cloud library. Off in store builds; saved keys are kept and still sync. */
    val debridEnabled: Boolean
    /**
     * Add-ons as playback sources. Off in store builds: synced add-ons still drive catalogs, metadata
     * and subtitles, but are never asked for streams (see AddonSourcePolicy).
     */
    val addonStreamSourcesEnabled: Boolean
    val supportersContributorsPageEnabled: Boolean
    val donationActionsEnabled: Boolean
    val donationProgressEnabled: Boolean
    val accountDeletionEnabled: Boolean
    val personalMediaAddonCopyEnabled: Boolean
    val p2pEnabled: Boolean
    val trailerPlaybackMode: TrailerPlaybackMode
    val heroTrailerPlaybackSupported: Boolean
    val inAppUpdaterEnabled: Boolean
    val imdbRatingLogoEnabled: Boolean
    val debugBackendSwitcherEnabled: Boolean
    val mediaPlaybackForegroundServiceEnabled: Boolean
    val downloadForegroundServiceEnabled: Boolean
    val customServerConnectionsEnabled: Boolean
}
