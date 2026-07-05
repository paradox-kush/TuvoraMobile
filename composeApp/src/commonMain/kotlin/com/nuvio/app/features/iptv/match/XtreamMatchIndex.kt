package com.nuvio.app.features.iptv.match

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private inline fun <R> SQLiteStatement.use(block: (SQLiteStatement) -> R): R =
    try { block(this) } finally { close() }

internal enum class MatchKind(val slug: String) { MOVIE("movie"), SERIES("series") }

/** One catalog entry as stored in the index. [ext] = container extension (movies only). */
internal data class IndexedItem(
    val sid: Int,
    val name: String,
    val year: Int?,
    val tmdb: Int?,
    val ext: String?,
    val poster: String? = null,
)

/** A confirmed (or confirmed-absent when [sid] is null) TMDB->stream mapping. */
internal data class CachedMapping(val sid: Int?, val matchedName: String?, val updatedAtMs: Long)

/** Outcome of a [XtreamMatchIndex.sync]: how much of the catalog actually changed. */
internal data class SyncStats(val added: Int, val changed: Int, val removed: Int, val total: Int)

/** Pure diff outcome: items to (re-)insert, sids whose old name-keys must be dropped, vanished sids. */
internal data class CatalogDiff(val upserts: List<IndexedItem>, val changedSids: List<Int>, val goneSids: List<Int>)

/**
 * Row fingerprint for change detection between an indexed row and its fresh fetch.
 * ponytail: a 32-bit hash can collide (~2^-32 per changed row) leaving one stale row;
 * exact field comparison would need all 175k names in heap — accepted ceiling.
 */
internal fun itemFp(name: String, year: Int?, tmdb: Int?, ext: String?, poster: String?): Int {
    var h = name.hashCode()
    h = 31 * h + (year ?: -1)
    h = 31 * h + (tmdb ?: -1)
    h = 31 * h + (ext?.hashCode() ?: 0)
    h = 31 * h + (poster?.hashCode() ?: 0)
    return h
}

private fun IndexedItem.fp(): Int = itemFp(name, year, tmdb, ext, poster)

/**
 * Diffs a fresh catalog fetch against the indexed rows. [existingSids] MUST be ascending
 * (PK read order) and positionally aligned with [existingFps]. Unchanged rows cost one
 * binary search each — that's the whole "validate existing quickly" pass. Duplicate sids
 * in [fetched] (degenerate panels): first occurrence decides.
 */
internal fun diffCatalog(existingSids: IntArray, existingFps: IntArray, fetched: List<IndexedItem>): CatalogDiff {
    val seen = BooleanArray(existingSids.size)
    val upserts = ArrayList<IndexedItem>()
    val changedSids = ArrayList<Int>()
    for (item in fetched) {
        val i = existingSids.ascIndexOf(item.sid)
        if (i < 0) {
            upserts += item
        } else if (!seen[i]) {
            seen[i] = true
            if (existingFps[i] != item.fp()) {
                upserts += item
                changedSids += item.sid
            }
        }
    }
    val goneSids = ArrayList<Int>()
    for (i in existingSids.indices) if (!seen[i]) goneSids += existingSids[i]
    return CatalogDiff(upserts, changedSids, goneSids)
}

/** Binary search over an ascending IntArray (no boxing, no JVM Arrays dependency). */
private fun IntArray.ascIndexOf(v: Int): Int {
    var lo = 0
    var hi = size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val x = this[mid]
        when {
            x < v -> lo = mid + 1
            x > v -> hi = mid - 1
            else -> return mid
        }
    }
    return -1
}

/**
 * Disk-backed lookup index per provider+kind: normalized-name keys and bulk-list tmdb ids
 * over the full catalog, plus the cache of verified tmdb->sid mappings (the thing Supabase
 * syncs across devices). All lookups are single indexed SELECTs — O(log n) pages, sub-ms.
 */
internal object XtreamMatchIndex {

    private val mutex = Mutex()
    private var conn: SQLiteConnection? = null

