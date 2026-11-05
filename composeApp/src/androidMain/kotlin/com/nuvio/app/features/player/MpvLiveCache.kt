package com.nuvio.app.features.player

/**
 * Pure helpers for the libmpv live back-buffer hooks ([PlayerEngineController.setLiveCacheBudget] /
 * [PlayerEngineController.liveSeekWindow]) so the numbers and the parsing are testable without an mpv core.
 *
 * Facts (mpv v0.41.0 demux.c thread_work -> update_opts): a runtime change of `demuxer-max-bytes`,
 * `demuxer-max-back-bytes` and `cache-secs` applies to the CURRENT file (pruned if shrunk); the demuxer reads ahead
 * while forward seconds < `cache-secs` AND forward bytes < `demuxer-max-bytes`, so a pause needs both raised.
 * `demuxer-cache-state.seekable-ranges` lists the stretches a seek can land in without a network read.
 */
internal object MpvLiveCache {
    /** The runtime properties for one cache plan, in the order they are applied. */
    fun properties(maxBytes: Long, maxBackBytes: Long, cacheSecs: Long): List<Pair<String, String>> = buildList {
        add("demuxer-max-bytes" to maxBytes.coerceAtLeast(0L).toString())
        add("demuxer-max-back-bytes" to maxBackBytes.coerceAtLeast(0L).toString())
        // cache-secs overrides demuxer-readahead-secs when larger, so it alone controls read-ahead depth.
        if (cacheSecs > 0L) add("cache-secs" to cacheSecs.coerceAtMost(MAX_CACHE_SECS).toString())
    }

    /** A seekable stretch, in mpv's time-pos seconds. */
    data class Range(val startSec: Double, val endSec: Double)

    /**
     * The stretch the playhead is in (the newest one when it is outside all of them - the live one), in
     * milliseconds on mpv's time-pos clock; null when nothing is seekable or the position is unknown.
     */
    fun window(ranges: List<Range>, positionMs: Long): com.nuvio.app.features.player.LiveSeekWindow? {
        val valid = ranges.filter { it.startSec.isFinite() && it.endSec.isFinite() && it.endSec > it.startSec }
        if (valid.isEmpty() || positionMs <= 0L) return null
        val pos = positionMs / 1000.0
        val range = valid.firstOrNull { pos >= it.startSec - EDGE_TOLERANCE_SEC && pos <= it.endSec + EDGE_TOLERANCE_SEC }
            ?: valid.last()
        val start = (range.startSec * 1000.0).toLong()
        val end = (range.endSec * 1000.0).toLong()
        return com.nuvio.app.features.player.LiveSeekWindow(start, end, positionMs.coerceIn(start, end))
    }

    private const val EDGE_TOLERANCE_SEC = 0.5
    private const val MAX_CACHE_SECS = 1_000_000_000L
}
