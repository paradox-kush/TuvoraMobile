package com.nuvio.app.features.livetv.pause

/**
 * Pure rule for "the viewer is live again" on the engine (mpv) back buffer.
 *
 * The seek window's end is the demuxer's cache end (`demuxer-cache-state.seekable-ranges`), not the provider's live
 * edge. On a segmented stream the demuxer pulls whole segments ahead of the playhead, so even a viewer who is exactly
 * live sits a read-ahead LEAD behind that end; with the raised forward cache the gap is not shrinking. A fixed
 * 2 s slack then never matches and the session never ends on its own. The lead seen while the viewer was live
 * (the largest, because segments land in bursts) is therefore part of the slack. Capped, so a huge cache is never
 * mistaken for "live".
 */
object LiveCaughtUp {
    /** Larger gaps are a real delay, not a read-ahead lead. */
    const val MAX_LEAD_MS = 30_000L

    /** Folds one observation (gap between the playhead and the window end while live) into the lead so far. */
    fun noteLead(current: Long, behindLiveMs: Long): Long =
        maxOf(current, behindLiveMs).coerceIn(0L, MAX_LEAD_MS)

    fun slackMs(edgeSlackMs: Long, liveLeadMs: Long): Long = edgeSlackMs + liveLeadMs.coerceIn(0L, MAX_LEAD_MS)

    fun isCaughtUp(behindLiveMs: Long, edgeSlackMs: Long, liveLeadMs: Long): Boolean =
        behindLiveMs <= slackMs(edgeSlackMs, liveLeadMs)
}
