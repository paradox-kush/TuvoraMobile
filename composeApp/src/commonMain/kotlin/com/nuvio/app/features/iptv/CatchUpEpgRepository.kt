package com.nuvio.app.features.iptv

import com.nuvio.app.features.iptv.content.EpgProgrammeRow
import com.nuvio.app.features.iptv.content.IptvContentDb
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One channel's history, fetched lazily and kept on disk.
 *
 * Three rules shape this, all of them memory rules rather than feature rules:
 *
 * - **The FOCUSED channel only, never a prefetch.** A guide page holds dozens of rows; fetching
 *   each one's full table is how 2 MB becomes 40 MB. The channel the viewer is actually watching
 *   is the one whose past they can act on.
 * - **Single-flight per channel.** The guide asks on scroll, on channel switch and on travel, and
 *   those overlap freely; concurrent fetches would stampede a panel that frequently allows one
 *   connection in total.
 * - **SQLite is the heap.** Rows go from the stream parser to the database and are read back a
 *   window at a time with the description truncated in SQL. Nothing holds a channel's table.
 *
 * Xtream only. `tv_archive` is an Xtream concept; M3U and Stalker playlists already ingest their
 * guide wholesale through XMLTV / bulk EPG, and their catch-up needs a different (server-built or
 * `catchup=`-attribute) URL story that this lane does not open.
 */
internal object CatchUpEpgRepository {

    private val gate = Mutex()
    private val inFlight = HashMap<String, CompletableDeferred<Unit>>()

    /** `server_info` costs a whole panel round trip, so it is asked once per account per session. */
    private val clockOffsets = HashMap<String, Long?>()
    private val allowedFormats = HashMap<String, List<String>?>()
    private val panelFactsFetched = HashSet<String>()

    /** Whether this playlist can serve catch-up at all — the guide's per-channel affordance gate. */
    fun supportsCatchUp(account: XtreamAccount): Boolean =
        account.sourceType == SOURCE_TYPE_XTREAM

    /**
     * Whether a replay can be built for ONE channel of [account]: an Xtream playlist (any channel; the
     * panel's `tv_archive` flag decides the rest), or a URL/file M3U playlist on a channel whose own
     * `catchup-*` attributes ([m3uCatchUp]) say how to ask. A Stalker archive is built server-side from a
     * programme id our EPG drops, so it is not offered. The history fetch above stays Xtream-only
     * ([supportsCatchUp] with one argument): M3U history comes from the XMLTV guide.
     */
    fun supportsCatchUp(account: XtreamAccount, m3uCatchUp: M3uCatchUpInfo?): Boolean =
        supportsCatchUp(account) ||
            account.sourceType == SOURCE_TYPE_STALKER ||
            (m3uCatchUp != null &&
                (account.sourceType == SOURCE_TYPE_M3U_URL || account.sourceType == SOURCE_TYPE_M3U_FILE))

    /** The Stalker bulk guide, landed - THROWS on failure (a replay must not read a failed guide as "no recording"). */
    suspend fun ensureStalkerGuide(account: XtreamAccount) {
        com.nuvio.app.features.iptv.stalker.StalkerClient.ensureGuide(account)
    }

    /**
     * Ensures this channel's guide table is on disk and fresh enough, then returns.
     *
     * Cheap and idempotent: a channel fetched inside [CatchUpEpgPolicy.FETCH_GATE_MS] returns
     * without touching the network, and a fetch already running is JOINED rather than duplicated.
     * A failure is swallowed — the guide falls back to now-and-next, which is what it showed
     * before this existed.
     */
    suspend fun ensureHistory(account: XtreamAccount, streamId: Int) {
        // A Stalker portal has no per-channel history call: its aired programmes (and the ids a replay is
        // addressed by) come from the ONE bulk guide, so "history is on disk" means that fetch has landed.
        // The window read that follows waits on it instead of racing it on a first open.
        if (account.sourceType == SOURCE_TYPE_STALKER) {
            GuideRead.softly(Unit) { ensureStalkerGuide(account) }
            return
        }
        if (!supportsCatchUp(account)) return
        val channelId = streamId.toString()
        val nowMs = TraktPlatformClock.nowEpochMs()

        val fetchedAt = runCatching { IptvContentDb.epgChannelFetchedAt(account.id, channelId) }.getOrNull()
        if (!CatchUpEpgPolicy.shouldFetch(fetchedAt, nowMs)) return

        val key = "${account.id}#$channelId"
        val (deferred, isOwner) = gate.withLock {
            inFlight[key]?.let { return@withLock it to false }
            val d = CompletableDeferred<Unit>()
            inFlight[key] = d
            d to true
        }
        if (!isOwner) {
            deferred.await()
            return
        }
        try {
            fetchChannel(account, streamId, channelId, nowMs)
        } finally {
            gate.withLock { inFlight.remove(key) }
            deferred.complete(Unit)
        }
    }

