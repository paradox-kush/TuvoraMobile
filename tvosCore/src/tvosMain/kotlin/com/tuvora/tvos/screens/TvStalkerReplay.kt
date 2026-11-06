package com.tuvora.tvos.screens

import com.nuvio.app.features.iptv.BoundedLoad
import com.nuvio.app.features.iptv.LoadSurface
import com.nuvio.app.features.iptv.ReplayOpenFence
import com.nuvio.app.features.iptv.stalker.StalkerArchiveAnswer
import com.nuvio.app.features.iptv.stalker.StalkerReplayFailure
import com.nuvio.app.features.iptv.stalker.StalkerReplayOutcome
import com.nuvio.app.features.livetv.ReplayMintGate
import com.tuvora.tvos.player.TvResolvedSource
import kotlinx.coroutines.delay

/** How asking a Stalker portal for one replay ended, before any player exists. */
internal sealed interface TvStalkerStart {
    /** The minted single-use link, resolved like a live link. The caller opens ONE session on it. */
    data class Play(val source: TvResolvedSource, val ticket: Long) : TvStalkerStart

    /** Nothing to play: the words, and whether Retry (a fresh mint) is offered. */
    data class Notice(val text: String, val canRetry: Boolean) : TvStalkerStart

    /** A double press, or a newer open overtook this one: publish nothing, change nothing. */
    data object Ignore : TvStalkerStart
}

/**
 * Starts a Stalker replay: ONE `create_link` under the live-resolve deadline, one resolve, no walk and no
 * probe. A second press while a mint is in flight is ignored BEFORE the open fence moves, so the first mint
 * stays current (moving it first would drop the first result and play nothing).
 */
internal class TvStalkerReplayStarter(
    private val fence: ReplayOpenFence,
    private val gate: ReplayMintGate = ReplayMintGate(),
) {
    suspend fun start(
        sourceType: String,
        programmeTitle: String,
        mint: suspend () -> StalkerArchiveAnswer?,
        resolve: suspend (String) -> TvResolvedSource?,
    ): TvStalkerStart {
        if (!gate.tryEnter()) return TvStalkerStart.Ignore
        try {
            val ticket = fence.next()
            val outcome = StalkerReplayOutcome.ofLoad(
                BoundedLoad.run<StalkerArchiveAnswer?>(
                    surface = LoadSurface.LIVE_RESOLVE,
                    isEmpty = { it == null },
                    report = mapOf("source_type" to sourceType, "kind" to "catchup_link"),
                    work = mint,
                ),
            )
            if (!fence.isCurrent(ticket)) return TvStalkerStart.Ignore
            return when (outcome) {
                StalkerReplayOutcome.NoRecording -> TvStalkerStart.Notice(TvReplayPolicy.noRecordingText(programmeTitle), canRetry = false)
                is StalkerReplayOutcome.Refused -> TvStalkerStart.Notice(TvStalkerReplayText.forFailure(outcome.failure), canRetry = true)
                is StalkerReplayOutcome.Link -> {
                    val source = resolve(outcome.url)
                    when {
                        !fence.isCurrent(ticket) -> TvStalkerStart.Ignore
                        source == null -> TvStalkerStart.Notice(TvStalkerReplayText.forFailure(StalkerReplayFailure.NO_ANSWER), canRetry = true)
                        else -> TvStalkerStart.Play(source, ticket)
                    }
                }
            }
        } finally {
            gate.exit()
        }
    }
}

/**
 * Follows the ONE session a Stalker replay opened. It never re-opens the link and never hands over a
 * successor: a failure ends with the portal's words and Retry (Swift asks again, a fresh mint).
 */
internal class TvStalkerReplay(
    private val fence: ReplayOpenFence,
    private val ticket: Long,
    private val run: TvStalkerReplayRun = TvStalkerReplayRun(),
    private val tickMs: Long = 1_000L,
) : TvReplaySupervisor {
    private var finished = false

    override suspend fun supervise(session: com.tuvora.tvos.player.TvPlayerSession): TvReplayStep? =
        superviseOn(TvSessionPlayback(session))

    internal suspend fun superviseOn(playback: TvReplayPlayback): TvReplayStep? {
        if (finished) return null
        while (true) {
            if (playback.isClosed || !fence.isCurrent(ticket)) { finished = true; return null }
            val s = playback.snapshot()
            when (val move = run.observe(s.isPlaying, s.positionMs, s.isEnded, s.errorMessage)) {
                TvStalkerReplayRun.Move.Wait -> delay(tickMs)
                TvStalkerReplayRun.Move.Done -> { finished = true; return null }
                is TvStalkerReplayRun.Move.Failed -> {
                    finished = true
                    return TvReplayStep(null, TvStalkerReplayText.forFailure(move.failure), canRetry = true)
                }
            }
        }
    }
}
