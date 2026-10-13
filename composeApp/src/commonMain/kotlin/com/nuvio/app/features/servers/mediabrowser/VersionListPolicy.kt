package com.nuvio.app.features.servers.mediabrowser

/** Decides when the item page needs a PlaybackInfo lookup to discover its real sources. */
internal object VersionListPolicy {
    /** Empty listings and on-demand placeholders may resolve to playable versions. */
    fun needsPlaybackInfo(sources: List<MediaSource>): Boolean =
        sources.all { it.isPlaceholder }
}
