package com.nuvio.app.features.epg

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import com.nuvio.app.features.iptv.content.EpgProgrammeRow
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private inline fun <R> SQLiteStatement.use(block: (SQLiteStatement) -> R): R =
    try { block(this) } finally { close() }

/** One channel row of the backend's channels-index (a display name of an EPG channel). */
internal data class EpgIndexRow(val slug: String, val epgId: String, val name: String)

/** A provider channel's persisted mapping onto a canonical EPG id. */
internal data class EpgMappingRow(val streamId: Int, val epgId: String, val tier: String)

/**
 * Opens the on-disk SQLite database backing the EPG mirror. Platform actuals pick the
 * driver, mirroring MatchDbDriver: AndroidSQLiteDriver on Android, BundledSQLiteDriver on
 * iOS (compiled-in SQLite — the framework's -lsqlite3 doesn't reliably propagate).
 */
internal expect object EpgMirrorDbDriver {
    fun openConnection(): SQLiteConnection
}

/**
 * Local store for the backend's EPG mirror (see nuvio-backend supabase/functions/epg-sync):
 * the channels index of every mirrored source, the programme window for the feeds we chose
 * to download, and the provider-channel → EPG-id mappings computed by [EpgChannelIndex].
 * KMP twin of NuvioTV's core/epg/EpgMirrorDb — same tables, same crash discipline (meta
 * stamped last; everything here is a rebuildable cache).
 *
 * EPG ids are stored through [com.nuvio.app.features.iptv.epg.normalizeChannelId] by the
 * callers so lookups are plain equality.
 */
internal object EpgMirrorDb {

    private val mutex = Mutex()
    private var conn: SQLiteConnection? = null

    private fun connection(): SQLiteConnection = conn ?: EpgMirrorDbDriver.openConnection().also {
        val version = it.prepare("PRAGMA user_version").use { st -> if (st.step()) st.getLong(0) else 0L }
        if (version < 1) {
            for (t in listOf("index_channels", "programmes", "mapping", "meta")) {
                it.execSQL("DROP TABLE IF EXISTS $t")
            }
            it.execSQL("PRAGMA user_version = 1")
        }
        it.execSQL("CREATE TABLE IF NOT EXISTS index_channels(slug TEXT NOT NULL, epg_id TEXT NOT NULL, name TEXT NOT NULL)")
        it.execSQL("CREATE INDEX IF NOT EXISTS index_channels_slug ON index_channels(slug)")
        it.execSQL("CREATE TABLE IF NOT EXISTS programmes(epg_id TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, title TEXT NOT NULL, desc TEXT)")
        it.execSQL("CREATE INDEX IF NOT EXISTS programmes_lookup ON programmes(epg_id, start_ms)")
        it.execSQL("CREATE TABLE IF NOT EXISTS mapping(provider_key TEXT NOT NULL, stream_id INTEGER NOT NULL, epg_id TEXT NOT NULL, tier TEXT NOT NULL, updated_ms INTEGER NOT NULL, PRIMARY KEY(provider_key, stream_id)) WITHOUT ROWID")
        it.execSQL("CREATE INDEX IF NOT EXISTS mapping_epg ON mapping(epg_id)")
        it.execSQL("CREATE TABLE IF NOT EXISTS meta(k TEXT NOT NULL PRIMARY KEY, v TEXT NOT NULL) WITHOUT ROWID")
        // The published source list, kept so the region picker can be shown without a re-fetch
        // (and so a selection can be resolved to slugs offline).
        it.execSQL("CREATE TABLE IF NOT EXISTS sources(slug TEXT NOT NULL PRIMARY KEY, label TEXT NOT NULL, countries TEXT, channel_count INTEGER NOT NULL) WITHOUT ROWID")
        conn = it
    }

    // --- meta ----------------------------------------------------------------

    suspend fun meta(key: String): String? = mutex.withLock {
        connection().prepare("SELECT v FROM meta WHERE k = ?").use { st ->
            st.bindText(1, key)
            if (st.step()) st.getText(0) else null
        }
    }

    suspend fun setMeta(key: String, value: String): Unit = mutex.withLock {
        connection().prepare("INSERT OR REPLACE INTO meta(k, v) VALUES(?, ?)").use { st ->
            st.bindText(1, key); st.bindText(2, value); st.step()
        }
    }

