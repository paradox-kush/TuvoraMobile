package com.nuvio.app.features.player

/** One source attempt; independent of the player, network and wall clock. */
class LivePlaybackStartupPolicy {
    private var started = false
    private var failed = false

    /** True is terminal for this attempt. A Retry/channel switch creates a fresh policy. */
    fun sample(snapshot: PlayerPlaybackSnapshot, elapsedMs: Long): Boolean {
        if (failed) return true
        started = started || snapshot.isPlaying || snapshot.positionMs > 0L
        failed = !started && (snapshot.isEnded || elapsedMs >= TIMEOUT_MS)
        return failed
    }

    companion object {
        // Allows slow probes/GOPs; a concrete failure ends the attempt immediately.
        const val TIMEOUT_MS = 20_000L

        /** eof-reached may already be cleared by the time END_FILE is dispatched. */
        fun endFileSnapshot(snapshot: PlayerPlaybackSnapshot, reason: String?, isLive: Boolean): PlayerPlaybackSnapshot =
            if (isLive && (reason == "eof" || reason == "error")) {
                snapshot.copy(isEnded = true, isPlaying = false, isLoading = false)
            } else snapshot
    }
}