    private fun connection(): SQLiteConnection = conn ?: MatchDbDriver.openConnection().also {
        // schema v2 adds items.poster (search cards). index tables are rebuildable caches,
        // mappings re-pull from Supabase — so migration is drop+recreate.
        val version = it.prepare("PRAGMA user_version").use { st -> if (st.step()) st.getLong(0) else 0L }
        if (version < 2) {
            it.execSQL("DROP TABLE IF EXISTS items"); it.execSQL("DROP TABLE IF EXISTS keys")
            it.execSQL("DROP TABLE IF EXISTS idx_meta"); it.execSQL("DROP TABLE IF EXISTS tmdb_map")
            it.execSQL("PRAGMA user_version = 2")
        }
        it.execSQL("CREATE TABLE IF NOT EXISTS items(provider TEXT NOT NULL, kind TEXT NOT NULL, sid INTEGER NOT NULL, name TEXT NOT NULL, year INTEGER, tmdb INTEGER, ext TEXT, poster TEXT, PRIMARY KEY(provider, kind, sid)) WITHOUT ROWID")
        it.execSQL("CREATE INDEX IF NOT EXISTS items_tmdb ON items(provider, kind, tmdb)")
        it.execSQL("CREATE TABLE IF NOT EXISTS keys(provider TEXT NOT NULL, kind TEXT NOT NULL, k TEXT NOT NULL, sid INTEGER NOT NULL, PRIMARY KEY(provider, kind, k, sid)) WITHOUT ROWID")
        it.execSQL("CREATE TABLE IF NOT EXISTS idx_meta(provider TEXT NOT NULL, kind TEXT NOT NULL, built_at INTEGER NOT NULL, item_count INTEGER NOT NULL, PRIMARY KEY(provider, kind)) WITHOUT ROWID")
        it.execSQL("CREATE TABLE IF NOT EXISTS tmdb_map(provider TEXT NOT NULL, kind TEXT NOT NULL, tmdb INTEGER NOT NULL, sid INTEGER, matched_name TEXT, updated_at INTEGER NOT NULL, synced INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(provider, kind, tmdb)) WITHOUT ROWID")
        conn = it
    }

    private fun now(): Long = TraktPlatformClock.nowEpochMs()

    /**
     * Drops EVERYTHING stored for one provider (index + local mapping mirror) — account
     * removed. The Supabase copy of the mappings survives for other devices / a re-add.
     */
    suspend fun purge(provider: String) {
        mutex.withLock {
            val c = connection()
            c.execSQL("BEGIN IMMEDIATE")
            try {
                for (t in listOf("items", "keys", "idx_meta", "tmdb_map")) {
                    c.prepare("DELETE FROM $t WHERE provider = ?").use { st ->
                        st.bindText(1, provider); st.step()
                    }
                }
                c.execSQL("COMMIT")
            } catch (t: Throwable) {
                c.execSQL("ROLLBACK"); throw t
            }
        }
    }

    suspend fun builtAt(provider: String, kind: MatchKind): Long? = mutex.withLock {
        connection().prepare("SELECT built_at FROM idx_meta WHERE provider = ? AND kind = ?").use { st ->
            st.bindText(1, provider); st.bindText(2, kind.slug)
            if (st.step()) st.getLong(0) else null
        }
    }

    /**
     * Replaces the whole index for one provider+kind. Chunked transactions keep the write
     * lock short so concurrent probes interleave; meta row is written LAST so a crashed
     * rebuild reads as stale, not as complete.
     */
    suspend fun rebuild(provider: String, kind: MatchKind, items: List<IndexedItem>) {
        mutex.withLock {
            val c = connection()
            c.execSQL("BEGIN IMMEDIATE")
            try {
                c.prepare("DELETE FROM items WHERE provider = ? AND kind = ?").use { st ->
                    st.bindText(1, provider); st.bindText(2, kind.slug); st.step()
                }
                c.prepare("DELETE FROM keys WHERE provider = ? AND kind = ?").use { st ->
                    st.bindText(1, provider); st.bindText(2, kind.slug); st.step()
                }
                c.prepare("DELETE FROM idx_meta WHERE provider = ? AND kind = ?").use { st ->
                    st.bindText(1, provider); st.bindText(2, kind.slug); st.step()
                }
                c.execSQL("COMMIT")
            } catch (t: Throwable) {
                c.execSQL("ROLLBACK"); throw t
            }
        }
        insertItems(provider, kind, items)
        writeMeta(provider, kind, items.size)
    }

