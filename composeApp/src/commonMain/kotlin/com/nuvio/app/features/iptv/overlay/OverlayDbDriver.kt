package com.nuvio.app.features.iptv.overlay

import androidx.sqlite.SQLiteConnection

/**
 * Opens the on-disk SQLite database backing the IPTV personalization overlay (hide / pin / reorder /
 * custom groups). Disk-backed and profile-scoped; survives restarts. Mirrors [com.nuvio.app.features.iptv.match.MatchDbDriver].
 */
internal expect object OverlayDbDriver {
    fun openConnection(): SQLiteConnection
}
