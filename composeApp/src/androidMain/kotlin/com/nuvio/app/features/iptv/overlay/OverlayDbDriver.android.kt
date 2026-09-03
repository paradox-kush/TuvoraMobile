package com.nuvio.app.features.iptv.overlay

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver

internal actual object OverlayDbDriver {
    private var dbPath: String? = null

    /** Host unit tests have no Context — they install a bundled in-memory driver here. */
    internal var openForTests: (() -> SQLiteConnection)? = null

    /** Called once at app startup (MainActivity), like MatchDbDriver.initialize. */
    fun initialize(context: Context) {
        dbPath = context.getDatabasePath("iptv_overlay.db").also { it.parentFile?.mkdirs() }.absolutePath
    }

    actual fun openConnection(): SQLiteConnection =
        openForTests?.invoke()
            ?: AndroidSQLiteDriver().open(checkNotNull(dbPath) { "OverlayDbDriver.initialize(context) not called" })
}
