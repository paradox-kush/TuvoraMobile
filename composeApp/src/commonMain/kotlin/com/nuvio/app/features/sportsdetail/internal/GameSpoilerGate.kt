package com.nuvio.app.features.sportsdetail.internal

import com.nuvio.app.features.radar.DelayedValueBuffer
import com.nuvio.app.features.radar.ScoreHidden
import com.nuvio.app.features.radar.ScorePending
import com.nuvio.app.features.radar.ScoreShown
import com.nuvio.app.features.radar.ScoreSlots
import com.nuvio.app.features.radar.ScoreSpoilerPolicy
import com.nuvio.app.features.radar.ScoreSpoilerSettings
import com.nuvio.app.features.sportsdetail.api.BroadcastBlock
import com.nuvio.app.features.sportsdetail.api.EventHeader
import com.nuvio.app.features.sportsdetail.api.FormBlock
import com.nuvio.app.features.sportsdetail.api.GameBlock
import com.nuvio.app.features.sportsdetail.api.GameDetail
import com.nuvio.app.features.sportsdetail.api.GameState
import com.nuvio.app.features.sportsdetail.api.Header
import com.nuvio.app.features.sportsdetail.api.LineupBlock
import com.nuvio.app.features.sportsdetail.api.NextBlock
import com.nuvio.app.features.sportsdetail.api.Side
import com.nuvio.app.features.sportsdetail.api.SpoilerRedaction
import com.nuvio.app.features.sportsdetail.api.StandingsBlock
import com.nuvio.app.features.sportsdetail.api.StandingsTableBlock
import com.nuvio.app.features.sportsdetail.api.TeamsHeader
import com.nuvio.app.features.sportsdetail.api.VersusHeader
import com.nuvio.app.features.sportsdetail.api.WatchBlock

/** What the game page draws under the score settings. [hidden] shows the "Scores hidden" chip; [pending] = delayed, nothing due yet. */
data class GameSpoilerView(val detail: GameDetail, val hidden: Boolean, val pending: Boolean) {
    /** The "Scores hidden" reveal chip: only for a live or final game (an upcoming one has no score to reveal), every platform. */
    val showsRevealChip: Boolean get() = hidden && (detail.state == GameState.LIVE || detail.state == GameState.FINAL)
}

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
 * Strips what would spoil a result. The scoreboard numbers go always. For a live or final game the blocks are an
 * ALLOW-list ([keepsWhenStarted]: Watch, broadcast, standings, line-ups, form, next games) — anything else, including a
 * block type added later, is dropped — and the sides' records (they move with the result) go too. Upcoming games keep
 * every block: there is nothing to spoil before the start.
 */
object GameSpoilerRedaction {
    fun redact(detail: GameDetail, scorePlaceholder: String?): GameDetail {
        val started = detail.state == GameState.LIVE || detail.state == GameState.FINAL
        return detail.copy(
            header = header(detail.header, scorePlaceholder, started),
            blocks = if (started) detail.blocks.filter(::keepsWhenStarted) else detail.blocks,
            spoilerRedaction = if (scorePlaceholder == null) SpoilerRedaction.HIDDEN else SpoilerRedaction.PENDING,
        )
    }

    /** The only blocks a started game shows while redacted: none of them carries the score or how the game is going. */
    fun keepsWhenStarted(b: GameBlock): Boolean =
        b is WatchBlock || b is BroadcastBlock || b is StandingsBlock || b is StandingsTableBlock || b is LineupBlock ||
            b is FormBlock || b is NextBlock

    private fun side(s: Side, placeholder: String?, started: Boolean): Side =
        s.copy(score = s.score?.let { placeholder }, record = s.record.takeUnless { started })

    // EventHeader (motorsport weekend, fight card) carries name, session and venue only: no result to strip.
    private fun header(h: Header, placeholder: String?, started: Boolean): Header = when (h) {
        is TeamsHeader -> h.copy(home = side(h.home, placeholder, started), away = side(h.away, placeholder, started))
        is VersusHeader -> h.copy(a = side(h.a, placeholder, started), b = side(h.b, placeholder, started))
        is EventHeader -> h
    }
}
