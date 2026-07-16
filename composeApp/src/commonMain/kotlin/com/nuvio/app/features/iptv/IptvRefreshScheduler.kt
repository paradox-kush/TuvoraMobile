package com.nuvio.app.features.iptv

import co.touchlab.kermit.Logger
import com.nuvio.app.features.iptv.content.IptvContentDb
import com.nuvio.app.features.iptv.epg.XmltvClient
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Per-playlist auto-refresh (P3-B), shared by the Android WorkManager worker and the iOS
 * foreground-on-launch hook. Scans the active profile's ENABLED playlists whose `autoRefreshHours > 0`
 * and are past their interval, then refreshes each by source type:
 *
 *  - **M3U (url/file):** re-ingest the catalog ([M3UClient.ingest], atomic meta-last) + refresh the
 *    XMLTV guide. The ingest's `ingest_meta.built_at` IS the last-refresh timestamp, so nothing extra
 *    is stored for M3U.
 *  - **Xtream:** API-on-demand — there's no catalog to re-ingest. "Refreshing" invalidates the search
 *    index's session channel cache (so it re-fetches) and bumps a stored last-checked timestamp.
 *
 * Defensive throughout: one playlist failing never aborts the others, and the whole pass is a no-op
 * when nothing is due. Serialized by a mutex so an on-launch pass and a periodic worker can't overlap.
 */
object IptvRefreshScheduler {

    private val log = Logger.withTag("IptvRefresh")
    private val json = Json { ignoreUnknownKeys = true }
    private val runLock = Mutex()

    /** Refreshes every due playlist for the active profile. Safe to call from anywhere; no-ops when idle. */
    suspend fun refreshDuePlaylists(): Int {
        // Never overlap two passes (worker + on-launch). tryLock so a running pass just wins.
        if (!runLock.tryLock()) return 0
        try {
            XtreamRepository.ensureLoaded()
            val profileId = ProfileRepository.activeProfileId
            val now = TraktPlatformClock.nowEpochMs()
            val accounts = XtreamRepository.uiState.value.accounts.filter { it.enabled && it.autoRefreshHours > 0 }
            if (accounts.isEmpty()) return 0

            val xtreamState = loadXtreamRefreshState(profileId).toMutableMap()
            var refreshed = 0
            for (acc in accounts) {
                val last = lastRefreshMs(acc, xtreamState)
                if (!dueForRefresh(last, acc.autoRefreshHours, now)) continue
                val ok = runCatching { refreshOne(acc) }
                    .onFailure { log.w(it) { "auto-refresh failed for ${acc.id}" } }
                    .getOrDefault(false)
                if (ok) {
                    refreshed++
                    // Xtream carries no ingest row, so record its checked time in the pref map.
                    if (!acc.sourceType.isM3u()) xtreamState[acc.id] = now
                }
            }
            if (xtreamState.isNotEmpty()) saveXtreamRefreshState(profileId, xtreamState)
            if (refreshed > 0) log.i { "auto-refresh done: $refreshed playlist(s) refreshed" }
            return refreshed
        } finally {
            runLock.unlock()
        }
    }

    /** Refresh one playlist by source type. Returns true on a usable refresh. */
    private suspend fun refreshOne(acc: XtreamAccount): Boolean = when {
        acc.sourceType.isM3u() -> {
            // Re-ingest the catalog (atomic, meta-last) then refresh the guide. A file playlist with no
            // local copy will fail the ingest defensively (caught by the caller) — nothing to re-fetch.
            val ok = M3UClient.ingest(acc).isSuccess
            if (ok) runCatching { XmltvClient.ensureEpg(acc, force = true) }
            ok
        }
        else -> {
            // Xtream/Stalker: invalidate the cached channel list so the next browse/search re-fetches,
            // and verify the panel is still reachable (cheap) so we only bump the timestamp on success.
            XtreamSearchIndex.invalidate(acc.id)
            IptvClient.forAccount(acc).accountInfo(acc).isSuccess
        }
    }

    /** The last-refresh instant for a playlist: the M3U ingest time, or the stored Xtream checked time. */
    private suspend fun lastRefreshMs(acc: XtreamAccount, xtreamState: Map<String, Long>): Long =
        if (acc.sourceType.isM3u()) {
            IptvContentDb.ingestMeta(acc.id)?.builtAtMs ?: 0L
        } else {
            xtreamState[acc.id] ?: 0L
        }

    private fun loadXtreamRefreshState(profileId: Int): Map<String, Long> {
        val stored = XtreamAccountStorage.loadRefreshStateJson(profileId) ?: return emptyMap()
        return runCatching { json.decodeFromString(refreshStateSerializer, stored) }.getOrDefault(emptyMap())
    }

    private fun saveXtreamRefreshState(profileId: Int, state: Map<String, Long>) {
        runCatching { XtreamAccountStorage.saveRefreshStateJson(profileId, json.encodeToString(refreshStateSerializer, state)) }
    }

    private val refreshStateSerializer = MapSerializer(String.serializer(), Long.serializer())
}

/**
 * Whether a playlist last refreshed at [lastRefreshMs] is due again, given [autoRefreshHours] (0 =
 * off) at instant [nowMs]. Pure so the due-selection is unit-tested. A never-refreshed playlist
 * ([lastRefreshMs] <= 0) is always due (as long as auto-refresh is on); a clock that went backwards
 * (now < last) is treated as due to self-heal rather than wait ~forever.
 */
fun dueForRefresh(lastRefreshMs: Long, autoRefreshHours: Int, nowMs: Long): Boolean {
    if (autoRefreshHours <= 0) return false
    if (lastRefreshMs <= 0L) return true
    val intervalMs = autoRefreshHours.toLong() * 60L * 60L * 1000L
    val elapsed = nowMs - lastRefreshMs
    return elapsed < 0L || elapsed >= intervalMs
}
