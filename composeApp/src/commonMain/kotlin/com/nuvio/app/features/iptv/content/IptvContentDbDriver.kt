package com.nuvio.app.features.iptv.content

import androidx.sqlite.SQLiteConnection

/**
 * Opens the on-disk SQLite database that stores the parsed M3U catalog (channels/vod/series per
 * playlist). Twin of [com.nuvio.app.features.iptv.match.MatchDbDriver] — platform actuals pick the
 * driver: AndroidSQLiteDriver (framework SQLite) on Android, BundledSQLiteDriver (SQLite compiled
 * into the framework) on iOS.
 *
 * Disk-backed is not optional here: an M3U URL playlist has NO API, so the parsed file IS the whole
 * catalog — commonly 190+ MB / 685k entries. It can't be re-parsed per browse and can't be held in
 * RAM, so it lives in SQLite (a few MB of page cache) and survives restarts.
 */
internal expect object IptvContentDbDriver {
    fun openConnection(): SQLiteConnection
}
