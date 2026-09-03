package com.nuvio.app.features.iptv.content

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Schema helpers that introspect before they mutate, so a migration never has to guess.
 *
 * The old idiom was `runCatching { ALTER TABLE … ADD COLUMN … }`, which treats EVERY failure —
 * a locked database, a full disk, corruption, a malformed statement — as "the column probably
 * already existed" and carries on with a schema it cannot trust. These helpers ask SQLite
 * (`PRAGMA table_info`) and only issue the ALTER when the column is genuinely absent; a real
 * failure propagates and fails the open visibly, to be retried on the next launch.
 */

/** True when [table] exists in the connected database. */
internal fun SQLiteConnection.tableExists(table: String): Boolean =
    prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { st ->
        st.bindText(1, table)
        st.step()
    }

/** The column names of [table] (empty when the table does not exist). */
internal fun SQLiteConnection.columnsOf(table: String): Set<String> =
    prepare("PRAGMA table_info($table)").use { st ->
        val out = HashSet<String>()
        while (st.step()) out.add(st.getText(1))
        out
    }

/**
 * Adds [column] to [table] with [ddl] (the full column definition, e.g. `"foo INTEGER NOT NULL
 * DEFAULT 0"`) unless it is already there. A missing table is a no-op: the caller's CREATE TABLE
 * builds it with the column. Any SQLite error from the ALTER propagates.
 */
internal fun SQLiteConnection.ensureColumn(table: String, column: String, ddl: String) {
    if (!tableExists(table)) return
    if (column in columnsOf(table)) return
    execSQL("ALTER TABLE $table ADD COLUMN $ddl")
}
