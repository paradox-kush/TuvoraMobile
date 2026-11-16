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
    const val STALLED_MESSAGE = "This channel isn't starting. Try again, or pick another."

    fun status(elapsedMs: Long, playing: Boolean, error: String?): TvGuidePreviewStatus = when {
        error != null -> TvGuidePreviewStatus(TvGuidePhase.Failed, error)
        playing -> TvGuidePreviewStatus(TvGuidePhase.Playing, null)
        elapsedMs >= STALL_MS -> TvGuidePreviewStatus(TvGuidePhase.Failed, STALLED_MESSAGE)
        else -> TvGuidePreviewStatus(TvGuidePhase.Loading, null)
    }
}