    private suspend fun fetchChannel(
        account: XtreamAccount,
        streamId: Int,
        channelId: String,
        nowMs: Long,
    ) {
        val catchUpDays = 0   // per-channel windows are per-panel; an unknown window is permissive
        val rows = ArrayList<EpgProgrammeRow>()
        val ok = runCatching {
            XtreamClient.simpleDataTableInto(account, streamId, nowMs, catchUpDays) { programme ->
                rows.add(
                    EpgProgrammeRow(
                        channelId = channelId,
                        startMs = programme.startMs,
                        endMs = programme.endMs,
                        title = programme.title,
                        desc = programme.description.takeIf { it.isNotBlank() },
                        // Only a POSITIVE mark is stored: the column is NOT NULL, and the policy
                        // treats "false" and "the panel never said" identically anyway.
                        hasArchive = programme.hasArchive == true,
                    )
                )
            }
        }.isSuccess
        // A throw means truncated or listings-less — do NOT stamp, or the gate would hold a
        // failed fetch shut for six hours and the channel would look like it has no guide.
        if (!ok) return
        runCatching {
            IptvContentDb.refillChannelEpg(account.id, channelId, rows, nowMs)
            IptvContentDb.pruneEpg(account.id, CatchUpEpgPolicy.pruneCutoffMs(nowMs, catchUpDays))
        }
    }

    /**
     * Programmes overlapping [fromMs, toMs) for one channel, descriptions truncated in SQL.
     *
     * Returns an empty list rather than throwing: a guide row with no data draws its "No EPG"
     * placeholder, which is strictly better than a screen that fails to compose.
     */
    suspend fun window(
        account: XtreamAccount,
        streamId: Int,
        fromMs: Long,
        toMs: Long,
    ): List<XtreamProgram> =
        GuideRead.softly(emptyList()) { windowOrThrow(account, streamId, fromMs, toMs) }

    /** [window] that lets a database failure through: for a caller that must tell "failed" from "nothing there" (a replay's programme id). */
    suspend fun windowOrThrow(account: XtreamAccount, streamId: Int, fromMs: Long, toMs: Long): List<XtreamProgram> =
        IptvContentDb.epgWindow(account.id, streamId.toString(), fromMs, toMs).map { it.toProgramme() }

    /** The FULL description for the programme sheet — the only place the whole text is read. */
    suspend fun fullDescription(account: XtreamAccount, streamId: Int, startMs: Long): String? =
        runCatching { IptvContentDb.epgFullDesc(account.id, streamId.toString(), startMs) }.getOrNull()

    /**
     * The panel's measured clock offset and its stated output formats, fetched together (they come
     * from the same `server_info` object) and remembered for the session.
     */
    suspend fun panelFacts(account: XtreamAccount): PanelFacts {
        if (account.id !in panelFactsFetched) {
            panelFactsFetched.add(account.id)
            // The clock half is the session memo the guide's epoch-skew correction shares — if a
            // liar short-EPG response already measured it, this is free. The replay math below is
            // unchanged.
            clockOffsets[account.id] = XtreamPanelClock.measuredOffsetMs(account)
            allowedFormats[account.id] = XtreamClient.allowedOutputFormats(account)
        }
        return PanelFacts(
            // The manual correction ADDS to whatever was measured: it exists for panels whose own
            // clock pair is a lie, and on those the measured value is the thing being corrected.
            serverOffsetMs = XtreamCatchUp.replayOffsetMs(clockOffsets[account.id], account.catchUpTimeCorrectionMinutes),
            allowedOutputFormats = allowedFormats[account.id],
        )
    }

    /** Drops the session's cached panel facts — used when a playlist's options change. */
    fun forget(accountId: String) {
        panelFactsFetched.remove(accountId)
        clockOffsets.remove(accountId)
        allowedFormats.remove(accountId)
        XtreamPanelClock.forget(accountId)
    }

    data class PanelFacts(
        val serverOffsetMs: Long?,
        val allowedOutputFormats: List<String>?,
    )

    private fun EpgProgrammeRow.toProgramme(): XtreamProgram = XtreamProgram(
        title = title,
        description = desc.orEmpty(),
        startMs = startMs,
        endMs = endMs,
        nowPlaying = false,
        // The column cannot hold "the panel said nothing", so an unmarked row reads as silence
        // rather than a denial — which is what the channel-level rules already assume.
        hasArchive = true.takeIf { hasArchive },
        programmeId = programmeId,
    )
}