    /** Meta keys starting with [prefix] — used to clear every account's stamp at once. */
    suspend fun metaKeysWithPrefix(prefix: String): List<String> = mutex.withLock {
        connection().prepare("SELECT k FROM meta WHERE k LIKE ? || '%'").use { st ->
            st.bindText(1, prefix)
            buildList { while (st.step()) add(st.getText(0)) }
        }
    }

    suspend fun deleteMeta(key: String): Unit = mutex.withLock {
        connection().prepare("DELETE FROM meta WHERE k = ?").use { st ->
            st.bindText(1, key); st.step()
        }
    }

    // --- published sources (region picker) --------------------------------------

    /** Full replace of the published source list (one row per mirror source). */
    suspend fun replaceSources(rows: List<EpgSourceInfo>): Unit = mutex.withLock {
        val c = connection()
        c.execSQL("BEGIN IMMEDIATE")
        try {
            c.execSQL("DELETE FROM sources")
            c.prepare("INSERT OR REPLACE INTO sources(slug, label, countries, channel_count) VALUES(?,?,?,?)").use { st ->
                for (r in rows) {
                    st.reset()
                    st.bindText(1, r.slug); st.bindText(2, r.label)
                    if (r.countries != null) st.bindText(3, r.countries) else st.bindNull(3)
                    st.bindLong(4, r.channelCount.toLong())
                    st.step()
                }
            }
            c.execSQL("COMMIT")
        } catch (t: Throwable) {
            c.execSQL("ROLLBACK"); throw t
        }
    }

    suspend fun sourcesAreEmpty(): Boolean = mutex.withLock {
        connection().prepare("SELECT 1 FROM sources LIMIT 1").use { st -> !st.step() }
    }

    suspend fun sources(): List<EpgSourceInfo> = mutex.withLock {
        connection().prepare("SELECT slug, label, countries, channel_count FROM sources").use { st ->
            buildList {
                while (st.step()) {
                    add(
                        EpgSourceInfo(
                            slug = st.getText(0),
                            label = st.getText(1),
                            countries = if (st.isNull(2)) null else st.getText(2),
                            channelCount = st.getLong(3).toInt(),
                        )
                    )
                }
            }
        }
    }

    // --- channels index -------------------------------------------------------

    /** Full replace of the channels index (small: one row per display name). */
    suspend fun replaceIndex(rows: List<EpgIndexRow>): Unit = mutex.withLock {
        val c = connection()
        c.execSQL("BEGIN IMMEDIATE")
        try {
            c.execSQL("DELETE FROM index_channels")
            c.prepare("INSERT INTO index_channels(slug, epg_id, name) VALUES(?,?,?)").use { st ->
                for (r in rows) {
                    st.reset()
                    st.bindText(1, r.slug); st.bindText(2, r.epgId); st.bindText(3, r.name)
                    st.step()
                }
            }
            c.execSQL("COMMIT")
        } catch (t: Throwable) {
            c.execSQL("ROLLBACK"); throw t
        }
    }

    /** Test-only: observes each shadow-insert batch size, to prove the streamed batch stays bounded
     *  no matter how large the catalog is. Null (no-op) in production. */
    internal var indexInsertObserver: ((Int) -> Unit)? = null

    /**
     * Shadow-swap channels-index refresh, driven by the repository's PHASE 2 (promote-from-staging).
     *
     * Each call takes and RELEASES the mutex on its own, so a guide read can interleave BETWEEN
     * batches — the network stream (phase 1) never reaches this table and never holds this lock. The
     * live `index_channels` keeps serving until [commitIndexShadow]; if the caller aborts, it calls
     * [dropIndexShadow] and the previous generation stands. Overlapping ingests are prevented upstream
     * (the repository single-flights `ensureFresh`), so there is at most one open shadow at a time.
     */
    suspend fun beginIndexShadow(): Unit = mutex.withLock {
        val c = connection()
        c.execSQL("DROP TABLE IF EXISTS index_channels_shadow")
        c.execSQL("CREATE TABLE index_channels_shadow(slug TEXT NOT NULL, epg_id TEXT NOT NULL, name TEXT NOT NULL)")
    }

    /** Insert one bounded batch into the shadow (short lock hold; the caller clears its buffer after). */
    suspend fun insertIndexShadow(rows: List<EpgIndexRow>): Unit = mutex.withLock {
        if (rows.isEmpty()) return@withLock
        indexInsertObserver?.invoke(rows.size)
        val c = connection()
        c.execSQL("BEGIN IMMEDIATE")
        try {
            c.prepare("INSERT INTO index_channels_shadow(slug, epg_id, name) VALUES(?,?,?)").use { st ->
                for (r in rows) {
                    st.reset()
                    st.bindText(1, r.slug); st.bindText(2, r.epgId); st.bindText(3, r.name)
                    st.step()
                }
            }
            c.execSQL("COMMIT")
        } catch (t: Throwable) {
            c.execSQL("ROLLBACK"); throw t
        }
    }