    /**
     * Incrementally reconciles the index with a fresh catalog fetch: unchanged rows are
     * validated by fingerprint only (no re-normalization, no rewrite), new/renamed rows are
     * (re)indexed, vanished rows deleted. Falls back to [rebuild] when the index is empty or
     * the catalog reshuffled wholesale. built_at is bumped LAST so a crashed sync reads as
     * stale and re-runs (idempotent).
     */
    suspend fun sync(provider: String, kind: MatchKind, items: List<IndexedItem>): SyncStats {
        // One streaming pass over the existing rows -> primitive (sid, fingerprint) arrays,
        // PK-ordered. ~1.4MB for a 175k catalog; never materializes the old names in heap.
        var sids = IntArray(4_096)
        var fps = IntArray(4_096)
        var count = 0
        mutex.withLock {
            connection().prepare(
                "SELECT sid, name, year, tmdb, ext, poster FROM items WHERE provider = ? AND kind = ? ORDER BY sid"
            ).use { st ->
                st.bindText(1, provider); st.bindText(2, kind.slug)
                while (st.step()) {
                    if (count == sids.size) {
                        sids = sids.copyOf(count * 2); fps = fps.copyOf(count * 2)
                    }
                    sids[count] = st.getLong(0).toInt()
                    fps[count] = itemFp(
                        name = st.getText(1),
                        year = if (st.isNull(2)) null else st.getLong(2).toInt(),
                        tmdb = if (st.isNull(3)) null else st.getLong(3).toInt(),
                        ext = if (st.isNull(4)) null else st.getText(4),
                        poster = if (st.isNull(5)) null else st.getText(5),
                    )
                    count++
                }
            }
        }
        if (count == 0) {
            rebuild(provider, kind, items)
            return SyncStats(added = items.size, changed = 0, removed = 0, total = items.size)
        }
        // A glitchy panel returning an empty list must not wipe a good index — keep it,
        // don't bump built_at, let the next window retry.
        if (items.isEmpty()) return SyncStats(0, 0, 0, count)

        val diff = diffCatalog(sids.copyOf(count), fps.copyOf(count), items)
        // A wholesale reshuffle (provider migration, sid renumbering) is cheaper as a clean rebuild.
        if (diff.upserts.size + diff.goneSids.size > maxOf(500, count / 3)) {
            rebuild(provider, kind, items)
            return SyncStats(added = items.size, changed = 0, removed = 0, total = items.size)
        }

        // Deletes first: renamed rows' old name-keys and vanished rows. Then the (small) upsert
        // set rides the same chunked insert path as a full rebuild.
        mutex.withLock {
            val c = connection()
            c.execSQL("BEGIN IMMEDIATE")
            try {
                for (chunk in (diff.changedSids + diff.goneSids).chunked(500)) {
                    val ph = chunk.joinToString(",") { "?" }
                    c.prepare("DELETE FROM keys WHERE provider = ? AND kind = ? AND sid IN ($ph)").use { st ->
                        st.bindText(1, provider); st.bindText(2, kind.slug)
                        chunk.forEachIndexed { i, sid -> st.bindLong(i + 3, sid.toLong()) }
                        st.step()
                    }
                }
                for (chunk in diff.goneSids.chunked(500)) {
                    val ph = chunk.joinToString(",") { "?" }
                    c.prepare("DELETE FROM items WHERE provider = ? AND kind = ? AND sid IN ($ph)").use { st ->
                        st.bindText(1, provider); st.bindText(2, kind.slug)
                        chunk.forEachIndexed { i, sid -> st.bindLong(i + 3, sid.toLong()) }
                        st.step()
                    }
                }
                c.execSQL("COMMIT")
            } catch (t: Throwable) {
                c.execSQL("ROLLBACK"); throw t
            }
        }
        insertItems(provider, kind, diff.upserts)
        writeMeta(provider, kind, items.size)
        return SyncStats(
            added = diff.upserts.size - diff.changedSids.size,
            changed = diff.changedSids.size,
            removed = diff.goneSids.size,
            total = items.size,
        )
    }

