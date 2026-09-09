package com.nuvio.app.features.epg

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver

internal actual object EpgMirrorDbDriver {
    private var dbPath: String? = null

    /** Host tests have no Android Context — inject an in-memory bundled driver (the IptvContentDbDriver idiom). */
    internal var openForTests: (() -> SQLiteConnection)? = null

    /** Called once at app startup (MainActivity), like MatchDbDriver.initialize. */
    fun initialize(context: Context) {
        dbPath = context.getDatabasePath("epg_mirror.db").also { it.parentFile?.mkdirs() }.absolutePath
    }

    actual fun openConnection(): SQLiteConnection =
        openForTests?.invoke()
            ?: AndroidSQLiteDriver().open(checkNotNull(dbPath) { "EpgMirrorDbDriver.initialize(context) not called" })
}
