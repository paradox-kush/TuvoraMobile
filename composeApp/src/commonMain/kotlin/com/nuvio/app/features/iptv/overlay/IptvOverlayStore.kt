package com.nuvio.app.features.iptv.overlay

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * On-disk store for the IPTV personalization overlay — the durable hide / pin / reorder / custom-group
 * edits, keyed on the canon-v1 identity so they survive a catalog rebuild and match the ids the website
 * wrote. Profile-scoped (the catalog is cross-profile shared, personalization is per-profile). Sparse:
 * only edited entities have rows. Every row carries `updated_at` (client LWW clock) + `deleted`
 * (tombstone) so the delta sync ([IptvOverlaySyncAdapter]) layers on without a schema change; local
 * reads ignore `deleted = 1`.
 *
 * Same idioms as [com.nuvio.app.features.iptv.match.XtreamMatchIndex]: one lazily-opened connection,
 * Mutex-guarded, WAL + synchronous=NORMAL (a torn write at worst loses an edit the next sync re-pulls).
 */
internal object IptvOverlayStore {

    private val mutex = Mutex()
    private var conn: SQLiteConnection? = null

    private fun connection(): SQLiteConnection = conn ?: OverlayDbDriver.openConnection().also {
        runCatching {
            it.prepare("PRAGMA journal_mode = WAL").use { st -> st.step() }
            it.execSQL("PRAGMA synchronous = NORMAL")
        }
        it.execSQL(
            "CREATE TABLE IF NOT EXISTS channel_overlay(profile_id INTEGER NOT NULL, entity_id TEXT NOT NULL, " +
                "playlist_id TEXT, hidden INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0, " +
                "position INTEGER, rename TEXT, updated_at INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(profile_id, entity_id)) WITHOUT ROWID",
        )
        it.execSQL("CREATE INDEX IF NOT EXISTS channel_overlay_pl ON channel_overlay(profile_id, playlist_id)")
        it.execSQL(
            "CREATE TABLE IF NOT EXISTS category_overlay(profile_id INTEGER NOT NULL, playlist_id TEXT NOT NULL, " +
                "content_type TEXT NOT NULL, category_key TEXT NOT NULL, hidden INTEGER NOT NULL DEFAULT 0, " +
                "pinned INTEGER NOT NULL DEFAULT 0, position INTEGER, rename TEXT, updated_at INTEGER NOT NULL DEFAULT 0, " +
                "deleted INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(profile_id, playlist_id, content_type, category_key)) WITHOUT ROWID",
        )
        it.execSQL(
            "CREATE TABLE IF NOT EXISTS custom_group(profile_id INTEGER NOT NULL, group_id TEXT NOT NULL, " +
                "playlist_id TEXT, content_type TEXT NOT NULL, name TEXT NOT NULL, position INTEGER NOT NULL DEFAULT 0, " +
                "updated_at INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(profile_id, group_id)) WITHOUT ROWID",
        )
        it.execSQL(
            "CREATE TABLE IF NOT EXISTS custom_group_member(profile_id INTEGER NOT NULL, group_id TEXT NOT NULL, " +
                "entity_id TEXT NOT NULL, position INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT 0, " +
                "deleted INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(profile_id, group_id, entity_id)) WITHOUT ROWID",
        )
        it.execSQL(
            "CREATE TABLE IF NOT EXISTS overlay_cursor(profile_id INTEGER NOT NULL PRIMARY KEY, cursor INTEGER NOT NULL DEFAULT 0) WITHOUT ROWID",
        )
        it.execSQL("PRAGMA user_version = 1")
        conn = it
    }

    // ---- delta-sync cursor + remote apply (used by IptvOverlaySyncAdapter) ------------------------

    suspend fun getCursor(profileId: Int): Long = mutex.withLock {
        connection().prepare("SELECT cursor FROM overlay_cursor WHERE profile_id = ?").use { st ->
            st.bindLong(1, profileId.toLong()); if (st.step()) st.getLong(0) else 0L
        }
    }

