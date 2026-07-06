package com.nuvio.app.features.iptv.content

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver

internal actual object IptvContentDbDriver {
    private var dbPath: String? = null

    /** Called once at app startup (MainActivity), like MatchDbDriver.initialize. */
    fun initialize(context: Context) {
        dbPath = context.getDatabasePath("iptv_content.db").also { it.parentFile?.mkdirs() }.absolutePath
    }

    actual fun openConnection(): SQLiteConnection =
        AndroidSQLiteDriver().open(checkNotNull(dbPath) { "IptvContentDbDriver.initialize(context) not called" })
}
