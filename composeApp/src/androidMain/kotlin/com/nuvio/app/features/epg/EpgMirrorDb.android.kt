package com.nuvio.app.features.epg

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver

internal actual object EpgMirrorDbDriver {
    private var dbPath: String? = null

    /** Called once at app startup (MainActivity), like MatchDbDriver.initialize. */
    fun initialize(context: Context) {
        dbPath = context.getDatabasePath("epg_mirror.db").also { it.parentFile?.mkdirs() }.absolutePath
    }

    actual fun openConnection(): SQLiteConnection =
        AndroidSQLiteDriver().open(checkNotNull(dbPath) { "EpgMirrorDbDriver.initialize(context) not called" })
}
