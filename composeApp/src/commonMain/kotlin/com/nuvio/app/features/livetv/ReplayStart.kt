package com.nuvio.app.features.livetv

import com.nuvio.app.features.common.lifecycle.EffectScope
import com.nuvio.app.features.iptv.CatchUpDialectWalk
import com.nuvio.app.features.iptv.M3uReplayOpen
import com.nuvio.app.features.iptv.BoundedLoad
import com.nuvio.app.features.iptv.LoadSurface
import com.nuvio.app.features.iptv.M3uReplayWalk
import com.nuvio.app.features.iptv.stalker.StalkerArchiveAnswer
import com.nuvio.app.features.iptv.stalker.StalkerReplayOutcome
import com.nuvio.app.features.iptv.stalker.StalkerReplayFailure

/**
 * One Stalker mint at a time per screen: a double press (or a Retry on top of a slow first open) must not mint
 * two single-use links - the second would be a wasted token against the portal's session cap.
 */
internal class ReplayMintGate {
    private val busy = kotlinx.atomicfu.atomic(false)

    /** True = the caller owns the mint and must [exit] when it ends; false = one is already in flight. */
    fun tryEnter(): Boolean = busy.compareAndSet(false, true)

    fun exit() { busy.value = false }

    val isBusy: Boolean get() = busy.value
}

/** How starting a replay ended: a session to show, "no recording", or a failure the viewer can retry. */
internal sealed interface ReplayStart {
    data class Started(val session: CatchUpSession) : ReplayStart

    /** The channel has no recording to offer (no catch-up, link cannot be built, playlist gone). */
    data object NoRecording : ReplayStart

    /** Opening threw or hit its deadline; never "no recording". */
    data class Failed(val timedOut: Boolean) : ReplayStart

    /** A Stalker mint is already in flight (a double press): nothing was asked, nothing changes. */
    data object InFlight : ReplayStart

    /** A Stalker portal refused or never answered; shown with its own words and a Retry that mints a fresh link. */
    data class Refused(val failure: StalkerReplayFailure) : ReplayStart
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
    /** A Stalker portal: one fresh create_link for the programme (null = no programme id). */
    isStalker: Boolean = false,
    stalkerLink: suspend () -> StalkerArchiveAnswer? = { null },
    mintGate: ReplayMintGate? = null,
): ReplayStart {
    if (isStalker) {
        if (mintGate != null && !mintGate.tryEnter()) return ReplayStart.InFlight
        try {
            return startStalkerReplay(sourceType, contentId, programmeTitle, startMs, endMs, admit, stalkerLink)
        } finally {
            mintGate?.exit()
        }
    }
    return startWalkedReplay(
        isM3uPlaylist, sourceType, contentId, programmeTitle, startMs, endMs, nowMs,
        m3uWalk, dialectWalk, m3uRequest, xtreamRequest, scope, admit,
    )
}

private suspend fun startStalkerReplay(
    sourceType: String,
    contentId: String,
    programmeTitle: String,
    startMs: Long,
    endMs: Long,
    admit: () -> Boolean,
    stalkerLink: suspend () -> StalkerArchiveAnswer?,
): ReplayStart {
    // A Stalker replay is ONE minted single-use link: no walk, no probe, no restart. The mint runs under the
    // live-resolve deadline, so a portal that never answers ends as a failure with Retry.
    val outcome = StalkerReplayOutcome.ofLoad(
        BoundedLoad.run<StalkerArchiveAnswer?>(
            surface = LoadSurface.LIVE_RESOLVE,
            isEmpty = { it == null },
            report = mapOf("source_type" to sourceType, "kind" to "catchup_link"),
            work = stalkerLink,
        ),
    )
    return when (outcome) {
        is StalkerReplayOutcome.Link ->
            if (admit()) ReplayStart.Started(beginStalkerReplay(outcome.url, contentId, programmeTitle, startMs, endMs))
            else ReplayStart.NoRecording
        is StalkerReplayOutcome.Refused -> ReplayStart.Refused(outcome.failure)
        StalkerReplayOutcome.NoRecording -> ReplayStart.NoRecording
    }
}

private suspend fun startWalkedReplay(
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
    scope: EffectScope?,
    admit: () -> Boolean,
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

/**
 * A Stalker replay's session: the one minted link, flagged single-use. No ladder (nothing to walk or restart),
 * no continuation (the portal serves the programme), and a new token per mint so a stale result is ignorable.
 */
internal fun beginStalkerReplay(url: String, contentId: String, programmeTitle: String, startMs: Long, endMs: Long): CatchUpSession =
    CatchUpSession(
        contentId = contentId,
        programmeTitle = programmeTitle,
        startMs = startMs,
        endMs = endMs,
        attempt = com.nuvio.app.features.iptv.CatchUpDialectWalk.Attempt(
            token = nextStalkerToken(),
            dialect = com.nuvio.app.features.iptv.CatchUpDialectWalk.Dialect.PLAYLIST_LINK,
            url = url,
        ),
        singleUse = true,
    )

/** Stalker mints sit outside any walk; their tokens only need to differ from one another. Negative so they never collide with a walk's. */
private var stalkerTokenSeq = 0L

private fun nextStalkerToken(): Long = kotlinx.atomicfu.locks.synchronized(stalkerTokenLock) { --stalkerTokenSeq }

private val stalkerTokenLock = kotlinx.atomicfu.locks.SynchronizedObject()