    /** Atomically swap the filled shadow in as the live index (and rebuild its slug lookup). */
    suspend fun commitIndexShadow(): Unit = mutex.withLock {
        val c = connection()
        c.execSQL("BEGIN IMMEDIATE")
        try {
            c.execSQL("DROP TABLE IF EXISTS index_channels")
            c.execSQL("ALTER TABLE index_channels_shadow RENAME TO index_channels")
            c.execSQL("CREATE INDEX IF NOT EXISTS index_channels_slug ON index_channels(slug)")
            c.execSQL("COMMIT")
        } catch (t: Throwable) {
            c.execSQL("ROLLBACK"); throw t
        }
    }

    /** Drops an orphaned shadow (an aborted/cancelled refresh) without touching the live index. */
    suspend fun dropIndexShadow(): Unit = mutex.withLock {
        connection().execSQL("DROP TABLE IF EXISTS index_channels_shadow")
    }

    /** Stream every index row (build the transient [EpgChannelIndex] without a big copy). */
    suspend fun forEachIndexRow(block: (EpgIndexRow) -> Unit): Unit = mutex.withLock {
        connection().prepare("SELECT slug, epg_id, name FROM index_channels").use { st ->
            while (st.step()) block(EpgIndexRow(st.getText(0), st.getText(1), st.getText(2)))
        }
    }

    suspend fun indexIsEmpty(): Boolean = mutex.withLock {
        connection().prepare("SELECT 1 FROM index_channels LIMIT 1").use { st -> !st.step() }
    }

    /** Display name of one EPG channel (first index row wins). */
    suspend fun indexNameFor(epgId: String): String? = mutex.withLock {
        connection().prepare("SELECT name FROM index_channels WHERE epg_id = ? LIMIT 1").use { st ->
            st.bindText(1, epgId)
            if (st.step()) st.getText(0) else null
        }
    }

    // --- programmes -----------------------------------------------------------

    /**
     * Starts a programme-window refresh: creates the SHADOW table the chunk inserts fill.
     * Readers keep serving the live `programmes` table until [commitProgrammesRefresh] swaps
     * atomically — the old up-front DELETE left the guide empty for the whole streamed feed
     * download. An orphaned shadow (crash mid-refresh) is dropped here on the next run.
     */
    suspend fun beginProgrammesRefresh(): Unit = mutex.withLock {
        val c = connection()
        c.execSQL("DROP TABLE IF EXISTS programmes_shadow")
        c.execSQL("CREATE TABLE programmes_shadow(epg_id TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, title TEXT NOT NULL, desc TEXT)")
    }

    /** Swaps the filled shadow in as the live programme table (single instant transaction). */
    suspend fun commitProgrammesRefresh(): Unit = mutex.withLock {
        val c = connection()
        c.execSQL("BEGIN IMMEDIATE")
        try {
            c.execSQL("DROP TABLE programmes")
            c.execSQL("ALTER TABLE programmes_shadow RENAME TO programmes")
            // Built once over the finished table — cheaper than maintaining it across 70k
            // streamed inserts; the old index name freed when its table dropped.
            c.execSQL("CREATE INDEX programmes_lookup ON programmes(epg_id, start_ms)")
            c.execSQL("COMMIT")
        } catch (t: Throwable) {
            c.execSQL("ROLLBACK"); throw t
        }
    }

    /** Chunk-insert programmes into the shadow table (short lock holds during a feed parse). */
    suspend fun insertProgrammes(rows: List<EpgProgrammeRow>): Unit = mutex.withLock {
        if (rows.isEmpty()) return@withLock
        val c = connection()
        c.execSQL("BEGIN IMMEDIATE")
        try {
            c.prepare("INSERT INTO programmes_shadow(epg_id, start_ms, end_ms, title, desc) VALUES(?,?,?,?,?)").use { st ->
                for (p in rows) {
                    st.reset()
                    st.bindText(1, p.channelId); st.bindLong(2, p.startMs); st.bindLong(3, p.endMs)
                    st.bindText(4, p.title)
                    if (p.desc != null) st.bindText(5, p.desc) else st.bindNull(5)
                    st.step()
                }
            }
            c.execSQL("COMMIT")
        } catch (t: Throwable) {
            c.execSQL("ROLLBACK"); throw t
        }
    }

