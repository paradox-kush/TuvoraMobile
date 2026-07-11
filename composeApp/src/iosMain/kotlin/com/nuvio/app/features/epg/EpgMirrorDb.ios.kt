package com.nuvio.app.features.epg

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

internal actual object EpgMirrorDbDriver {
    @OptIn(ExperimentalForeignApi::class)
    actual fun openConnection(): SQLiteConnection {
        val fm = NSFileManager.defaultManager
        val support = fm.URLForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        ) ?: NSURL.fileURLWithPath(".")
        val path = support.URLByAppendingPathComponent("epg_mirror.db")?.path ?: "epg_mirror.db"
        // BundledSQLiteDriver compiles SQLite in — no system libsqlite3 dependency.
        return BundledSQLiteDriver().open(path)
    }
}
