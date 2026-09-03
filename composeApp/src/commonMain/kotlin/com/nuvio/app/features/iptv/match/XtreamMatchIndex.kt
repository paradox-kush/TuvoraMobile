package com.nuvio.app.features.iptv.match

import androidx.sqlite.SQLiteConnection
import com.nuvio.app.features.iptv.content.ensureColumn
import com.nuvio.app.features.iptv.identity.IptvIdentity
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import com.nuvio.app.core.memory.AppMemory
import com.nuvio.app.core.contracts.MemoryTierPolicy
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

private inline fun <R> SQLiteStatement.use(block: (SQLiteStatement) -> R): R =
    try { block(this) } finally { close() }

internal enum class MatchKind(val slug: String) { MOVIE("movie"), SERIES("series"), LIVE("live") }

/**
 * One catalog entry as stored in the index. [ext] = container extension (movies only).
 * P7 (items 4-5): the index doubles as the Xtream BROWSE catalog — [categoryId] scopes hub rows,
 * [epgId]/[hasArchive] carry the live-channel fields the guide needs. The download was always
 * paid for (this index re-fetches the full catalog every 72h); now the hub reads it back instead
 * of re-fetching per category per session.
 */
internal data class IndexedItem(
    val sid: Int,
    val name: String,
    val year: Int?,
    val tmdb: Int?,
    val ext: String?,
    val poster: String? = null,
    val categoryId: String? = null,
    val epgId: String? = null,
    val hasArchive: Boolean = false,
    /** Arrival index in the panel's bulk list — categories serve in THE PANEL'S order, never sorted. */
    val pos: Int = 0,
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
 *
 * Poster is deliberately NOT part of the fingerprint: lazily enriched artwork (written by
 * PosterEnricher for panels whose bulk lists ship no icons) must not read as a "change" on
 * the next sync — the bulk row's empty icon would win and wipe the enrichment. Bulk icon
 * updates still land on any row the diff rewrites (the write coalesces incoming nulls).
 */
internal fun itemFp(
    name: String, year: Int?, tmdb: Int?, ext: String?,
    categoryId: String? = null, epgId: String? = null, hasArchive: Boolean = false,
    pos: Int = 0,
): Int {
    var h = name.hashCode()
    h = 31 * h + (year ?: -1)
    h = 31 * h + (tmdb ?: -1)
    h = 31 * h + (ext?.hashCode() ?: 0)
    h = 31 * h + (categoryId?.hashCode() ?: 0)
    h = 31 * h + (epgId?.hashCode() ?: 0)
    h = 31 * h + if (hasArchive) 1 else 0
    h = 31 * h + pos
    return h
}

private fun IndexedItem.fp(): Int = itemFp(name, year, tmdb, ext, categoryId, epgId, hasArchive, pos)

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

    private val _buildProgress = MutableStateFlow<Map<String, IndexBuildProgress>>(emptyMap())

    /**
     * Live per-provider index-build progress, for the "Preparing catalog…" status.
     *
     * Reported as a running COUNT, not a percentage, and that is deliberate. An account's build
     * spans movies, series and live, and the streaming sync learns its row count only as the
     * response parses — so any total would either be invented or would visibly reset three times.
     * [IndexBuildProgress] can carry a total for callers that genuinely have one; this path does
     * not pretend to.
     */
    internal val buildProgress: StateFlow<Map<String, IndexBuildProgress>> = _buildProgress.asStateFlow()

    private fun advanceBuildProgress(provider: String, delta: Int) {
        _buildProgress.update { current ->
            val next = IndexBuildProgress((current[provider]?.itemsWritten ?: 0) + delta)
            current + (provider to (current[provider]?.mergeWith(next) ?: next))
        }
    }

    /** Called when an account's build finishes (or fails) so the status clears. */
    internal fun clearBuildProgress(provider: String) {
        _buildProgress.update { it - provider }
    }

    private fun connection(): SQLiteConnection = conn ?: MatchDbDriver.openConnection().also {
        // Stated, not inherited. Android 9+ turns on "compatibility WAL" by default unless an app
        // opts in or out, so the journal mode here was whatever the platform decided — and
        // `synchronous` defaults to FULL, meaning every COMMIT fsyncs. The interruptibility fix
        // that moved transactions from chunked(5_000) to tier batches of 100/300/500 therefore
        // multiplied the fsync count 10-50x on a 468k catalog (~94 commits became 937-4,684).
        //
        // NORMAL is safe here by design rather than by gamble: every table in this file is a
        // rebuildable cache (the schema below drops and recreates on migration, and mappings
        // re-pull from Supabase), so the worst case of a torn write is the rebuild that is already
        // the recovery path. StreamVault sets its journal mode explicitly for the same reason.
        runCatching {
            // WAL needs only SQLite 3.7, so it is safe at minSdk 24 — and it genuinely helps there:
            // Android's "compatibility WAL" default only arrived in Android 9, so API 24-27 were
            // running a rollback journal. `:memory:` ignores this and stays "memory", hence
            // runCatching rather than a version check.
            it.prepare("PRAGMA journal_mode = WAL").use { st -> st.step() }
            it.execSQL("PRAGMA synchronous = NORMAL")
        }
        // schema v2 adds items.poster (search cards). index tables are rebuildable caches,
        // mappings re-pull from Supabase — so migration is drop+recreate.
        val version = it.prepare("PRAGMA user_version").use { st -> if (st.step()) st.getLong(0) else 0L }
        if (version < 2) {
            it.execSQL("DROP TABLE IF EXISTS items"); it.execSQL("DROP TABLE IF EXISTS keys")
            it.execSQL("DROP TABLE IF EXISTS idx_meta"); it.execSQL("DROP TABLE IF EXISTS tmdb_map")
            it.execSQL("PRAGMA user_version = 2")
        }
        // v3 (P7, items 4-5): the index becomes the Xtream browse catalog — category_id/epg_id/
        // tv_archive on items, a categories table, and a LIVE kind. Index tables are rebuildable
        // (next warm-up refills); tmdb_map (the synced mappings) is untouched.
        if (version < 3) {
            it.execSQL("DROP TABLE IF EXISTS items"); it.execSQL("DROP TABLE IF EXISTS keys")
            it.execSQL("DROP TABLE IF EXISTS idx_meta"); it.execSQL("DROP TABLE IF EXISTS cats")
            it.execSQL("PRAGMA user_version = 3")
        }
        // v4: items.pos — categories browse in the PANEL'S list order, never alphabetized.
        // Same drop+recreate policy (rebuildable caches; tmdb_map untouched).
        if (version < 4) {
            it.execSQL("DROP TABLE IF EXISTS items"); it.execSQL("DROP TABLE IF EXISTS keys")
            it.execSQL("DROP TABLE IF EXISTS idx_meta"); it.execSQL("DROP TABLE IF EXISTS cats")
            it.execSQL("PRAGMA user_version = 4")
        }
        // v5: idx_meta.last_added_at — negatives in tmdb_map are only trusted if they postdate
        // the catalog's newest addition (a "not on this provider" verdict is falsified the
        // moment the provider adds titles). ALTER fails harmlessly when the v<4 step just
        // dropped the table — the CREATE below builds it with the column.
        if (version < 5) {
            // Introspects first: a no-op when the v<4 step just dropped the table (the CREATE below
            // builds it with the column) and when the column is present; a real failure propagates.
            it.ensureColumn("idx_meta", "last_added_at", "last_added_at INTEGER NOT NULL DEFAULT 0")
            it.execSQL("PRAGMA user_version = 5")
        }
        // v6 ships the id-mismatch override in verifyDecision: "not on this provider" verdicts
        // cached by the old rule can be junk-tmdb false negatives (a panel returning a constant
        // tmdb_id rejected its whole catalog). One-time purge — positives untouched, a negative
        // regenerates in a single resolve. Only 2..5 have a surviving tmdb_map (v<2 dropped it).
        if (version in 2..5) {
            it.execSQL("DELETE FROM tmdb_map WHERE sid IS NULL")
        }
        if (version < 6) it.execSQL("PRAGMA user_version = 6")
        // v7 (Overlay Build Spec P1): items.entity_id — the channel's deterministic identity
        // (IptvIdentity), materialized at index time so a saved live id can be re-bound to the
        // channel's CURRENT sid after the panel renumbers. Same drop+recreate policy (rebuildable
        // caches; tmdb_map untouched). live_sid_history, created below, is NOT dropped: it is the
        // durable memory of which identity each sid meant, and must outlive index rebuilds.
        if (version < 7) {
            it.execSQL("DROP TABLE IF EXISTS items"); it.execSQL("DROP TABLE IF EXISTS keys")
            it.execSQL("DROP TABLE IF EXISTS idx_meta"); it.execSQL("DROP TABLE IF EXISTS cats")
        }
        it.execSQL("CREATE TABLE IF NOT EXISTS items(provider TEXT NOT NULL, kind TEXT NOT NULL, sid INTEGER NOT NULL, name TEXT NOT NULL, year INTEGER, tmdb INTEGER, ext TEXT, poster TEXT, category_id TEXT, epg_id TEXT, tv_archive INTEGER NOT NULL DEFAULT 0, pos INTEGER NOT NULL DEFAULT 0, entity_id TEXT, PRIMARY KEY(provider, kind, sid)) WITHOUT ROWID")
        it.execSQL("CREATE INDEX IF NOT EXISTS items_tmdb ON items(provider, kind, tmdb)")
        it.execSQL("CREATE INDEX IF NOT EXISTS items_cat ON items(provider, kind, category_id, pos)")
        it.execSQL("CREATE INDEX IF NOT EXISTS items_entity ON items(provider, kind, entity_id)")
        it.execSQL("CREATE TABLE IF NOT EXISTS cats(provider TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, sort INTEGER NOT NULL, PRIMARY KEY(provider, kind, id)) WITHOUT ROWID")
        it.execSQL("CREATE TABLE IF NOT EXISTS keys(provider TEXT NOT NULL, kind TEXT NOT NULL, k TEXT NOT NULL, sid INTEGER NOT NULL, PRIMARY KEY(provider, kind, k, sid)) WITHOUT ROWID")
        it.execSQL("CREATE TABLE IF NOT EXISTS idx_meta(provider TEXT NOT NULL, kind TEXT NOT NULL, built_at INTEGER NOT NULL, item_count INTEGER NOT NULL, last_added_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(provider, kind)) WITHOUT ROWID")
        it.execSQL("CREATE TABLE IF NOT EXISTS tmdb_map(provider TEXT NOT NULL, kind TEXT NOT NULL, tmdb INTEGER NOT NULL, sid INTEGER, matched_name TEXT, updated_at INTEGER NOT NULL, synced INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(provider, kind, tmdb)) WITHOUT ROWID")
        // The FIRST identity each live sid was ever seen carrying (INSERT OR IGNORE, never
        // overwritten): a favourite saved as `live:{sid}` meant THAT channel, even if the panel later
        // hands the number to another one. Survives rebuilds; purged only with the account.
        it.execSQL("CREATE TABLE IF NOT EXISTS live_sid_history(provider TEXT NOT NULL, sid INTEGER NOT NULL, entity_id TEXT NOT NULL, seen_at INTEGER NOT NULL, PRIMARY KEY(provider, sid)) WITHOUT ROWID")
        if (version < 7) it.execSQL("PRAGMA user_version = 7")
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
                for (t in listOf("items", "keys", "idx_meta", "tmdb_map", "cats", "live_sid_history")) {
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
     * Indexed item count for a provider+kind, or null when never built — the playlist settings
     * sheet's catalog counts (item 8): the numbers already exist locally, zero API calls, exactly
     * how the reference client shows "Movies: 60000" (from data it already has).
     */
    suspend fun indexedCount(provider: String, kind: MatchKind): Int? = mutex.withLock {
        connection().prepare("SELECT item_count FROM idx_meta WHERE provider = ? AND kind = ?").use { st ->
            st.bindText(1, provider); st.bindText(2, kind.slug)
            if (st.step()) st.getLong(0).toInt() else null
        }
    }

    // --- browse catalog (P7, items 4-5): the hub reads Xtream sections from here --------------

    /** Replaces one provider+kind's category list (fetched alongside the catalog build). */
    suspend fun replaceCategories(provider: String, kind: MatchKind, categories: List<Pair<String, String>>) = mutex.withLock {
        val c = connection()
        c.execSQL("BEGIN IMMEDIATE")
        try {
            c.prepare("DELETE FROM cats WHERE provider = ? AND kind = ?").use { st ->
                st.bindText(1, provider); st.bindText(2, kind.slug); st.step()
            }
            c.prepare("INSERT OR REPLACE INTO cats(provider, kind, id, name, sort) VALUES(?,?,?,?,?)").use { st ->
                categories.forEachIndexed { i, (id, name) ->
                    st.reset()
                    st.bindText(1, provider); st.bindText(2, kind.slug)
                    st.bindText(3, id); st.bindText(4, name); st.bindLong(5, i.toLong())
                    st.step()
                }
            }
            c.execSQL("COMMIT")
        } catch (t: Throwable) {
            c.execSQL("ROLLBACK"); throw t
        }
    }

    /** The stored category list in panel order. Empty when the catalog was never built. */
    suspend fun categoriesFor(provider: String, kind: MatchKind): List<Pair<String, String>> = mutex.withLock {
        connection().prepare("SELECT id, name FROM cats WHERE provider = ? AND kind = ? ORDER BY sort").use { st ->
            st.bindText(1, provider); st.bindText(2, kind.slug)
            val out = ArrayList<Pair<String, String>>()
            while (st.step()) out.add(st.getText(0) to st.getText(1))
            out
        }
    }

    /**
     * One window of a category (or the whole kind when [categoryId] is null), name-ordered.
     * THE item-5 read: the hub asks for [limit] rows from [offset] instead of materializing a
     * whole category — the covering items_cat index makes it a range scan.
     */
    suspend fun itemsFor(provider: String, kind: MatchKind, categoryId: String?, offset: Int, limit: Int): List<IndexedItem> = mutex.withLock {
        val sql = if (categoryId == null)
            "SELECT sid, name, year, tmdb, ext, poster, category_id, epg_id, tv_archive FROM items WHERE provider = ? AND kind = ? ORDER BY pos, sid LIMIT ? OFFSET ?"
        else
            "SELECT sid, name, year, tmdb, ext, poster, category_id, epg_id, tv_archive FROM items WHERE provider = ? AND kind = ? AND category_id = ? ORDER BY pos, sid LIMIT ? OFFSET ?"
        connection().prepare(sql).use { st ->
            st.bindText(1, provider); st.bindText(2, kind.slug)
            var i = 3
            if (categoryId != null) st.bindText(i++, categoryId)
            st.bindLong(i++, limit.toLong()); st.bindLong(i, offset.toLong())
            readItems(st)
        }
    }

    /** A single item row by sid — the registry's cold-start fallback (item 5). */
    suspend fun itemRow(provider: String, kind: MatchKind, sid: Int): IndexedItem? = mutex.withLock {
        connection().prepare("SELECT sid, name, year, tmdb, ext, poster, category_id, epg_id, tv_archive FROM items WHERE provider = ? AND kind = ? AND sid = ?").use { st ->
            st.bindText(1, provider); st.bindText(2, kind.slug); st.bindLong(3, sid.toLong())
            readItems(st).firstOrNull()
        }
    }

    /**
     * Replaces the whole index for one provider+kind. Chunked transactions keep the write
     * lock short so concurrent probes interleave; meta row is written LAST so a crashed
     * rebuild reads as stale, not as complete.
     */
    /**
     * Every distinct `epg_channel_id` this provider's LIVE lineup carries — the allow-set for a
     * whole-guide XMLTV ingest, so a guide covering thousands of channels only stores rows for ours.
     *
     * The Xtream counterpart of [IptvContentDb.distinctTvgIds]: M3U and Stalker lineups live in
     * IptvContentDb, Xtream's lives here, and the ingest needs whichever one owns the account.
     * Panels fill this field very unevenly (6% on Starshare, measured), so an empty result is a
     * normal answer meaning "this panel cannot be matched by id" — not an error.
     */
    /**
     * The sid the channel a saved live id meant carries in the CURRENT catalog, or null when this
     * device cannot say (never indexed the playlist, or the identity is no longer in it).
     *
     * A saved id carries the sid the channel had when it was saved. Panels renumber (commit
     * 6c622d49), so the sid is a hint: [live_sid_history] remembers which identity that number
     * FIRST meant, and the current row carrying the same identity is the channel now. This also
     * covers a number handed to a different channel afterwards — the original identity wins while it
     * still exists. When it does not, the caller decides what a bare sid is worth.
     *
     * Duplicates (metadata-identical rows share an identity) resolve to the saved sid if it is one of
     * them, else deterministically to the lowest — indistinguishable streams, so any is the channel.
     */
    suspend fun resolveLiveSid(provider: String, savedSid: Int): Int? = mutex.withLock {
        val c = connection()
        val entity = c.prepare("SELECT entity_id FROM live_sid_history WHERE provider = ? AND sid = ?").use { st ->
            st.bindText(1, provider); st.bindLong(2, savedSid.toLong())
            if (st.step()) st.getText(0) else null
        } ?: c.prepare("SELECT entity_id FROM items WHERE provider = ? AND kind = ? AND sid = ?").use { st ->
            // No history for this sid (indexed before v7): trust the current row's own identity.
            st.bindText(1, provider); st.bindText(2, MatchKind.LIVE.slug); st.bindLong(3, savedSid.toLong())
            if (st.step() && !st.isNull(0)) st.getText(0) else null
        } ?: return@withLock null
        c.prepare("SELECT sid FROM items WHERE provider = ? AND kind = ? AND entity_id = ? ORDER BY sid").use { st ->
            st.bindText(1, provider); st.bindText(2, MatchKind.LIVE.slug); st.bindText(3, entity)
            var lowest: Int? = null
            while (st.step()) {
                val sid = st.getLong(0).toInt()
                if (sid == savedSid) return@withLock sid
                if (lowest == null) lowest = sid
            }
            lowest
        }
    }

    suspend fun liveEpgIds(provider: String): List<String> = mutex.withLock {
        connection().prepare(
            "SELECT DISTINCT epg_id FROM items WHERE provider = ? AND kind = ? " +
                "AND epg_id IS NOT NULL AND epg_id <> ''"
        ).use { st ->
            st.bindText(1, provider); st.bindText(2, MatchKind.LIVE.slug)
            val out = ArrayList<String>()
            while (st.step()) if (!st.isNull(0)) out.add(st.getText(0))
            out
        }
    }

    /**
     * One live channel's `epg_channel_id`, or null when the panel left it blank. The guide resolves
     * by stream id but the stored guide is keyed by channel id, so this is the join between them.
     */
    suspend fun liveEpgIdFor(provider: String, sid: Int): String? = mutex.withLock {
        connection().prepare(
            "SELECT epg_id FROM items WHERE provider = ? AND kind = ? AND sid = ?"
        ).use { st ->
            st.bindText(1, provider); st.bindText(2, MatchKind.LIVE.slug); st.bindLong(3, sid.toLong())
            if (st.step() && !st.isNull(0)) st.getText(0).takeIf { it.isNotBlank() } else null
        }
    }

    suspend fun rebuild(provider: String, kind: MatchKind, itemsIn: List<IndexedItem>) {
        val items = itemsIn.mapIndexed { i, raw -> if (raw.pos == i) raw else raw.copy(pos = i) }
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
        writeMeta(provider, kind, items.size, addedCount = items.size)
    }

    /**
     * Incrementally reconciles the index with a fresh catalog fetch: unchanged rows are
     * validated by fingerprint only (no re-normalization, no rewrite), new/renamed rows are
     * (re)indexed, vanished rows deleted. Falls back to [rebuild] when the index is empty or
     * the catalog reshuffled wholesale. built_at is bumped LAST so a crashed sync reads as
     * stale and re-runs (idempotent).
     */
    suspend fun sync(provider: String, kind: MatchKind, itemsIn: List<IndexedItem>): SyncStats {
        val items = itemsIn.mapIndexed { i, raw -> if (raw.pos == i) raw else raw.copy(pos = i) }
        // One streaming pass over the existing rows -> primitive (sid, fingerprint) arrays,
        // PK-ordered. ~1.4MB for a 175k catalog; never materializes the old names in heap.
        var sids = IntArray(4_096)
        var fps = IntArray(4_096)
        var count = 0
        mutex.withLock {
            connection().prepare(
                "SELECT sid, name, year, tmdb, ext, poster, category_id, epg_id, tv_archive, pos FROM items WHERE provider = ? AND kind = ? ORDER BY sid"
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
                        categoryId = if (st.isNull(6)) null else st.getText(6),
                        epgId = if (st.isNull(7)) null else st.getText(7),
                        hasArchive = st.getLong(8) > 0,
                        pos = st.getLong(9).toInt(),
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
        writeMeta(provider, kind, items.size, addedCount = diff.upserts.size - diff.changedSids.size)
        return SyncStats(
            added = diff.upserts.size - diff.changedSids.size,
            changed = diff.changedSids.size,
            removed = diff.goneSids.size,
            total = items.size,
        )
    }

    /**
     * Opens a streaming sync: the caller feeds catalog rows one at a time as the response parses
     * ([SyncSession.accept]), and the session flushes to the DB every [SyncSession.FLUSH_CHUNK]
     * rows — so peak heap is one chunk (~5k items), never the whole catalog. Finalization
     * (vanished-row deletes + the built_at bump) happens ONLY in [SyncSession.finish], which the
     * caller must not reach on a truncated body; rows applied before an abort are harmless
     * (idempotent INSERT OR REPLACE, meta untouched, next sync re-runs).
     *
     * Semantics vs [sync]: identical, minus the wholesale-reshuffle rebuild shortcut — streaming
     * can't know the diff size up front, so a renumbered catalog takes the (correct, chunked)
     * incremental path instead of a clean rebuild. On an empty index this IS a streamed rebuild:
     * any leftover rows are wiped here, and every accepted row is an upsert.
     */
    suspend fun beginSync(provider: String, kind: MatchKind): SyncSession {
        var sids = IntArray(4_096)
        var fps = IntArray(4_096)
        var count = 0
        mutex.withLock {
            connection().prepare(
                "SELECT sid, name, year, tmdb, ext, poster, category_id, epg_id, tv_archive, pos FROM items WHERE provider = ? AND kind = ? ORDER BY sid"
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
                        categoryId = if (st.isNull(6)) null else st.getText(6),
                        epgId = if (st.isNull(7)) null else st.getText(7),
                        hasArchive = st.getLong(8) > 0,
                        pos = st.getLong(9).toInt(),
                    )
                    count++
                }
            }
        }
        if (count == 0) {
            // First build (or a crashed one): clear any leftovers so the stream is a clean rebuild.
            // Deleting idx_meta here also keeps the caller's "empty list on a first build is OK"
            // check working — builtAt reads null until finish() writes it.
            mutex.withLock {
                val c = connection()
                c.execSQL("BEGIN IMMEDIATE")
                try {
                    for (table in listOf("items", "keys", "idx_meta")) {
                        c.prepare("DELETE FROM $table WHERE provider = ? AND kind = ?").use { st ->
                            st.bindText(1, provider); st.bindText(2, kind.slug); st.step()
                        }
                    }
                    c.execSQL("COMMIT")
                } catch (t: Throwable) {
                    c.execSQL("ROLLBACK"); throw t
                }
            }
        }
        return SyncSession(provider, kind, sids.copyOf(count), fps.copyOf(count))
    }

    /** One in-flight streaming sync. Not thread-safe: feed it from the single response-reader thread. */
    class SyncSession internal constructor(
        private val provider: String,
        private val kind: MatchKind,
        private val existingSids: IntArray,
        private val existingFps: IntArray,
    ) {
        private val seen = BooleanArray(existingSids.size)
        private val pending = ArrayList<IndexedItem>(FLUSH_CHUNK)
        private val pendingChanged = ArrayList<Int>()
        private var fetched = 0
        private var added = 0
        private var changed = 0

        /**
         * Accepts one parsed catalog row. Non-suspend so the transport's reader callback can call
         * it directly; a full chunk drains via [kotlinx.coroutines.runBlocking] on that same IO
         * thread — the exact idiom [com.nuvio.app.features.iptv.M3UClient]'s IngestCollector
         * established (never the main thread). Duplicate sids: first occurrence decides, like
         * [diffCatalog].
         */
        fun accept(raw: IndexedItem) {
            fetched++
            // Stamp arrival order — the panel's list order IS the browse order (never sorted).
            val item = raw.copy(pos = fetched - 1)
            val i = existingSids.ascIndexOf(item.sid)
            if (i < 0) {
                pending += item
                added++
            } else if (!seen[i]) {
                seen[i] = true
                if (existingFps[i] != item.fp()) {
                    pending += item
                    pendingChanged += item.sid
                    changed++
                }
            }
            if (pending.size >= FLUSH_CHUNK) kotlinx.coroutines.runBlocking { flush() }
        }

        private suspend fun flush() {
            if (pending.isEmpty()) return
            // Renamed rows' old name-keys must go before their new keys land (same order sync()
            // guarantees via its up-front delete).
            if (pendingChanged.isNotEmpty()) {
                mutex.withLock {
                    val c = connection()
                    c.execSQL("BEGIN IMMEDIATE")
                    try {
                        for (chunk in pendingChanged.chunked(500)) {
                            val ph = chunk.joinToString(",") { "?" }
                            c.prepare("DELETE FROM keys WHERE provider = ? AND kind = ? AND sid IN ($ph)").use { st ->
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
            }
            insertItems(provider, kind, pending)
            pending.clear()
            pendingChanged.clear()
        }

        /**
         * Flushes the tail, deletes rows the fetch no longer contains, and bumps built_at LAST.
         * An empty fetch against an existing index is treated as a panel glitch — nothing is
         * deleted and built_at stays stale so the next window retries (mirrors [sync]).
         */
        suspend fun finish(): SyncStats {
            if (fetched == 0 && existingSids.isNotEmpty()) return SyncStats(0, 0, 0, existingSids.size)
            flush()
            val gone = ArrayList<Int>()
            for (i in existingSids.indices) if (!seen[i]) gone += existingSids[i]
            if (gone.isNotEmpty()) {
                mutex.withLock {
                    val c = connection()
                    c.execSQL("BEGIN IMMEDIATE")
                    try {
                        for (chunk in gone.chunked(500)) {
                            val ph = chunk.joinToString(",") { "?" }
                            for (table in listOf("keys", "items")) {
                                c.prepare("DELETE FROM $table WHERE provider = ? AND kind = ? AND sid IN ($ph)").use { st ->
                                    st.bindText(1, provider); st.bindText(2, kind.slug)
                                    chunk.forEachIndexed { i, sid -> st.bindLong(i + 3, sid.toLong()) }
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
            writeMeta(provider, kind, fetched, addedCount = added)
            return SyncStats(added = added, changed = changed, removed = gone.size, total = fetched)
        }

        private companion object {
            const val FLUSH_CHUNK = 5_000
        }
    }

    /**
     * Writes the index in tier-sized transactions, yielding between them.
     *
     * The hub reads its rows from this same database, so every statement here is time the UI
     * cannot read. Measured on a 2 GB TV box (v1.4.30): at 5,000 rows a batch — an
     * UPDATE-or-INSERT plus one INSERT per normalized key, so ~25,000 statements — the build held
     * a worker at 97% CPU for minutes while the hub's category reads queued behind it. Categories
     * read as "not loading" and only recovered when the build ended.
     *
     * Both halves are StreamVault's shape (CatalogSyncRuntimeProfile): the batch is
     * [MemoryTierPolicy.indexBatchSize] — and [AppMemory.effectiveTier] shrinks it again under
     * pressure — and the loop suspends between batches so a reader waiting on [mutex] gets in and
     * the dispatcher is not monopolized. This does not make indexing faster; it makes it
     * interruptible, which is what "the app feels broken" actually was.
     */
    private suspend fun insertItems(provider: String, kind: MatchKind, items: List<IndexedItem>) {
        val batch = MemoryTierPolicy.indexBatchSize(AppMemory.effectiveTier())
        for (chunk in items.chunked(batch)) {
            yield()
            // Normalised OUTSIDE the lock and the transaction. TitleNormalizer.keysOf is the
            // CPU-heavy half of indexing (4-8 Unicode NFD folds and dozens of regex passes per
            // item); running it inside `mutex.withLock` made every reader — i.e. the UI — queue
            // behind it. Sorted by (k, sid) because `keys` is WITHOUT ROWID, so its primary key IS
            // the storage B-tree and catalog-order inserts land at random leaves. See sortedKeyRows.
            val keyRows = sortedKeyRows(chunk)
            mutex.withLock {
                val c = connection()
                c.execSQL("BEGIN IMMEDIATE")
                try {
                    // UPDATE-then-INSERT rather than INSERT OR REPLACE: an existing row's poster
                    // must survive an incoming null (B-style panels ship empty bulk icons; the
                    // stored value may be PosterEnricher's work). COALESCE keeps non-null incoming
                    // icons flowing. Same two-step shape as the TV twin.
                    //
                    // NOT rewritten as a single SQLite UPSERT, though it reads like the obvious
                    // win: UPSERT needs SQLite >= 3.24, which arrives with Android 11 (API 30), and
                    // minSdk here is 24. AndroidSQLiteDriver wraps the FRAMEWORK SQLite, so on an
                    // API 24-29 device the statement fails to prepare at all. Unit tests cannot
                    // catch it either — they run BundledSQLiteDriver, which is modern. The TV twin
                    // carries the same warning ("Framework SQLite on the oldest supported TVs
                    // predates UPSERT, hence two steps"); heed it before trying this again.
                    c.prepare("UPDATE items SET name=?, year=?, tmdb=?, ext=?, poster=COALESCE(?, poster), category_id=?, epg_id=?, tv_archive=?, pos=?, entity_id=? WHERE provider=? AND kind=? AND sid=?").use { upd ->
                        c.prepare("SELECT changes()").use { chg ->
                            c.prepare("INSERT OR REPLACE INTO items(provider, kind, sid, name, year, tmdb, ext, poster, category_id, epg_id, tv_archive, pos, entity_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)").use { ins ->
                                for (it in chunk) {
                                    // Live rows carry their identity; VOD/series identity is a different problem (TMDB).
                                    val entity = if (kind == MatchKind.LIVE) IptvIdentity.entityId(provider, it.name, it.epgId) else null
                                    upd.reset()
                                    upd.bindText(1, it.name)
                                    if (it.year != null) upd.bindLong(2, it.year.toLong()) else upd.bindNull(2)
                                    if (it.tmdb != null) upd.bindLong(3, it.tmdb.toLong()) else upd.bindNull(3)
                                    if (it.ext != null) upd.bindText(4, it.ext) else upd.bindNull(4)
                                    if (it.poster != null) upd.bindText(5, it.poster) else upd.bindNull(5)
                                    if (it.categoryId != null) upd.bindText(6, it.categoryId) else upd.bindNull(6)
                                    if (it.epgId != null) upd.bindText(7, it.epgId) else upd.bindNull(7)
                                    upd.bindLong(8, if (it.hasArchive) 1L else 0L)
                                    upd.bindLong(9, it.pos.toLong())
                                    if (entity != null) upd.bindText(10, entity) else upd.bindNull(10)
                                    upd.bindText(11, provider); upd.bindText(12, kind.slug); upd.bindLong(13, it.sid.toLong())
                                    upd.step()
                                    chg.reset()
                                    val updated = chg.step() && chg.getLong(0) > 0
                                    if (!updated) {
                                        ins.reset()
                                        ins.bindText(1, provider); ins.bindText(2, kind.slug); ins.bindLong(3, it.sid.toLong())
                                        ins.bindText(4, it.name)
                                        if (it.year != null) ins.bindLong(5, it.year.toLong()) else ins.bindNull(5)
                                        if (it.tmdb != null) ins.bindLong(6, it.tmdb.toLong()) else ins.bindNull(6)
                                        if (it.ext != null) ins.bindText(7, it.ext) else ins.bindNull(7)
                                        if (it.poster != null) ins.bindText(8, it.poster) else ins.bindNull(8)
                                        if (it.categoryId != null) ins.bindText(9, it.categoryId) else ins.bindNull(9)
                                        if (it.epgId != null) ins.bindText(10, it.epgId) else ins.bindNull(10)
                                        ins.bindLong(11, if (it.hasArchive) 1L else 0L)
                                        ins.bindLong(12, it.pos.toLong())
                                        if (entity != null) ins.bindText(13, entity) else ins.bindNull(13)
                                        ins.step()
                                    }
                                }
                            }
                        }
                    }
                    c.prepare("INSERT OR REPLACE INTO keys(provider, kind, k, sid) VALUES(?,?,?,?)").use { st ->
                        for (row in keyRows) {
                            st.reset()
                            st.bindText(1, provider); st.bindText(2, kind.slug); st.bindText(3, row.key); st.bindLong(4, row.sid.toLong())
                            st.step()
                        }
                    }
                    if (kind == MatchKind.LIVE) {
                        // First-seen identity per sid, never overwritten (see live_sid_history).
                        c.prepare("INSERT OR IGNORE INTO live_sid_history(provider, sid, entity_id, seen_at) VALUES(?,?,?,?)").use { st ->
                            val seenAt = now()
                            for (item in chunk) {
                                st.reset()
                                st.bindText(1, provider); st.bindLong(2, item.sid.toLong())
                                st.bindText(3, IptvIdentity.entityId(provider, item.name, item.epgId)); st.bindLong(4, seenAt)
                                st.step()
                            }
                        }
                    }
                    c.execSQL("COMMIT")
                } catch (t: Throwable) {
                    c.execSQL("ROLLBACK"); throw t
                }
            }
            // After the batch is durable, so the number on screen never runs ahead of what
            // survives a kill mid-build.
            advanceBuildProgress(provider, chunk.size)
        }
    }

    /**
     * PosterEnricher's write-back: artwork learned from get_vod_info/get_series_info for a row
     * whose bulk list carried no icon. Survives syncs because [itemFp] ignores poster and the
     * sync write coalesces incoming nulls over it.
     */
    suspend fun updatePoster(provider: String, kind: MatchKind, sid: Int, poster: String) {
        mutex.withLock {
            connection().prepare("UPDATE items SET poster = ? WHERE provider = ? AND kind = ? AND sid = ?").use { st ->
                st.bindText(1, poster); st.bindText(2, provider); st.bindText(3, kind.slug); st.bindLong(4, sid.toLong())
                st.step()
            }
        }
    }

    private suspend fun writeMeta(provider: String, kind: MatchKind, itemCount: Int, addedCount: Int) {
        mutex.withLock {
            val c = connection()
            val previousLastAdded = c.prepare("SELECT last_added_at FROM idx_meta WHERE provider = ? AND kind = ?").use { st ->
                st.bindText(1, provider); st.bindText(2, kind.slug)
                if (st.step()) st.getLong(0) else 0L
            }
            // Catalog gained titles -> every older negative verdict is suspect (see lastAddedAt).
            val lastAdded = if (addedCount > 0) now() else previousLastAdded
            c.prepare("INSERT OR REPLACE INTO idx_meta(provider, kind, built_at, item_count, last_added_at) VALUES(?,?,?,?,?)").use { st ->
                st.bindText(1, provider); st.bindText(2, kind.slug); st.bindLong(3, now()); st.bindLong(4, itemCount.toLong()); st.bindLong(5, lastAdded)
                st.step()
            }
        }
    }

    /**
     * When this provider+kind last GAINED catalog items (0 = never observed). Tier-2 negative
     * mappings ("not on this provider") are only honored when they postdate this — a panel that
     * added titles invalidates every older miss, locally AND ones synced from other devices.
     */
    suspend fun lastAddedAt(provider: String, kind: MatchKind): Long = mutex.withLock {
        connection().prepare("SELECT last_added_at FROM idx_meta WHERE provider = ? AND kind = ?").use { st ->
            st.bindText(1, provider); st.bindText(2, kind.slug)
            if (st.step()) st.getLong(0) else 0L
        }
    }

    /** Manual re-match reset: distrust every negative verdict recorded before now. */
    suspend fun distrustNegativeMappings(provider: String) = mutex.withLock {
        connection().prepare("UPDATE idx_meta SET last_added_at = ? WHERE provider = ?").use { st ->
            st.bindLong(1, now()); st.bindText(2, provider)
            st.step()
        }
    }

    /** Substring name search over the indexed catalog — backs the IPTV rows in Search. */
    suspend fun searchByName(provider: String, kind: MatchKind, query: String, limit: Int): List<IndexedItem> = mutex.withLock {
        connection().prepare(
            "SELECT sid, name, year, tmdb, ext, poster, category_id, epg_id, tv_archive FROM items WHERE provider = ? AND kind = ? AND name LIKE '%' || ? || '%' LIMIT ?"
        ).use { st ->
            st.bindText(1, provider); st.bindText(2, kind.slug); st.bindText(3, query); st.bindLong(4, limit.toLong())
            readItems(st)
        }
    }

    /** All items indexed under a normalized key. */
    suspend fun probe(provider: String, kind: MatchKind, key: String): List<IndexedItem> = mutex.withLock {
        connection().prepare(
            "SELECT i.sid, i.name, i.year, i.tmdb, i.ext, i.poster, i.category_id, i.epg_id, i.tv_archive FROM keys x JOIN items i ON i.provider = x.provider AND i.kind = x.kind AND i.sid = x.sid WHERE x.provider = ? AND x.kind = ? AND x.k = ?"
        ).use { st ->
            st.bindText(1, provider); st.bindText(2, kind.slug); st.bindText(3, key)
            readItems(st)
        }
    }

    /** Tier-1: items whose bulk-list tmdb id already matches. */
    suspend fun byTmdb(provider: String, kind: MatchKind, tmdb: Int): List<IndexedItem> = mutex.withLock {
        connection().prepare("SELECT sid, name, year, tmdb, ext, poster, category_id, epg_id, tv_archive FROM items WHERE provider = ? AND kind = ? AND tmdb = ?").use { st ->
            st.bindText(1, provider); st.bindText(2, kind.slug); st.bindLong(3, tmdb.toLong())
            readItems(st)
        }
    }

    suspend fun item(provider: String, kind: MatchKind, sid: Int): IndexedItem? = mutex.withLock {
        connection().prepare("SELECT sid, name, year, tmdb, ext, poster, category_id, epg_id, tv_archive FROM items WHERE provider = ? AND kind = ? AND sid = ?").use { st ->
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
                    categoryId = if (st.isNull(6)) null else st.getText(6),
                    epgId = if (st.isNull(7)) null else st.getText(7),
                    hasArchive = st.getLong(8) > 0,
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
