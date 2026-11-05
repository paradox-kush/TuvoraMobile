package com.nuvio.app.features.livetv

import com.nuvio.app.features.common.lifecycle.EffectScope
import com.nuvio.app.features.iptv.CatchUpDialectWalk
import com.nuvio.app.features.iptv.M3uReplayOpen
import com.nuvio.app.features.iptv.M3uReplayWalk

/** How starting a replay ended: a session to show, "no recording", or a failure the viewer can retry. */
internal sealed interface ReplayStart {
    data class Started(val session: CatchUpSession) : ReplayStart

    /** The channel has no recording to offer (no catch-up, link cannot be built, playlist gone). */
    data object NoRecording : ReplayStart

    /** Opening threw or hit its deadline; never "no recording". */
    data class Failed(val timedOut: Boolean) : ReplayStart
}

/**
 * The ONE way a replay starts, for BOTH entries - a guide cell on this screen and a Sports Centre replay
 * arriving with the launch. Which walk a channel's replay uses is decided here and nowhere else: an M3U
 * playlist builds from its own catch-up ([M3uReplayWalk], opened under a deadline), anything else asks the
 * Xtream dialect walk. The launch path used to be Xtream-only, so a sports replay on an M3U channel got no
 * request and said "no recording".
 *
 * The request builders are lambdas so the decision tests without the content database.
 */
internal suspend fun startReplay(
    isM3uPlaylist: Boolean,
    sourceType: String,
    contentId: String,
    programmeTitle: String,
    startMs: Long,
    endMs: Long,
    nowMs: Long,
    m3uWalk: M3uReplayWalk,
    dialectWalk: CatchUpDialectWalk,
    m3uRequest: suspend () -> M3uReplayWalk.Request?,
    xtreamRequest: suspend () -> CatchUpDialectWalk.Request?,
    scope: EffectScope? = null,
    admit: () -> Boolean = { true },
): ReplayStart {
    if (isM3uPlaylist) {
        return when (val open = M3uReplayOpen.run(sourceType, m3uRequest)) {
            is M3uReplayOpen.Failed -> ReplayStart.Failed(open.timedOut)
            M3uReplayOpen.Unavailable -> ReplayStart.NoRecording
            is M3uReplayOpen.Ready ->
                beginM3uReplay(m3uWalk, open.request, contentId, programmeTitle, startMs, endMs, scope, admit)
                    ?.let(ReplayStart::Started) ?: ReplayStart.NoRecording
        }
    }
    val request = xtreamRequest()
    return beginLaunchReplay(dialectWalk, request, contentId, programmeTitle, startMs, endMs, nowMs, scope, admit)
        ?.let(ReplayStart::Started) ?: ReplayStart.NoRecording
}
