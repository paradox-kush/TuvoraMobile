package com.tuvora.tvos.player

/**
 * The code-only failure description a "Send report" carries from the playback error card. The card's message is
 * viewer text (it can name a provider), so the report gets one of these fixed codes instead — never the message.
 * `*_stalled` means playback had started and then stopped (the shared ReportCategoryInference reads it as Freezes);
 * `*_open_failed` means it never started.
 */
object TvPlayerFailureCode {
    const val NO_ENGINE = "no_engine"
    const val LIVE_OPEN_FAILED = "live_open_failed"
    const val LIVE_STALLED = "live_stalled"
    const val VOD_OPEN_FAILED = "vod_open_failed"
    const val VOD_STALLED = "vod_stalled"

    fun of(isLive: Boolean, positionMs: Long, message: String?): String = when {
        message?.contains("No player available", ignoreCase = true) == true -> NO_ENGINE
        isLive -> if (positionMs > 0) LIVE_STALLED else LIVE_OPEN_FAILED
        else -> if (positionMs > 0) VOD_STALLED else VOD_OPEN_FAILED
    }
}