    private suspend fun insertItems(provider: String, kind: MatchKind, items: List<IndexedItem>) {
        for (chunk in items.chunked(5_000)) {
            mutex.withLock {
                val c = connection()
                c.execSQL("BEGIN IMMEDIATE")
                try {
                    c.prepare("INSERT OR REPLACE INTO items(provider, kind, sid, name, year, tmdb, ext, poster) VALUES(?,?,?,?,?,?,?,?)").use { st ->
                        for (it in chunk) {
                            st.reset()
                            st.bindText(1, provider); st.bindText(2, kind.slug); st.bindLong(3, it.sid.toLong())
                            st.bindText(4, it.name)
                            if (it.year != null) st.bindLong(5, it.year.toLong()) else st.bindNull(5)
                            if (it.tmdb != null) st.bindLong(6, it.tmdb.toLong()) else st.bindNull(6)
                            if (it.ext != null) st.bindText(7, it.ext) else st.bindNull(7)
                            if (it.poster != null) st.bindText(8, it.poster) else st.bindNull(8)
                            st.step()
                        }
                    }
                    c.prepare("INSERT OR REPLACE INTO keys(provider, kind, k, sid) VALUES(?,?,?,?)").use { st ->
                        for (it in chunk) {
                            for (key in TitleNormalizer.keysOf(it.name)) {
                                st.reset()
                                st.bindText(1, provider); st.bindText(2, kind.slug); st.bindText(3, key); st.bindLong(4, it.sid.toLong())
                                st.step()
                            }
                        }
                    }
                    c.execSQL("COMMIT")
                } catch (t: Throwable) {
                    c.execSQL("ROLLBACK"); throw t
                }
            }
        }
    }

    private suspend fun writeMeta(provider: String, kind: MatchKind, itemCount: Int) {
        mutex.withLock {
            connection().prepare("INSERT OR REPLACE INTO idx_meta(provider, kind, built_at, item_count) VALUES(?,?,?,?)").use { st ->
                st.bindText(1, provider); st.bindText(2, kind.slug); st.bindLong(3, now()); st.bindLong(4, itemCount.toLong())
                st.step()
            }
        }
    }

    /** Substring name search over the indexed catalog — backs the IPTV rows in Search. */
    suspend fun searchByName(provider: String, kind: MatchKind, query: String, limit: Int): List<IndexedItem> = mutex.withLock {
        connection().prepare(
            "SELECT sid, name, year, tmdb, ext, poster FROM items WHERE provider = ? AND kind = ? AND name LIKE '%' || ? || '%' LIMIT ?"
        ).use { st ->
            st.bindText(1, provider); st.bindText(2, kind.slug); st.bindText(3, query); st.bindLong(4, limit.toLong())
            readItems(st)
        }
    }

    /** All items indexed under a normalized key. */
    suspend fun probe(provider: String, kind: MatchKind, key: String): List<IndexedItem> = mutex.withLock {
        connection().prepare(
            "SELECT i.sid, i.name, i.year, i.tmdb, i.ext, i.poster FROM keys x JOIN items i ON i.provider = x.provider AND i.kind = x.kind AND i.sid = x.sid WHERE x.provider = ? AND x.kind = ? AND x.k = ?"
        ).use { st ->
            st.bindText(1, provider); st.bindText(2, kind.slug); st.bindText(3, key)
            readItems(st)
        }
    }

    /** Tier-1: items whose bulk-list tmdb id already matches. */
    suspend fun byTmdb(provider: String, kind: MatchKind, tmdb: Int): List<IndexedItem> = mutex.withLock {
        connection().prepare("SELECT sid, name, year, tmdb, ext, poster FROM items WHERE provider = ? AND kind = ? AND tmdb = ?").use { st ->
            st.bindText(1, provider); st.bindText(2, kind.slug); st.bindLong(3, tmdb.toLong())
            readItems(st)
        }
    }