    suspend fun setCursor(profileId: Int, cursor: Long): Unit = mutex.withLock {
        connection().prepare("INSERT INTO overlay_cursor(profile_id, cursor) VALUES(?,?) ON CONFLICT(profile_id) DO UPDATE SET cursor=excluded.cursor").use { st ->
            st.bindLong(1, profileId.toLong()); st.bindLong(2, cursor); st.step()
        }
    }

    /** Apply a pulled channel event (deleted = the event was a 'delete'). Overwrites (LWW handled by the server order). */
    suspend fun applyRemoteChannel(profileId: Int, entityId: String, playlistId: String?, o: ChannelOverlay, updatedAt: Long, deleted: Boolean): Unit = mutex.withLock {
        connection().prepare(
            "INSERT INTO channel_overlay(profile_id, entity_id, playlist_id, hidden, pinned, position, rename, updated_at, deleted) " +
                "VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(profile_id, entity_id) DO UPDATE SET playlist_id=excluded.playlist_id, " +
                "hidden=excluded.hidden, pinned=excluded.pinned, position=excluded.position, rename=excluded.rename, " +
                "updated_at=excluded.updated_at, deleted=excluded.deleted",
        ).use { st ->
            st.bindLong(1, profileId.toLong()); st.bindText(2, entityId)
            if (playlistId != null) st.bindText(3, playlistId) else st.bindNull(3)
            st.bindLong(4, if (o.hidden) 1 else 0); st.bindLong(5, if (o.pinned) 1 else 0)
            if (o.position != null) st.bindLong(6, o.position.toLong()) else st.bindNull(6)
            if (o.rename != null) st.bindText(7, o.rename) else st.bindNull(7)
            st.bindLong(8, updatedAt); st.bindLong(9, if (deleted) 1 else 0)
            st.step()
        }
    }

    suspend fun applyRemoteCategory(profileId: Int, playlistId: String, contentType: String, categoryKey: String, o: CategoryOverlay, updatedAt: Long, deleted: Boolean): Unit = mutex.withLock {
        connection().prepare(
            "INSERT INTO category_overlay(profile_id, playlist_id, content_type, category_key, hidden, pinned, position, rename, updated_at, deleted) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(profile_id, playlist_id, content_type, category_key) DO UPDATE SET " +
                "hidden=excluded.hidden, pinned=excluded.pinned, position=excluded.position, rename=excluded.rename, " +
                "updated_at=excluded.updated_at, deleted=excluded.deleted",
        ).use { st ->
            st.bindLong(1, profileId.toLong()); st.bindText(2, playlistId); st.bindText(3, contentType); st.bindText(4, categoryKey)
            st.bindLong(5, if (o.hidden) 1 else 0); st.bindLong(6, if (o.pinned) 1 else 0)
            if (o.position != null) st.bindLong(7, o.position.toLong()) else st.bindNull(7)
            if (o.rename != null) st.bindText(8, o.rename) else st.bindNull(8)
            st.bindLong(9, updatedAt); st.bindLong(10, if (deleted) 1 else 0)
            st.step()
        }
    }

    suspend fun applyRemoteGroup(profileId: Int, groupId: String, playlistId: String?, contentType: String, name: String, position: Int, updatedAt: Long, deleted: Boolean): Unit = mutex.withLock {
        connection().prepare(
            "INSERT INTO custom_group(profile_id, group_id, playlist_id, content_type, name, position, updated_at, deleted) " +
                "VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(profile_id, group_id) DO UPDATE SET playlist_id=excluded.playlist_id, " +
                "content_type=excluded.content_type, name=excluded.name, position=excluded.position, updated_at=excluded.updated_at, deleted=excluded.deleted",
        ).use { st ->
            st.bindLong(1, profileId.toLong()); st.bindText(2, groupId)
            if (playlistId != null) st.bindText(3, playlistId) else st.bindNull(3)
            st.bindText(4, contentType); st.bindText(5, name); st.bindLong(6, position.toLong()); st.bindLong(7, updatedAt); st.bindLong(8, if (deleted) 1 else 0)
            st.step()
        }
    }

