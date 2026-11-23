package com.nuvio.app.features.sportsdetail.internal

import com.nuvio.app.features.radar.DelayedValueBuffer
import com.nuvio.app.features.radar.ScoreHidden
import com.nuvio.app.features.radar.ScorePending
import com.nuvio.app.features.radar.ScoreShown
import com.nuvio.app.features.radar.ScoreSlots
import com.nuvio.app.features.radar.ScoreSpoilerPolicy
import com.nuvio.app.features.radar.ScoreSpoilerSettings
import com.nuvio.app.features.sportsdetail.api.BasketballStripBlock
import com.nuvio.app.features.sportsdetail.api.EventHeader
import com.nuvio.app.features.sportsdetail.api.GameBlock
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.Header
import com.nuvio.app.features.sportsdetail.api.HighlightsBlock
import com.nuvio.app.features.sportsdetail.api.LeadersBlock
import com.nuvio.app.features.sportsdetail.api.LinescoreBlock
import com.nuvio.app.features.sportsdetail.api.PlaysBlock
import com.nuvio.app.features.sportsdetail.api.ResultsBlock
import com.nuvio.app.features.sportsdetail.api.SoccerStripBlock
import com.nuvio.app.features.sportsdetail.api.StatsBlock
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.TimelineBlock
import com.nuvio.app.features.sportsdetail.api.VersusHeader
import com.nuvio.app.features.sportsdetail.api.WinProbabilityBlock

/** What the game page draws under the score settings. [hidden] shows the "Scores hidden" chip; [pending] = delayed, nothing due yet. */
data class GameSpoilerView(val detail: GameDetail, val hidden: Boolean, val pending: Boolean)

/**
 * The game page's side of Hide scores + score delay (one per open game; phone/iPad controller and Apple TV model).
 * [record] runs where a fetch result lands; [view] picks the snapshot the delay allows ([ScoreSpoilerPolicy]) and
 * redacts it when hidden or pending. Pure state, no I/O, no timer: the screen schedules one local re-render at
 * [nextChangeAtMs].
 */
class GameSpoilerGate {
    private val buffer = DelayedValueBuffer<String, GameDetail>()
    @kotlin.concurrent.Volatile
    private var finishedAtFirstSight: Boolean? = null

    fun record(detail: GameDetail, atMs: Long) {
        if (finishedAtFirstSight == null) finishedAtFirstSight = detail.state == GameState.FINAL
        buffer.record(detail.gameId, detail, atMs)
    }

    fun view(newest: GameDetail, settings: ScoreSpoilerSettings, nowMs: Long): GameSpoilerView =
        when (val v = ScoreSpoilerPolicy.resolve(settings, newest.gameId, newest, finishedAtFirstSight == true, buffer, nowMs)) {
            is ScoreShown -> GameSpoilerView(v.value ?: newest, hidden = false, pending = false)
            ScoreHidden -> GameSpoilerView(GameSpoilerRedaction.redact(newest, scorePlaceholder = null), hidden = true, pending = false)
            ScorePending -> GameSpoilerView(GameSpoilerRedaction.redact(newest, scorePlaceholder = ScoreSlots.PLACEHOLDER), hidden = false, pending = true)
        }

    /** When a buffered snapshot becomes due under [settings]; null when hidden, undelayed or nothing is pending. */
    fun nextChangeAtMs(settings: ScoreSpoilerSettings, nowMs: Long): Long? =
        if (!settings.shown || finishedAtFirstSight == true) null else buffer.nextChangeAtMs(nowMs, settings.delayMs)
}

/**
 * Strips what would spoil a result. Keeps teams, kick-off, the LIVE clock / "Final" status, standings, line-ups, form,
 * broadcast and Watch; drops the scoreboard numbers and win chance always, and for a live or final game also the box
 * score, team stats, leaders, plays / scoring summary (and so the last play), results, highlights, scorers, the last
 * soccer event and the basketball scoring run.
 */
object GameSpoilerRedaction {
    fun redact(detail: GameDetail, scorePlaceholder: String?): GameDetail {
        val started = detail.state == GameState.LIVE || detail.state == GameState.FINAL
        return detail.copy(
            header = header(detail.header, scorePlaceholder),
            blocks = detail.blocks.mapNotNull { block(it, started) },
        )
    }

    private fun header(h: Header, placeholder: String?): Header = when (h) {
        is TeamsHeader -> h.copy(
            home = h.home.copy(score = h.home.score?.let { placeholder }),
            away = h.away.copy(score = h.away.score?.let { placeholder }),
        )
        is VersusHeader -> h.copy(a = h.a.copy(score = h.a.score?.let { placeholder }), b = h.b.copy(score = h.b.score?.let { placeholder }))
        is EventHeader -> h
    }

    private fun block(b: GameBlock, started: Boolean): GameBlock? = when {
        b is WinProbabilityBlock -> null
        !started -> b
        b is LinescoreBlock || b is StatsBlock || b is LeadersBlock || b is PlaysBlock || b is TimelineBlock ||
            b is ResultsBlock || b is HighlightsBlock -> null
        b is SoccerStripBlock -> b.copy(lastEvent = null, homeScorers = emptyList(), awayScorers = emptyList())
        b is BasketballStripBlock -> b.copy(run = null)
        else -> b
    }
}
