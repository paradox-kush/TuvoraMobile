package com.nuvio.app.features.livetv

import com.nuvio.app.features.iptv.XtreamProgram

internal data class GuideNowNext(val now: XtreamProgram?, val next: XtreamProgram?)

/** Live labels use timestamps, never the provider's load-time nowPlaying flag. */
internal object GuideNowNextPolicy {
    fun select(programmes: List<XtreamProgram>, nowMs: Long): GuideNowNext {
        val sorted = programmes.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        return GuideNowNext(
            now = sorted.firstOrNull { it.startMs <= nowMs && nowMs < it.endMs },
            next = sorted.firstOrNull { it.startMs > nowMs },
        )
    }

    /**
     * The now-bar during a replay names the programme being replayed and the one after it, never the
     * show airing now. A replay with no matching guide row (a Sports replay) still names itself.
     */
    fun selectForReplay(programmes: List<XtreamProgram>, startMs: Long, endMs: Long, title: String): GuideNowNext {
        val sorted = programmes.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        val now = sorted.firstOrNull { it.startMs == startMs }
            ?: XtreamProgram(title, "", startMs, endMs, false)
        return GuideNowNext(now = now, next = sorted.firstOrNull { it.startMs >= now.endMs })
    }
}
