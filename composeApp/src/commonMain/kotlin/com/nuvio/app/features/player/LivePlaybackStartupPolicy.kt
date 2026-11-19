package com.nuvio.app.features.player

/**
 * One source attempt; independent of the player, network and wall clock.
 *
 * Judges a channel's FIRST open only. [recoveringPlayedChannel] marks an automatic reconnect of a
 * channel that already played (the freeze watcher re-resolves through the same path as Retry): its
 * failures belong to the reconnect ladder, which keeps trying until the provider is back.
 */
class LivePlaybackStartupPolicy(private val recoveringPlayedChannel: Boolean = false) {
    private var started = false
    private var failed = false

    /** True is terminal for this attempt. A Retry/channel switch creates a fresh policy. */
    fun sample(snapshot: PlayerPlaybackSnapshot, elapsedMs: Long): Boolean {
        if (recoveringPlayedChannel) return false
        if (failed) return true
        started = started || hasStarted(snapshot)
        failed = !started && (snapshot.isEnded || elapsedMs >= TIMEOUT_MS)
        return failed
    }

    /**
     * The engine reported an error for THIS attempt. Judged by what this attempt itself drew, never by the screen's
     * last snapshot: on a zap that snapshot is still the PREVIOUS channel's played frame (the resolve effect does
     * not clear it), and reading it made a refused first open look like a mid-stream drop, so the screen sat on
     * "Connecting" forever (device pass 2026-11-18, play a channel then zap to a refused one).
     */
    fun sampleError(): Boolean = sample(PlayerPlaybackSnapshot(isEnded = true), elapsedMs = 0L)

    companion object {
        /**
         * Whether an engine snapshot was delivered to a callback built for a channel that is no longer on screen.
         * After a zap the previous engine's trailing snapshot still reaches its old closure; treated as the new
         * channel's it armed the freeze watcher under the new id and turned a refused open into a "drop"
         * (device pass 2026-11-18). Such a snapshot is dropped, not interpreted.
         */
        fun isStale(callbackContentId: String?, currentContentId: String?): Boolean =
            callbackContentId != currentContentId

        /**
         * Whether a new attempt on [contentId] is the freeze watcher's reconnect of a channel that already played.
         * The watcher is armed per screen, not per channel, and a zap builds the next channel's policy before the
         * old watch is torn down, so "armed" alone read a zap onto a refused channel as a mid-stream drop and the
         * freeze ladder re-asked it (4 extra requests on a connection-limited provider). It counts only when the
         * armed watch belongs to this channel; an unnamed watch keeps the old reading.
         */
        fun recoversPlayedChannel(armed: Boolean, armedContentId: String?, contentId: String?): Boolean =
            armed && (armedContentId == null || armedContentId == contentId)

        // Allows slow probes/GOPs; a concrete failure ends the attempt immediately.
        const val TIMEOUT_MS = 20_000L

        /**
         * Playback has really begun: the playhead moved or a frame was drawn. Never the bare
         * isPlaying flag — Android libmpv echoes `pause=false` into it before the file has loaded,
         * so a channel that then failed to open read as started, its end read as a freeze, and the
         * docked screen reconnected forever instead of showing the error (1.13.4).
         */
        fun hasStarted(snapshot: PlayerPlaybackSnapshot): Boolean =
            snapshot.positionMs > 0L || snapshot.videoProgressTicks > 0L

        /** eof-reached may already be cleared by the time END_FILE is dispatched. */
        fun endFileSnapshot(snapshot: PlayerPlaybackSnapshot, reason: String?, isLive: Boolean): PlayerPlaybackSnapshot =
            if (isLive && (reason == "eof" || reason == "error")) {
                snapshot.copy(isEnded = true, isPlaying = false, isLoading = false)
            } else snapshot
    }
}