    suspend fun item(provider: String, kind: MatchKind, sid: Int): IndexedItem? = mutex.withLock {
        connection().prepare("SELECT sid, name, year, tmdb, ext, poster FROM items WHERE provider = ? AND kind = ? AND sid = ?").use { st ->
            st.bindText(1, provider); st.bindText(2, kind.slug); st.bindLong(3, sid.toLong())
            readItems(st).firstOrNull()
        }
    }

    private fun readItems(st: SQLiteStatement): List<IndexedItem> {
        val out = ArrayList<IndexedItem>()
        while (st.step()) {
            out.add(
                IndexedItem(
                    sid = st.getLong(0).toInt(),
                    name = st.getText(1),
                    year = if (st.isNull(2)) null else st.getLong(2).toInt(),
                    tmdb = if (st.isNull(3)) null else st.getLong(3).toInt(),
                    ext = if (st.isNull(4)) null else st.getText(4),
                    poster = if (st.isNull(5)) null else st.getText(5),
                )
            )
        }
        return out
    }

    // --- verified-mapping cache (local mirror of the Supabase iptv_tmdb_map rows) ---

    suspend fun cachedMapping(provider: String, kind: MatchKind, tmdb: Int): CachedMapping? = mutex.withLock {
        connection().prepare("SELECT sid, matched_name, updated_at FROM tmdb_map WHERE provider = ? AND kind = ? AND tmdb = ?").use { st ->
            st.bindText(1, provider); st.bindText(2, kind.slug); st.bindLong(3, tmdb.toLong())
            if (st.step()) CachedMapping(
                sid = if (st.isNull(0)) null else st.getLong(0).toInt(),
                matchedName = if (st.isNull(1)) null else st.getText(1),
                updatedAtMs = st.getLong(2),
            ) else null
        }
    }

    suspend fun putMapping(provider: String, kind: MatchKind, tmdb: Int, sid: Int?, matchedName: String?, synced: Boolean = false, updatedAtMs: Long = now()) {
        mutex.withLock {
            connection().prepare("INSERT OR REPLACE INTO tmdb_map(provider, kind, tmdb, sid, matched_name, updated_at, synced) VALUES(?,?,?,?,?,?,?)").use { st ->
                st.bindText(1, provider); st.bindText(2, kind.slug); st.bindLong(3, tmdb.toLong())
                if (sid != null) st.bindLong(4, sid.toLong()) else st.bindNull(4)
                if (matchedName != null) st.bindText(5, matchedName) else st.bindNull(5)
                st.bindLong(6, updatedAtMs)
                st.bindLong(7, if (synced) 1 else 0)
                st.step()
            }
        }
    }

    /** Rows not yet pushed to Supabase: (kind, tmdb, sid, matchedName, updatedAtMs). */
    suspend fun unsyncedMappings(provider: String): List<UnsyncedMapping> = mutex.withLock {
        connection().prepare("SELECT kind, tmdb, sid, matched_name, updated_at FROM tmdb_map WHERE provider = ? AND synced = 0").use { st ->
            st.bindText(1, provider)
            val out = ArrayList<UnsyncedMapping>()
            while (st.step()) {
                out.add(
                    UnsyncedMapping(
                        kind = st.getText(0),
                        tmdb = st.getLong(1).toInt(),
                        sid = if (st.isNull(2)) null else st.getLong(2).toInt(),
                        matchedName = if (st.isNull(3)) null else st.getText(3),
                        updatedAtMs = st.getLong(4),
                    )
                )
            }
            out
        }
    }

    suspend fun markSynced(provider: String, kind: String, tmdb: Int) {
        mutex.withLock {
            connection().prepare("UPDATE tmdb_map SET synced = 1 WHERE provider = ? AND kind = ? AND tmdb = ?").use { st ->
                st.bindText(1, provider); st.bindText(2, kind); st.bindLong(3, tmdb.toLong())
                st.step()
            }
        }
    }
}

internal data class UnsyncedMapping(val kind: String, val tmdb: Int, val sid: Int?, val matchedName: String?, val updatedAtMs: Long)
