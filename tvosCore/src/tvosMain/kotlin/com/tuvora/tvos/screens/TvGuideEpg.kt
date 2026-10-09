package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.TileEpgQueue
import com.nuvio.app.features.iptv.XtreamProgram
import com.nuvio.app.features.livetv.LiveGuideChannel
import com.nuvio.app.features.livetv.LiveTvData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** One row's programmes for one window, as the guide should show them. */
data class TvGuideRowEpg(val contentId: String, val windowStartMs: Long, val programmes: List<XtreamProgram>)

/**
 * The guide's EPG loader: [TvGuideEpgPrefetch] decides which rows and which source; this runs the
 * asks through the shared [TileEpgQueue] (two workers, newest-first, capped backlog with eviction —
 * the hub tiles' queue), stamps each (row, window) once, and publishes results on [results].
 */
object TvGuideEpg {
    private val lock = SynchronizedObject()
    private val asked = HashSet<String>()
    private val historyShown = HashSet<String>()
    /** Stamps whose answer had at least one programme: what a guide-data bump leaves alone. */
    private val answered = HashSet<String>()
    private var last: LastRequest? = null
    private var seenGeneration = LiveTvData.guideDataGeneration.value

    private class LastRequest(
        val channels: List<LiveGuideChannel>, val anchor: Int, val windowStartMs: Long,
        val travelling: Boolean, val catchUpSupported: Boolean,
    )
    private val _results = MutableSharedFlow<TvGuideRowEpg>(extraBufferCapacity = 128)
    val results: SharedFlow<TvGuideRowEpg> = _results.asSharedFlow()

    /** A fresh guide: forget what earlier screens asked for (their results went nowhere). */
    fun resetSession() = synchronized(lock) {
        asked.clear(); historyShown.clear(); answered.clear(); last = null
        seenGeneration = LiveTvData.guideDataGeneration.value
    }

    /**
     * Bumped when new guide data lands (a playlist's XMLTV ingest finishing, a mirror sync, a manual
     * guide pick). The open guide collects it and calls [onGuideDataGeneration] - rows asked while the
     * ingest was still running answered empty and were stamped once-only, so without this the guide
     * kept "No information" until the user left and came back.
     */
    val guideDataGeneration: StateFlow<Long> get() = LiveTvData.guideDataGeneration

    /**
     * New guide data may have landed: forget the stamps of the rows that answered empty (and the
     * focused channel's) and ask for them again - see [TvGuideDataRefresh].
     */
    fun onGuideDataGeneration(generation: Long) {
        val again = synchronized(lock) {
            if (!TvGuideDataRefresh.changed(seenGeneration, generation)) return
            seenGeneration = generation
            val req = last ?: return
            val rows = TvGuideEpgPrefetch.rows(req.anchor, req.channels.size).map { req.channels[it] }
            val plan = TvGuideDataRefresh.plan(
                askedKeys = asked,
                answeredKeys = answered,
                shownRowIds = rows.map { it.contentId },
                focusedId = req.channels.getOrNull(req.anchor.coerceIn(0, req.channels.lastIndex.coerceAtLeast(0)))?.contentId,
                windowStartMs = req.windowStartMs,
            )
            asked.removeAll(plan.dropKeys)
            answered.removeAll(plan.dropKeys)
            req
        }
        request(again.channels, again.anchor, again.windowStartMs, again.travelling, again.catchUpSupported)
    }

    /** The number of asks made this session — the regression hook for the fan-out bound. */
    val askedCount: Int get() = synchronized(lock) { asked.size }

    /**
     * Asks for the rows around [anchor] in [channels] (the list the guide shows), for the window at
     * [windowStartMs]. Call only once focus has settled ([TvGuideEpgPrefetch.SETTLE_MS]).
     */
    fun request(channels: List<LiveGuideChannel>, anchor: Int, windowStartMs: Long, travelling: Boolean, catchUpSupported: Boolean) {
        synchronized(lock) { last = LastRequest(channels, anchor, windowStartMs, travelling, catchUpSupported) }
        val rows = TvGuideEpgPrefetch.rows(anchor, channels.size).map { channels[it] }
        val byKey = rows.associateBy { TvGuideEpgPrefetch.key(it.contentId, windowStartMs) }
        val keys = synchronized(lock) {
            TvGuideEpgPrefetch.pending(rows.map { it.contentId }, windowStartMs, asked).also { asked.addAll(it) }
        }
        // Newest-first queue: enqueue reversed so the focused row runs first.
        for (key in keys.asReversed()) {
            val channel = byKey.getValue(key)
            val fetch = TvGuideEpgPrefetch.fetchFor(travelling, channel.hasArchive, catchUpSupported)
            if (fetch == TvRowFetch.Skip) {
                _results.tryEmit(TvGuideRowEpg(channel.contentId, windowStartMs, emptyList()))
                continue
            }
            TileEpgQueue.enqueue(
                key = "tvguide:$key",
                onEvicted = { synchronized(lock) { asked.remove(key) } },
            ) { load(channel, windowStartMs, travelling, fetch, key) }
        }
    }

    private suspend fun load(channel: LiveGuideChannel, windowStartMs: Long, travelling: Boolean, fetch: TvRowFetch, key: String): Boolean {
        if (fetch == TvRowFetch.History) LiveTvData.ensureHistory(channel.contentId)
        val shown = synchronized(lock) { key in historyShown }
        val window = TvCatchUp.windowProgrammes(
            channel.contentId, windowStartMs, windowStartMs + TvGuideTimeline.WINDOW_MS, travelling, shown,
        )
        if (window.fromHistory) synchronized(lock) { historyShown.add(key) }
        if (window.programmes.isNotEmpty()) synchronized(lock) { answered.add(key) }
        _results.emit(TvGuideRowEpg(channel.contentId, windowStartMs, window.programmes))
        return window.programmes.isNotEmpty()
    }
}