    suspend fun applyRemoteMember(profileId: Int, groupId: String, entityId: String, position: Int, updatedAt: Long, deleted: Boolean): Unit = mutex.withLock {
        connection().prepare(
            "INSERT INTO custom_group_member(profile_id, group_id, entity_id, position, updated_at, deleted) VALUES(?,?,?,?,?,?) " +
                "ON CONFLICT(profile_id, group_id, entity_id) DO UPDATE SET position=excluded.position, updated_at=excluded.updated_at, deleted=excluded.deleted",
        ).use { st ->
            st.bindLong(1, profileId.toLong()); st.bindText(2, groupId); st.bindText(3, entityId); st.bindLong(4, position.toLong()); st.bindLong(5, updatedAt); st.bindLong(6, if (deleted) 1 else 0)
            st.step()
        }
    }

    /** Rows to push to the server: every edited row (including tombstones) as (kind, okey, playlistId, valueJson, updatedAt). */
    suspend fun rowsForPush(profileId: Int): List<OverlayPushRow> = mutex.withLock {
        val c = connection()
        val out = ArrayList<OverlayPushRow>()
        c.prepare("SELECT entity_id, playlist_id, hidden, pinned, position, rename, updated_at, deleted FROM channel_overlay WHERE profile_id = ?").use { st ->
            st.bindLong(1, profileId.toLong())
            while (st.step()) {
                val v = buildString {
                    append("{\"hidden\":").append(st.getLong(2) != 0L)
                    append(",\"pinned\":").append(st.getLong(3) != 0L)
                    if (!st.isNull(4)) append(",\"position\":").append(st.getLong(4))
                    if (!st.isNull(5)) append(",\"rename\":").append(jsonStr(st.getText(5)))
                    append("}")
                }
                out.add(OverlayPushRow("channel", st.getText(0), if (st.isNull(1)) null else st.getText(1), v, st.getLong(6), st.getLong(7) != 0L))
            }
        }
        out
    }

