package com.nuvio.app.features.player

/**
 * The engine side of live pause / rewind for an mpv-backed [NuvioPlayerBridge] (iPhone/iPad and Apple TV): the
 * engine's own demuxer cache is the buffer, so pausing raises its budget and the rewind window is what
 * `demuxer-cache-state.seekable-ranges` says it holds. Pure glue over [MpvLiveCache]: the names, values and the
 * window arithmetic are shared with Android, the bridge only moves strings.
 *
 * No emulation of any OS API: how much RAM the cache may take is decided by the caller from the platform's own
 * tier (`LivePauseDeviceBudget`), never here.
 */
internal class BridgeLiveCache(private val bridge: NuvioPlayerBridge) {
    /** False for a bridge that has no live cache hooks (it keeps the plain pause). */
    val supported: Boolean get() = bridge is NuvioPlayerLiveCacheBridge

    /** Applies one cache plan off the main thread (the bridge queues the writes). No-op when unsupported. */
    fun setBudget(maxBytes: Long, maxBackBytes: Long, cacheSecs: Long) {
        val cache = bridge as? NuvioPlayerLiveCacheBridge ?: return
        val properties = MpvLiveCache.properties(maxBytes, maxBackBytes, cacheSecs)
        cache.applyLiveCacheProperties(properties.map { it.first }, properties.map { it.second })
    }

    /** The seekable stretch the playhead is in, from the bridge's observed shadow; null when there is none. */
    fun window(): LiveSeekWindow? {
        val cache = bridge as? NuvioPlayerLiveCacheBridge ?: return null
        return MpvLiveCache.window(MpvLiveCache.parseRanges(cache.getSeekableRanges()), bridge.getPositionMs())
    }
}