    /** Now + next for one EPG channel (rows start-ordered, all ending after [nowMs]). */
    suspend fun nowNext(epgId: String, nowMs: Long): List<EpgProgrammeRow> = mutex.withLock {
        connection().prepare(
            "SELECT epg_id, start_ms, end_ms, title, desc FROM programmes WHERE epg_id = ? AND end_ms > ? ORDER BY start_ms LIMIT 2"
        ).use { st ->
            st.bindText(1, epgId); st.bindLong(2, nowMs)
            buildList {
                while (st.step()) {
                    add(EpgProgrammeRow(st.getText(0), st.getLong(1), st.getLong(2), st.getText(3), if (st.isNull(4)) null else st.getText(4)))
                }
            }
        }
    }

    /**
     * Programmes overlapping [fromMs, toMs) whose title contains ANY of [tokens]
     * (ASCII-case-insensitive LIKE; callers pass normalized lowercase team tokens and
     * score/verify the rows — this is just the SQL-side candidate cut).
     */
    suspend fun searchProgrammes(tokens: List<String>, fromMs: Long, toMs: Long, limit: Int = 800): List<EpgProgrammeRow> {
        val safe = tokens.map { t -> t.lowercase().filter { it.isLetterOrDigit() || it == ' ' } }
            .filter { it.length > 2 }.distinct().take(8)
        if (safe.isEmpty()) return emptyList()
        return mutex.withLock {
            val likes = safe.joinToString(" OR ") { "title LIKE ?" }
            connection().prepare(
                "SELECT epg_id, start_ms, end_ms, title, desc FROM programmes WHERE start_ms < ? AND end_ms > ? AND ($likes) LIMIT $limit"
            ).use { st ->
                st.bindLong(1, toMs); st.bindLong(2, fromMs)
                safe.forEachIndexed { i, t -> st.bindText(3 + i, "%$t%") }
                buildList {
                    while (st.step()) {
                        add(EpgProgrammeRow(st.getText(0), st.getLong(1), st.getLong(2), st.getText(3), if (st.isNull(4)) null else st.getText(4)))
                    }
                }
            }
        }
    }

    // --- provider mappings ------------------------------------------------------

    /** Full replace of one provider's channel→EPG mappings. */
    suspend fun replaceMapping(providerKey: String, rows: List<EpgMappingRow>): Unit = mutex.withLock {
        val c = connection()
        val now = TraktPlatformClock.nowEpochMs()
        c.execSQL("BEGIN IMMEDIATE")
        try {
            c.prepare("DELETE FROM mapping WHERE provider_key = ?").use { st ->
                st.bindText(1, providerKey); st.step()
            }
            c.prepare("INSERT OR REPLACE INTO mapping(provider_key, stream_id, epg_id, tier, updated_ms) VALUES(?,?,?,?,?)").use { st ->
                for (r in rows) {
                    st.reset()
                    st.bindText(1, providerKey); st.bindLong(2, r.streamId.toLong())
                    st.bindText(3, r.epgId); st.bindText(4, r.tier); st.bindLong(5, now)
                    st.step()
                }
            }
            c.execSQL("COMMIT")
        } catch (t: Throwable) {
            c.execSQL("ROLLBACK"); throw t
        }
    }

    /** streamId → epgId for one provider. */
    suspend fun mappingFor(providerKey: String): Map<Int, String> = mutex.withLock {
        connection().prepare("SELECT stream_id, epg_id FROM mapping WHERE provider_key = ?").use { st ->
            st.bindText(1, providerKey)
            buildMap { while (st.step()) put(st.getLong(0).toInt(), st.getText(1)) }
        }
    }

    /** Every distinct EPG id any provider channel mapped onto (the programme-parse filter). */
    suspend fun mappedEpgIds(): Set<String> = mutex.withLock {
        connection().prepare("SELECT DISTINCT epg_id FROM mapping").use { st ->
            buildSet { while (st.step()) add(st.getText(0)) }
        }
    }

    suspend fun purgeProvider(providerKey: String): Unit = mutex.withLock {
        connection().prepare("DELETE FROM mapping WHERE provider_key = ?").use { st ->
            st.bindText(1, providerKey); st.step()
        }
    }
}