    private fun jsonStr(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) when (ch) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
            else -> sb.append(ch)
        }
        return sb.append("\"").toString()
    }

    /** Load the whole (sparse) overlay for a profile — the read layer consults this while composing. */
    suspend fun snapshot(profileId: Int): OverlaySnapshot = mutex.withLock {
        val c = connection()
        val channels = HashMap<String, ChannelOverlay>()
        c.prepare("SELECT entity_id, hidden, pinned, position, rename FROM channel_overlay WHERE profile_id = ? AND deleted = 0").use { st ->
            st.bindLong(1, profileId.toLong())
            while (st.step()) {
                channels[st.getText(0)] = ChannelOverlay(
                    hidden = st.getLong(1) != 0L,
                    pinned = st.getLong(2) != 0L,
                    position = if (st.isNull(3)) null else st.getLong(3).toInt(),
                    rename = if (st.isNull(4)) null else st.getText(4),
                )
            }
        }
        val categories = HashMap<String, CategoryOverlay>()
        c.prepare("SELECT category_key, hidden, pinned, position, rename FROM category_overlay WHERE profile_id = ? AND deleted = 0").use { st ->
            st.bindLong(1, profileId.toLong())
            while (st.step()) {
                categories[st.getText(0)] = CategoryOverlay(
                    hidden = st.getLong(1) != 0L,
                    pinned = st.getLong(2) != 0L,
                    position = if (st.isNull(3)) null else st.getLong(3).toInt(),
                    rename = if (st.isNull(4)) null else st.getText(4),
                )
            }
        }
        val members = HashMap<String, MutableList<Pair<Int, String>>>() // groupId -> (position, entityId)
        c.prepare("SELECT group_id, entity_id, position FROM custom_group_member WHERE profile_id = ? AND deleted = 0").use { st ->
            st.bindLong(1, profileId.toLong())
            while (st.step()) {
                members.getOrPut(st.getText(0)) { mutableListOf() }.add(st.getLong(2).toInt() to st.getText(1))
            }
        }
        val groups = ArrayList<CustomGroup>()
        c.prepare("SELECT group_id, playlist_id, content_type, name, position FROM custom_group WHERE profile_id = ? AND deleted = 0").use { st ->
            st.bindLong(1, profileId.toLong())
            while (st.step()) {
                val gid = st.getText(0)
                groups.add(
                    CustomGroup(
                        id = gid,
                        playlistId = if (st.isNull(1)) null else st.getText(1),
                        contentType = st.getText(2),
                        name = st.getText(3),
                        position = st.getLong(4).toInt(),
                        memberEntityIds = members[gid]?.sortedBy { it.first }?.map { it.second } ?: emptyList(),
                    ),
                )
            }
        }
        OverlaySnapshot(channels = channels, categories = categories, groups = groups)
    }

    /** Upsert a channel edit (or tombstone it when the edit is a no-op), stamping [updatedAt]. */
    suspend fun setChannel(profileId: Int, entityId: String, playlistId: String?, o: ChannelOverlay, updatedAt: Long): Unit = mutex.withLock {
        val c = connection()
        c.prepare(
            "INSERT INTO channel_overlay(profile_id, entity_id, playlist_id, hidden, pinned, position, rename, updated_at, deleted) " +
                "VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(profile_id, entity_id) DO UPDATE SET playlist_id=excluded.playlist_id, " +
                "hidden=excluded.hidden, pinned=excluded.pinned, position=excluded.position, rename=excluded.rename, " +
                "updated_at=excluded.updated_at, deleted=excluded.deleted",
        ).use { st ->
            st.bindLong(1, profileId.toLong()); st.bindText(2, entityId)
            if (playlistId != null) st.bindText(3, playlistId) else st.bindNull(3)
            st.bindLong(4, if (o.hidden) 1 else 0); st.bindLong(5, if (o.pinned) 1 else 0)
            if (o.position != null) st.bindLong(6, o.position.toLong()) else st.bindNull(6)
            if (o.rename != null) st.bindText(7, o.rename) else st.bindNull(7)
            st.bindLong(8, updatedAt); st.bindLong(9, if (o.isNoop) 1 else 0)
            st.step()
        }
    }

    suspend fun setCategory(profileId: Int, playlistId: String, contentType: String, categoryKey: String, o: CategoryOverlay, updatedAt: Long): Unit = mutex.withLock {
        val c = connection()
        c.prepare(
            "INSERT INTO category_overlay(profile_id, playlist_id, content_type, category_key, hidden, pinned, position, rename, updated_at, deleted) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(profile_id, playlist_id, content_type, category_key) DO UPDATE SET " +
                "hidden=excluded.hidden, pinned=excluded.pinned, position=excluded.position, rename=excluded.rename, " +
                "updated_at=excluded.updated_at, deleted=excluded.deleted",
        ).use { st ->
            st.bindLong(1, profileId.toLong()); st.bindText(2, playlistId); st.bindText(3, contentType); st.bindText(4, categoryKey)
            st.bindLong(5, if (o.hidden) 1 else 0); st.bindLong(6, if (o.pinned) 1 else 0)
            if (o.position != null) st.bindLong(7, o.position.toLong()) else st.bindNull(7)
            if (o.rename != null) st.bindText(8, o.rename) else st.bindNull(8)
            st.bindLong(9, updatedAt); st.bindLong(10, if (o.isNoop) 1 else 0)
            st.step()
        }
    }

    /** Create/replace a custom group and its ordered membership in one transaction. */
    suspend fun putGroup(profileId: Int, group: CustomGroup, updatedAt: Long): Unit = mutex.withLock {
        val c = connection()
        c.execSQL("BEGIN IMMEDIATE")
        try {
            c.prepare(
                "INSERT INTO custom_group(profile_id, group_id, playlist_id, content_type, name, position, updated_at, deleted) " +
                    "VALUES(?,?,?,?,?,?,?,0) ON CONFLICT(profile_id, group_id) DO UPDATE SET playlist_id=excluded.playlist_id, " +
                    "content_type=excluded.content_type, name=excluded.name, position=excluded.position, updated_at=excluded.updated_at, deleted=0",
            ).use { st ->
                st.bindLong(1, profileId.toLong()); st.bindText(2, group.id)
                if (group.playlistId != null) st.bindText(3, group.playlistId) else st.bindNull(3)
                st.bindText(4, group.contentType); st.bindText(5, group.name); st.bindLong(6, group.position.toLong()); st.bindLong(7, updatedAt)
                st.step()
            }
            // Replace membership: tombstone existing, then upsert the new ordered set.
            c.prepare("UPDATE custom_group_member SET deleted = 1, updated_at = ? WHERE profile_id = ? AND group_id = ?").use { st ->
                st.bindLong(1, updatedAt); st.bindLong(2, profileId.toLong()); st.bindText(3, group.id); st.step()
            }
            c.prepare(
                "INSERT INTO custom_group_member(profile_id, group_id, entity_id, position, updated_at, deleted) VALUES(?,?,?,?,?,0) " +
                    "ON CONFLICT(profile_id, group_id, entity_id) DO UPDATE SET position=excluded.position, updated_at=excluded.updated_at, deleted=0",
            ).use { st ->
                group.memberEntityIds.forEachIndexed { i, entity ->
                    st.reset()
                    st.bindLong(1, profileId.toLong()); st.bindText(2, group.id); st.bindText(3, entity); st.bindLong(4, i.toLong()); st.bindLong(5, updatedAt)
                    st.step()
                }
            }
            c.execSQL("COMMIT")
        } catch (t: Throwable) {
            runCatching { c.execSQL("ROLLBACK") }
            throw t
        }
    }

    suspend fun deleteGroup(profileId: Int, groupId: String, updatedAt: Long): Unit = mutex.withLock {
        val c = connection()
        c.prepare("UPDATE custom_group SET deleted = 1, updated_at = ? WHERE profile_id = ? AND group_id = ?").use { st ->
            st.bindLong(1, updatedAt); st.bindLong(2, profileId.toLong()); st.bindText(3, groupId); st.step()
        }
        c.prepare("UPDATE custom_group_member SET deleted = 1, updated_at = ? WHERE profile_id = ? AND group_id = ?").use { st ->
            st.bindLong(1, updatedAt); st.bindLong(2, profileId.toLong()); st.bindText(3, groupId); st.step()
        }
    }

    /** Drop a removed playlist's channel + category overlay (custom groups may span playlists — left alone). */
    suspend fun purgePlaylist(profileId: Int, playlistId: String): Unit = mutex.withLock {
        val c = connection()
        c.prepare("DELETE FROM channel_overlay WHERE profile_id = ? AND playlist_id = ?").use { st ->
            st.bindLong(1, profileId.toLong()); st.bindText(2, playlistId); st.step()
        }
        c.prepare("DELETE FROM category_overlay WHERE profile_id = ? AND playlist_id = ?").use { st ->
            st.bindLong(1, profileId.toLong()); st.bindText(2, playlistId); st.step()
        }
    }

    /** Test hook: close so the next call re-opens (verifies persistence across reopen). */
    internal suspend fun closeForTests(): Unit = mutex.withLock {
        conn?.close(); conn = null
    }
}
