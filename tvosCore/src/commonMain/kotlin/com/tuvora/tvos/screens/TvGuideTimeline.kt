package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.CatchUpEpgPolicy
import com.nuvio.app.features.iptv.XtreamCatchUp
import com.nuvio.app.features.iptv.XtreamCatchUp.ProgrammeAction
import com.nuvio.app.features.iptv.XtreamProgram
import com.nuvio.app.features.livetv.LiveGuideChannel

/** What OK does on a programme cell (NuvioTV GuideCellIntent). */
enum class TvCellIntent {
    /** Nothing playable — the cell is not a focus target. */
    None,
    /** Airing, no archive: tune live. */
    PlayLive,
    /** Airing on an archive channel: "Start over" or "Watch live". */
    OpenSheet,
    /** Finished and kept: replay at once. */
    Replay,
}

/** One cell of a channel's timeline strip: a programme, or a filler gap ([programme] null). */
data class TvGuideCell(
    val programme: XtreamProgram?,
    /** Fraction of the window width, left edge and width. */
    val from: Double,
    val width: Double,
    val intent: TvCellIntent,
    val replayBadge: Boolean,
    val airing: Boolean,
)

/**
 * The catch-up guide's timeline, as NuvioTV draws and moves it (ui/screens/iptv/GuideTimeTravel.kt,
 * GuideCellIntent.kt, XtreamGuideCatchUp.kt `guideActionFor` / `GuideProgrammeCells`): a two-hour
 * window that opens one slot in the past, pages a whole window at its edges, and never travels past
 * what the panel keeps. Pure (clock and programmes in, geometry and intents out) so the clamps and
 * the OK rule are pinned by tests.
 */
object TvGuideTimeline {
    const val SLOT_MS = 30 * 60 * 1000L
    const val WINDOW_MS = 2 * 60 * 60 * 1000L
    const val SLOTS = (WINDOW_MS / SLOT_MS).toInt()

    /** One press at the window's edge travels a full window (NuvioTV EDGE_TRAVEL_SLOTS: the Onn field report). */
    const val EDGE_TRAVEL_SLOTS = SLOTS

    /** The resting window shows one slot of the past, so replay is discoverable without travelling. */
    const val LIVE_LOOKBACK_MS = SLOT_MS

    fun liveWindowStartMs(nowMs: Long): Long = slotFloor(nowMs - LIVE_LOOKBACK_MS)

    /** The furthest back: what the catch-up store keeps (the panel's window, never less than its floor). */
    fun earliestWindowStartMs(nowMs: Long, catchUpDays: Int): Long =
        slotFloor(CatchUpEpgPolicy.parseWindowStartMs(nowMs, catchUpDays))

    /** The furthest forward: where the window's right edge meets the stored horizon. */
    fun latestWindowStartMs(nowMs: Long): Long =
        maxOf(slotFloor(CatchUpEpgPolicy.parseWindowEndMs(nowMs) - WINDOW_MS), liveWindowStartMs(nowMs))

    /** Moves the window [slots] half-hours (negative = back), clamped to the travelable range. */
    fun shift(startMs: Long, slots: Int, nowMs: Long, catchUpDays: Int): Long =
        (startMs + slots * SLOT_MS).coerceIn(earliestWindowStartMs(nowMs, catchUpDays), latestWindowStartMs(nowMs))

    fun isAtLiveEdge(startMs: Long, nowMs: Long): Boolean = startMs == liveWindowStartMs(nowMs)

    /** A window at live follows the clock; one the viewer travelled stays where it was put. */
    fun onClockTick(startMs: Long, previousNowMs: Long, nowMs: Long): Long =
        if (isAtLiveEdge(startMs, previousNowMs)) liveWindowStartMs(nowMs) else startMs

    fun containsNow(startMs: Long, nowMs: Long): Boolean = nowMs >= startMs && nowMs < startMs + WINDOW_MS

    fun nowFraction(startMs: Long, nowMs: Long): Double =
        ((nowMs - startMs).toDouble() / WINDOW_MS).coerceIn(0.0, 1.0)

    /** The header ticks. */
    fun slotStarts(startMs: Long): List<Long> = (0 until SLOTS).map { startMs + it * SLOT_MS }

    /**
     * NuvioTV guideActionFor: the shared [XtreamCatchUp.actionFor], then a playlist that cannot build a
     * replay URL (Stalker) never shows a replay affordance; an M3U playlist's channels do when they carry catch-up.
     */
    fun action(programme: XtreamProgram, hasArchive: Boolean, catchUpDays: Int, nowMs: Long, catchUpSupported: Boolean): ProgrammeAction {
        val action = XtreamCatchUp.actionFor(
            programmeStartMs = programme.startMs,
            programmeEndMs = programme.endMs,
            nowMs = nowMs,
            hasArchive = hasArchive,
            catchUpDays = catchUpDays,
            programmeHasArchive = programme.hasArchive,
        )
        if (catchUpSupported) return action
        return when (action) {
            ProgrammeAction.START_OVER -> ProgrammeAction.PLAY_LIVE
            ProgrammeAction.REPLAY -> ProgrammeAction.NONE
            else -> action
        }
    }

