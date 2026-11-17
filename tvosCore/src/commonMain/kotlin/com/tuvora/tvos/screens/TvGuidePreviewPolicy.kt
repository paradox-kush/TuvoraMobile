package com.tuvora.tvos.screens

enum class TvGuidePhase { Loading, Playing, Failed }
data class TvGuidePreviewStatus(val phase: TvGuidePhase, val message: String?)

/**
 * What the live guide's preview strip shows for a channel the viewer opened. Every open ends: playing, or failed with a
 * reason and Retry. The strip holds this deadline itself (bounded-load rule 3) so a resolve or engine that never answers
 * cannot leave a silent black box.
 */
object TvGuidePreviewPolicy {
    const val STALL_MS = 25_000L
    const val UNREACHABLE_MESSAGE = "Couldn't reach this channel. Check the playlist's connection, then try again."
    const val STALLED_MESSAGE = "This channel isn't starting. Try again, or pick another."

    /** The channel link or handshake failed before any session existed (a down portal): fail at once, never wait for the deadline. */
    fun resolveFailed(): TvGuidePreviewStatus = TvGuidePreviewStatus(TvGuidePhase.Failed, UNREACHABLE_MESSAGE)

    fun offersRetry(status: TvGuidePreviewStatus): Boolean = status.phase == TvGuidePhase.Failed

    /** [started] is real evidence of playback (a decoded frame or position), never the engine's raw playing flag, which is true while idle. */
    fun status(elapsedMs: Long, started: Boolean, error: String?): TvGuidePreviewStatus = when {
        error != null -> TvGuidePreviewStatus(TvGuidePhase.Failed, error)
        started -> TvGuidePreviewStatus(TvGuidePhase.Playing, null)
        elapsedMs >= STALL_MS -> TvGuidePreviewStatus(TvGuidePhase.Failed, STALLED_MESSAGE)
        else -> TvGuidePreviewStatus(TvGuidePhase.Loading, null)
    }
}