    fun intent(action: ProgrammeAction): TvCellIntent = when (action) {
        ProgrammeAction.NONE -> TvCellIntent.None
        ProgrammeAction.PLAY_LIVE -> TvCellIntent.PlayLive
        ProgrammeAction.START_OVER -> TvCellIntent.OpenSheet
        ProgrammeAction.REPLAY -> TvCellIntent.Replay
    }

    /** ⟲ means "the panel kept this", not "selectable": an airing programme without archive has none. */
    fun showsReplayBadge(action: ProgrammeAction): Boolean =
        action == ProgrammeAction.REPLAY || action == ProgrammeAction.START_OVER

    /**
     * One channel's strip across the window: programme cells in time order with filler cells for the
     * gaps, each carrying its OK intent. Programmes outside the window are dropped.
     */
    fun cells(
        programmes: List<XtreamProgram>,
        channel: LiveGuideChannel,
        startMs: Long,
        nowMs: Long,
        catchUpSupported: Boolean,
    ): List<TvGuideCell> {
        val endMs = startMs + WINDOW_MS
        val visible = programmes
            .filter { it.endMs > startMs && it.startMs < endMs && it.endMs > it.startMs }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs }
        fun fraction(ms: Long) = (ms - startMs).toDouble() / WINDOW_MS
        val cells = mutableListOf<TvGuideCell>()
        var cursor = startMs
        for (p in visible) {
            val from = maxOf(p.startMs, startMs, cursor)
            val to = minOf(p.endMs, endMs)
            if (to <= from) continue
            if (from > cursor) cells += filler(fraction(cursor), fraction(from))
            val action = action(p, channel.hasArchive, channel.catchUpDays, nowMs, catchUpSupported)
            cells += TvGuideCell(
                programme = p,
                from = fraction(from),
                width = fraction(to) - fraction(from),
                intent = intent(action),
                replayBadge = showsReplayBadge(action),
                airing = nowMs >= p.startMs && nowMs < p.endMs,
            )
            cursor = to
        }
        if (cursor < endMs) cells += filler(fraction(cursor), 1.0)
        return cells
    }

    private fun filler(from: Double, to: Double) =
        TvGuideCell(programme = null, from = from, width = to - from, intent = TvCellIntent.None, replayBadge = false, airing = false)

    /** Start–end as the sheet's kicker shows it, in whole minutes. */
    fun durationMinutes(programme: XtreamProgram): Int = XtreamCatchUp.durationMinutes(programme.startMs, programme.endMs)

    private fun slotFloor(ms: Long): Long = ms.floorDiv(SLOT_MS) * SLOT_MS
}

/** What a replay attempt's player state means for the catch-up dialect walk. */
enum class TvReplayVerdict {
    /** Still opening: keep watching. */
    Pending,
    /** It played: pin the dialect, stop walking. */
    Proven,
    /** The Stalker session cap: say so, don't walk (retrying can't help). */
    SessionLimit,
    /** It failed: report to the walk, which answers the next URL shape or gives up. */
    Failed,
}

/**
 * The phone's LiveTvScreen replay rule (onSnapshot / onCatchUpFailure), pure: playing or any
 * position proves the attempt; an error or an immediate end-of-file (libmpv reports an unopenable
 * catch-up URL as EOF, not an error) fails it; a session-limit error is its own notice.
 */
object TvReplayPolicy {
    fun verdict(isPlaying: Boolean, positionMs: Long, isEnded: Boolean, errorMessage: String?): TvReplayVerdict = when {
        isPlaying || positionMs > 0L -> TvReplayVerdict.Proven
        errorMessage != null && com.nuvio.app.features.iptv.CatchUpPlayback.isSessionLimit(errorMessage) -> TvReplayVerdict.SessionLimit
        errorMessage != null || isEnded -> TvReplayVerdict.Failed
        else -> TvReplayVerdict.Pending
    }

    /** NuvioTV's notice when no URL shape plays (XtreamLiveGuideViewModel.startReplay). */
    fun noRecordingText(programmeTitle: String): String = "This provider has no recording of \"$programmeTitle\""

    /** The shared compose_livetv_catchup_session_limit copy (NuvioTV has no separate string). */
    const val SESSION_LIMIT_TEXT = "Your provider allows fewer connections than you're using · Close another device and try again"

    /** A replay that did not open or never started, with Retry beside it (never "no recording"). */
    const val REPLAY_FAILED_TEXT = "Couldn't open this replay \u00b7 Try again"

    /** The shared compose_livetv_catchup_ended copy: the replay ran out and the player is back on live. */
    const val REPLAY_ENDED_TEXT = "This replay has ended \u00b7 Back on live"

    /** The player title for a replay: "Channel · Programme", as NuvioTV's ReplayLaunch. */
    fun title(channelName: String, programmeTitle: String): String = "$channelName · $programmeTitle"
}
